# -*- coding: utf-8 -*-
"""
Поиск по индексу по текстовому запросу.

Использование:
  python search.py "инвестиции и дивиденды"
  python search.py "Kotlin Compose архитектура"
"""
from pathlib import Path
import sys

OUT_DIR = Path(__file__).resolve().parent / "output"
JSON_INDEX = OUT_DIR / "index.json"
FAISS_INDEX = OUT_DIR / "index.faiss"


def main() -> None:
    from embedder import get_embedder
    from index_store import load_json_index, search_faiss

    if len(sys.argv) < 2:
        print("Usage: python search.py <query>")
        sys.exit(1)
    query = " ".join(sys.argv[1:])

    if not JSON_INDEX.exists() or not FAISS_INDEX.exists():
        print("Index not found. Run build_index.py first.")
        sys.exit(1)

    model = get_embedder()
    query_emb = model.encode([query], normalize_embeddings=True)

    results = search_faiss(query_emb, FAISS_INDEX, JSON_INDEX, top_k=5)
    print(f"Query: {query}\n")
    for i, r in enumerate(results, 1):
        print(f"--- {i} (score: {r['score']:.4f}) [{r['doc_id']}] {r.get('section', '')} ---")
        print(r["text"][:400] + ("..." if len(r["text"]) > 400 else ""))
        print()


if __name__ == "__main__":
    main()
