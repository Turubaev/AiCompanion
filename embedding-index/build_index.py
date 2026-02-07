# -*- coding: utf-8 -*-
r"""
Пайплайн: разбивка текста на чанки → генерация эмбеддингов → сохранение индекса.

Использование:
  cd embedding-index
  .venv\Scripts\activate
  pip install -r requirements.txt
  python build_index.py

По умолчанию читает Test_sample.pdf из корня проекта (поддерживаются также .md).
Сохраняет индекс в embedding-index/output/index.json и index.faiss.
"""
from pathlib import Path
import sys

# Корень репозитория — на уровень выше embedding-index
REPO_ROOT = Path(__file__).resolve().parent.parent
# Индексируемые документы: оба PDF из корня проекта
DEFAULT_DOCS = [
    REPO_ROOT / "Test_sample.pdf",
    REPO_ROOT / "Test_sample_2.pdf",
]
OUT_DIR = Path(__file__).resolve().parent / "output"
JSON_INDEX = OUT_DIR / "index.json"
FAISS_INDEX = OUT_DIR / "index.faiss"


def chunk_to_dict(c) -> dict:
    from chunker import Chunk
    return {
        "doc_id": c.doc_id,
        "chunk_index": c.chunk_index,
        "text": c.text,
        "source_path": c.source_path,
        "section": c.section,
    }


def main() -> None:
    from chunker import chunk_document, chunk_text, Chunk
    from embedder import get_embedder, compute_embeddings
    from index_store import save_json_index, save_faiss_index

    doc_paths = [p for p in DEFAULT_DOCS if p.exists()]
    if not doc_paths:
        print("No input documents found. Expected:", [str(p) for p in DEFAULT_DOCS], file=sys.stderr)
        sys.exit(1)

    # Меньшие чанки (380 символов) — лучше вытаскивать отдельные персоны/факты из диссертаций и PDF
    CHUNK_MAX_CHARS = 380
    CHUNK_OVERLAP = 60
    CHUNK_MIN_CHARS = 60

    chunks: list[Chunk] = []
    for path in doc_paths:
        doc_id = path.stem
        if path.suffix.lower() == ".pdf":
            from pdf_loader import extract_text
            text = extract_text(path)
            for c in chunk_text(
                text, doc_id, str(path), section="",
                max_chars=CHUNK_MAX_CHARS,
                overlap_chars=CHUNK_OVERLAP,
                min_chunk_chars=CHUNK_MIN_CHARS,
            ):
                chunks.append(c)
        else:
            for c in chunk_document(
                path, doc_id,
                max_chars=CHUNK_MAX_CHARS,
                overlap_chars=CHUNK_OVERLAP,
                min_chunk_chars=CHUNK_MIN_CHARS,
            ):
                chunks.append(c)

    texts = [c.text for c in chunks]
    print(f"Chunked {len(doc_paths)} docs into {len(chunks)} chunks.")

    print("Loading embedding model...")
    model = get_embedder()
    print("Computing embeddings...")
    embeddings = compute_embeddings(texts, model=model)

    chunks_dict = [chunk_to_dict(c) for c in chunks]
    meta = {
        "source_docs": [str(p) for p in doc_paths],
        "model": "paraphrase-multilingual-mpnet-base-v2",
        "dim": int(embeddings.shape[1]),
    }

    save_json_index(chunks_dict, embeddings, JSON_INDEX, meta=meta)
    print(f"Saved JSON index: {JSON_INDEX}")

    save_faiss_index(embeddings, FAISS_INDEX)
    print(f"Saved FAISS index: {FAISS_INDEX}")

    print("Done. Local index is ready.")


if __name__ == "__main__":
    main()
