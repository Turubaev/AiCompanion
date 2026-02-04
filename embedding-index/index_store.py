# -*- coding: utf-8 -*-
"""
Сохранение и загрузка индекса: JSON (полный дамп) и FAISS (быстрый поиск).
"""
import json
from pathlib import Path

import numpy as np


def save_json_index(
    chunks: list[dict],
    embeddings: np.ndarray,
    out_path: Path,
    meta: dict | None = None,
) -> None:
    """
    Сохраняет индекс в JSON: список чанков с текстом и метаданными,
    отдельно массив эмбеддингов (list of lists для сериализации).
    """
    payload = {
        "meta": meta or {},
        "chunks": chunks,
        "embeddings": embeddings.tolist(),
        "dim": int(embeddings.shape[1]),
        "num_chunks": len(chunks),
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def load_json_index(path: Path) -> tuple[list[dict], np.ndarray, dict]:
    """Загружает индекс из JSON. Возвращает (chunks, embeddings, meta)."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    chunks = data["chunks"]
    embeddings = np.array(data["embeddings"], dtype=np.float32)
    meta = data.get("meta", {})
    return chunks, embeddings, meta


def save_faiss_index(embeddings: np.ndarray, out_path: Path) -> None:
    """Сохраняет только векторы в индекс FAISS (IndexFlatIP для cosine)."""
    import faiss
    dim = embeddings.shape[1]
    index = faiss.IndexFlatIP(dim)  # inner product = cosine if normalized
    index.add(embeddings.astype(np.float32))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    faiss.write_index(index, str(out_path))


def load_faiss_index(path: Path) -> "faiss.Index":
    """Загружает FAISS-индекс из файла."""
    import faiss
    return faiss.read_index(str(path))


def search_faiss(
    query_embedding: np.ndarray,
    faiss_index_path: Path,
    json_index_path: Path,
    top_k: int = 5,
) -> list[dict]:
    """
    Поиск по запросу: загружает FAISS + JSON, возвращает top_k чанков с метаданными.
    query_embedding должен быть нормализован (как при encode(..., normalize_embeddings=True)).
    """
    import faiss
    index = load_faiss_index(faiss_index_path)
    chunks, _, _ = load_json_index(json_index_path)
    query_embedding = query_embedding.astype(np.float32).reshape(1, -1)
    if not query_embedding.flags.c_contiguous:
        query_embedding = np.ascontiguousarray(query_embedding)
    scores, indices = index.search(query_embedding, min(top_k, len(chunks)))
    results = []
    for score, idx in zip(scores[0], indices[0]):
        if idx < 0:
            break
        results.append({**chunks[idx], "score": float(score)})
    return results
