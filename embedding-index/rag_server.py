# -*- coding: utf-8 -*-
"""
HTTP-сервер поиска по индексу эмбеддингов для RAG.
Запуск: python rag_server.py [--port 5001] [--min-score 0.5]
Эндпоинт: POST /search  Body: {"query": "...", "top_k": 5, "min_score": 0.5}  -> {"chunks": [{"text": "...", "score": ...}, ...]}
Порог min_score: отсечение нерелевантных чанков (score < min_score не возвращаются). По умолчанию 0 или RAG_MIN_SCORE.
"""
from pathlib import Path
import argparse
import json
import os

OUT_DIR = Path(__file__).resolve().parent / "output"
JSON_INDEX = OUT_DIR / "index.json"
FAISS_INDEX = OUT_DIR / "index.faiss"

# Глобальные объекты модели и индекса (загружаются при старте)
_embedder = None
_faiss_index = None
_chunks = None
_reranker_model = None

# Модель reranker (мультиязычная, подходит для RU/EN)
RERANKER_MODEL = "BAAI/bge-reranker-base"


def load_index():
    global _embedder, _faiss_index, _chunks
    if _chunks is not None:
        return
    from embedder import get_embedder
    from index_store import load_json_index, load_faiss_index
    if not JSON_INDEX.exists() or not FAISS_INDEX.exists():
        raise FileNotFoundError("Index not found. Run build_index.py first.")
    _embedder = get_embedder()
    _chunks, _, _ = load_json_index(JSON_INDEX)
    _faiss_index = load_faiss_index(FAISS_INDEX)


# Слова-пустышки, по которым не ищем по вхождению в текст
_STOPWORDS = frozenset(
    "кто такой какой что как это какая какое какие на в и из для по о об от до при с со к у не нет да или а но же бы ли уже еще ещё только".split()
)


def _query_words(q: str) -> list[str]:
    """Извлекает значимые слова запроса (для гибридного поиска по вхождению)."""
    import re
    words = re.findall(r"[а-яёa-z]+", q.lower())
    return [w for w in words if len(w) >= 2 and w not in _STOPWORDS]


def _load_reranker():
    """Ленивая загрузка модели reranker (cross-encoder)."""
    global _reranker_model
    if _reranker_model is None:
        from sentence_transformers import CrossEncoder
        _reranker_model = CrossEncoder(RERANKER_MODEL)


def _rerank(query: str, results: list[dict]) -> list[dict]:
    """
    Переранжирует чанки через cross-encoder. Заменяет score на оценку reranker,
    нормализованную в (0, 1) через sigmoid.
    """
    if not results:
        return results
    _load_reranker()
    pairs = [(query, (r.get("text") or "")[:512]) for r in results]  # обрезаем длинные чанки
    raw_scores = _reranker_model.predict(pairs)
    import math
    for i, s in enumerate(raw_scores):
        results[i]["score"] = 1.0 / (1.0 + math.exp(-float(s)))  # sigmoid -> (0, 1)
    results.sort(key=lambda x: x["score"], reverse=True)
    return results


def search(query: str, top_k: int = 5, min_score: float = 0.0, use_reranker: bool = False) -> list[dict]:
    """
    Гибридный поиск: чанки с вхождением слов запроса + семантика FAISS.
    Опционально: reranker (cross-encoder) переранжирует кандидатов.
    Затем отсекаются чанки с score < min_score (фильтр релевантности).
    """
    import numpy as np
    load_index()
    seen_idx: set[int] = set()
    results: list[dict] = []
    # 1) Чанки, где есть слова из запроса (приоритет). Оценка 0.80 — ниже 0.95,
    #    чтобы при высоком min_score (0.85–0.95) порог релевантности отсекал
    #    только совпадения по словам и оставлял самые семантически релевантные.
    KEYWORD_MATCH_SCORE = 0.80
    words = _query_words(query)
    if words:
        for idx, ch in enumerate(_chunks):
            text_lower = (ch.get("text") or "").lower()
            if any(w in text_lower for w in words):
                if idx not in seen_idx:
                    seen_idx.add(idx)
                    results.append({**_chunks[idx], "score": KEYWORD_MATCH_SCORE})
    # 2) Семантический поиск FAISS
    query_emb = _embedder.encode([query], normalize_embeddings=True)
    query_emb = np.array(query_emb, dtype=np.float32).reshape(1, -1)
    if not query_emb.flags.c_contiguous:
        query_emb = np.ascontiguousarray(query_emb)
    n_chunks = len(_chunks)
    k = min((top_k * 3 if use_reranker else top_k * 2), n_chunks)  # для reranker берём больше кандидатов
    scores, indices = _faiss_index.search(query_emb, k)
    for score, idx in zip(scores[0], indices[0]):
        if idx < 0 or idx >= n_chunks:  # защита от рассинхрона FAISS и JSON
            continue
        if idx not in seen_idx:
            seen_idx.add(idx)
            results.append({**_chunks[idx], "score": float(score)})
    results.sort(key=lambda r: r["score"], reverse=True)
    results = results[: top_k * 3 if use_reranker else top_k]
    # 3) Опционально: переранжирование через cross-encoder
    if use_reranker and results:
        results = _rerank(query, results)
    results = results[:top_k]
    min_score_f = float(min_score)
    n_before = len(results)
    if min_score_f > 0:
        scores_before = [round(float(r["score"]), 3) for r in results]
        results = [r for r in results if float(r["score"]) >= min_score_f]
        print(f"RAG min_score={min_score_f}: scores={scores_before} -> {len(results)} chunks (filtered)")
    return results


