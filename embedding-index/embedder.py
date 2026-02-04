# -*- coding: utf-8 -*-
"""
Генерация эмбеддингов для чанков.
Использует sentence-transformers (локально, без API).
"""
from pathlib import Path
from typing import List

from sentence_transformers import SentenceTransformer


# Модель с поддержкой русского и без необходимости API key
DEFAULT_MODEL = "sentence-transformers/paraphrase-multilingual-mpnet-base-v2"


def get_embedder(model_name: str = DEFAULT_MODEL) -> SentenceTransformer:
    """Возвращает загруженную модель (кэшируется в ~/.cache)."""
    return SentenceTransformer(model_name)


def compute_embeddings(
    texts: List[str],
    model: SentenceTransformer | None = None,
    batch_size: int = 32,
    show_progress: bool = True,
):
    """
    Считает эмбеддинги для списка текстов.
    Возвращает numpy array формы (n, dim).
    """
    if model is None:
        model = get_embedder()
    return model.encode(
        texts,
        batch_size=batch_size,
        show_progress_bar=show_progress,
        normalize_embeddings=True,
    )
