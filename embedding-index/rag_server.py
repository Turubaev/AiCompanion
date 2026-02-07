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
    sources_in_index = set()
    for ch in _chunks:
        path = ch.get("source_path") or ""
        name = Path(path).name if path else ch.get("doc_id") or ""
        if name:
            sources_in_index.add(name)
    print(f"RAG index: {len(_chunks)} chunks, {len(sources_in_index)} source(s): {sorted(sources_in_index)}")


# Слова-пустышки, по которым не ищем по вхождению в текст
_STOPWORDS = frozenset(
    "кто такой какой что как это какая какое какие на в и из для по о об от до при с со к у не нет да или а но же бы ли уже еще ещё только".split()
)

# Минимальная длина "специфичного" слова — чтобы не тянуть чанки из другого документа
# только из-за общих слов вроде "документе", "про", "минимальный" (в автореферате тоже бывает)
_MIN_SPECIFIC_WORD_LEN = 7


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


def _source_label_from_chunk(r: dict) -> str:
    path = r.get("source_path") or ""
    name = Path(path).name if path else ""
    return name or r.get("doc_id") or "источник"


def _diversify_by_source(results: list[dict], top_k: int) -> list[dict]:
    """
    Если в результатах несколько источников (документов), не отдаём все top_k
    из одного — перемежаем чанками из других источников, чтобы в ответе были
    оба документа при релевантности (исправляет ситуацию «все 10 чанков из Test_sample.pdf»).
    """
    if len(results) <= top_k:
        return results
    by_source: dict[str, list[dict]] = {}
    for r in results:
        src = _source_label_from_chunk(r)
        by_source.setdefault(src, []).append(r)
    if len(by_source) < 2:
        return results[:top_k]
    # Есть несколько источников — набираем top_k, по очереди беря лучший чанк от каждого источника
    out: list[dict] = []
    indices = {src: 0 for src in by_source}
    while len(out) < top_k:
        added = False
        for src in sorted(by_source.keys()):
            if len(out) >= top_k:
                break
            lst = by_source[src]
            i = indices[src]
            if i < len(lst):
                out.append(lst[i])
                indices[src] = i + 1
                added = True
        if not added:
            break
    return out


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
    # 1) Чанки, где есть слова из запроса (приоритет). Чтобы не тянуть чанки из другого
    #    документа из-за общих слов (например "документе", "про"), добавляем только если
    #    в чанке есть минимум 2 слова запроса и хотя бы одно "специфичное" (длина >= 7).
    KEYWORD_MATCH_SCORE = 0.80
    words = _query_words(query)
    specific_words = [w for w in words if len(w) >= _MIN_SPECIFIC_WORD_LEN]
    if words:
        for idx, ch in enumerate(_chunks):
            if idx in seen_idx:
                continue
            text_lower = (ch.get("text") or "").lower()
            matched = [w for w in words if w in text_lower]
            if len(matched) < 2:
                continue
            if specific_words and not any(w in text_lower for w in specific_words):
                continue
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
    # Пул кандидатов: без reranker раньше брали только top_k — тогда все 10 были из одного документа.
    # Берём больше и формируем пул по источникам, чтобы диверсификация могла подмешать второй документ.
    pool_size = top_k * 5
    by_source_pool: dict[str, list[dict]] = {}
    for r in results:
        src = _source_label_from_chunk(r)
        by_source_pool.setdefault(src, [])
        if len(by_source_pool[src]) < top_k * 2:
            by_source_pool[src].append(r)
    candidate_pool: list[dict] = []
    for src in sorted(by_source_pool.keys()):
        candidate_pool.extend(by_source_pool[src])
    candidate_pool.sort(key=lambda r: r["score"], reverse=True)
    results = candidate_pool[: pool_size]
    # 3) Опционально: переранжирование через cross-encoder
    if use_reranker and results:
        results = _rerank(query, results)
        results = results[: top_k * 3]
    # 4) Разнообразие по источникам: при нескольких документах не отдаём все top_k из одного
    results = _diversify_by_source(results, top_k)
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
            chunks = [
                {"text": r["text"], "score": r["score"], "source": _source_label_from_chunk(r)}
                for r in results
            ]
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
    print("Loading index...")
    load_index()
    app.run(host=args.host, port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