def create_app(default_min_score: float = 0.0, default_use_reranker: bool = False):
    try:
        from flask import Flask, request, jsonify
    except ImportError:
        raise ImportError("Install flask: pip install flask")
    app = Flask(__name__)
    app.config["RAG_DEFAULT_MIN_SCORE"] = default_min_score
    app.config["RAG_DEFAULT_USE_RERANKER"] = default_use_reranker

    @app.route("/search", methods=["POST"])
    def search_endpoint():
        try:
            from flask import current_app
            body = request.get_json(force=True, silent=True) or {}
            q = body.get("query") or (request.args.get("query") if request.method == "GET" else None)
            if not q or not str(q).strip():
                return jsonify({"error": "Missing 'query'"}), 400
            top_k = int(body.get("top_k", 5))
            top_k = min(max(1, top_k), 20)
            # Порог: из запроса или по умолчанию с сервера (--min-score / RAG_MIN_SCORE)
            min_score = body.get("min_score")
            if min_score is None:
                min_score = current_app.config.get("RAG_DEFAULT_MIN_SCORE", 0.0)
            else:
                min_score = float(min_score)
            use_reranker = body.get("use_reranker")
            if use_reranker is None:
                use_reranker = current_app.config.get("RAG_DEFAULT_USE_RERANKER", False)
            else:
                use_reranker = bool(use_reranker)
            results = search(str(q).strip(), top_k=top_k, min_score=min_score, use_reranker=use_reranker)
            # Возвращаем только text и score для экономии трафика
            chunks = [{"text": r["text"], "score": r["score"]} for r in results]
            return jsonify({"chunks": chunks})
        except FileNotFoundError as e:
            return jsonify({"error": str(e)}), 503
        except Exception as e:
            return jsonify({"error": str(e)}), 500

    @app.route("/health", methods=["GET"])
    def health():
        try:
            load_index()
            return jsonify({"status": "ok", "chunks_loaded": len(_chunks)})
        except Exception as e:
            return jsonify({"status": "error", "error": str(e)}), 503

    return app


def main():
    parser = argparse.ArgumentParser(description="RAG search server for embedding index")
    parser.add_argument("--port", type=int, default=5001, help="Port (default: 5001)")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--min-score", type=float, default=None, help="Min relevance score (0.0-1.0). Overrides RAG_MIN_SCORE env.")
    parser.add_argument("--reranker", action="store_true", help="Use cross-encoder reranker (BAAI/bge-reranker-base) for better relevance.")
    args = parser.parse_args()
    default_min = 0.0
    if args.min_score is not None:
        default_min = max(0.0, min(1.0, args.min_score))
    else:
        try:
            default_min = max(0.0, min(1.0, float(os.environ.get("RAG_MIN_SCORE", "0"))))
        except (TypeError, ValueError):
            pass
    app = create_app(default_min_score=default_min, default_use_reranker=args.reranker)
    print(f"RAG server starting on http://{args.host}:{args.port}")
    print("POST /search with JSON {\"query\": \"...\", \"top_k\": 5, \"min_score\": <optional>, \"use_reranker\": <optional>}")
    if default_min > 0:
        print(f"Default min_score (filter): {default_min}")
    if args.reranker:
        print("Reranker: enabled (BAAI/bge-reranker-base)")
    app.run(host=args.host, port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
