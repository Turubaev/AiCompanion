# -*- coding: utf-8 -*-
"""
HTTP-сервер поиска по индексу эмбеддингов для RAG.
Запуск: python rag_server.py [--port 5001]
Эндпоинт: POST /search  Body: {"query": "текст запроса", "top_k": 5}  -> {"chunks": [{"text": "...", "score": ...}, ...]}
"""
from pathlib import Path
import argparse
import json

OUT_DIR = Path(__file__).resolve().parent / "output"
JSON_INDEX = OUT_DIR / "index.json"
FAISS_INDEX = OUT_DIR / "index.faiss"

# Глобальные объекты модели и индекса (загружаются при старте)
_embedder = None
_faiss_index = None
_chunks = None


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


def search(query: str, top_k: int = 5) -> list[dict]:
    """
    Гибридный поиск: чанки с вхождением слов запроса + семантика FAISS.
    Так фрагменты вроде «Глазунов Анатолий Алексеевич, доктор…» попадают в ответ
    при запросе «кто такой глазунов анатолий алексеевич», даже если score FAISS низкий.
    """
    import numpy as np
    load_index()
    seen_idx: set[int] = set()
    results: list[dict] = []
    # 1) Чанки, где есть слова из запроса (приоритет)
    words = _query_words(query)
    if words:
        for idx, ch in enumerate(_chunks):
            text_lower = (ch.get("text") or "").lower()
            if any(w in text_lower for w in words):
                if idx not in seen_idx:
                    seen_idx.add(idx)
                    results.append({**_chunks[idx], "score": 0.95})
    # 2) Семантический поиск FAISS
    query_emb = _embedder.encode([query], normalize_embeddings=True)
    query_emb = np.array(query_emb, dtype=np.float32).reshape(1, -1)
    if not query_emb.flags.c_contiguous:
        query_emb = np.ascontiguousarray(query_emb)
    k = min(top_k * 2, len(_chunks))  # взять с запасом, потом обрежем
    scores, indices = _faiss_index.search(query_emb, k)
    for score, idx in zip(scores[0], indices[0]):
        if idx < 0:
            break
        if idx not in seen_idx:
            seen_idx.add(idx)
            results.append({**_chunks[idx], "score": float(score)})
    # Сортируем по score по убыванию и берём top_k
    results.sort(key=lambda r: r["score"], reverse=True)
    return results[:top_k]


def create_app():
    try:
        from flask import Flask, request, jsonify
    except ImportError:
        raise ImportError("Install flask: pip install flask")
    app = Flask(__name__)

    @app.route("/search", methods=["POST"])
    def search_endpoint():
        try:
            body = request.get_json(force=True, silent=True) or {}
            q = body.get("query") or (request.args.get("query") if request.method == "GET" else None)
            if not q or not str(q).strip():
                return jsonify({"error": "Missing 'query'"}), 400
            top_k = int(body.get("top_k", 5))
            top_k = min(max(1, top_k), 20)
            results = search(str(q).strip(), top_k=top_k)
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
    args = parser.parse_args()
    app = create_app()
    print(f"RAG server starting on http://{args.host}:{args.port}")
    print("POST /search with JSON {\"query\": \"...\", \"top_k\": 5}")
    app.run(host=args.host, port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
