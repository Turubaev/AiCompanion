# -*- coding: utf-8 -*-
"""
Поиск по индексу по текстовому запросу (гибридный: слова запроса + FAISS, как в RAG-сервере).

Использование:
  python search.py "инвестиции и дивиденды"
  python search.py "кто такой Глазунов Анатолий" 10
"""
import sys


def main() -> None:
    from pathlib import Path
    from rag_server import search

    if len(sys.argv) < 2:
        print("Usage: python search.py <query> [top_k]")
        sys.exit(1)
    args = sys.argv[1:]
    top_k = 10
    if len(args) >= 2 and args[-1].isdigit():
        top_k = int(args.pop())
    query = " ".join(args)

    out_dir = Path(__file__).resolve().parent / "output"
    if not (out_dir / "index.json").exists() or not (out_dir / "index.faiss").exists():
        print("Index not found. Run build_index.py first.")
        sys.exit(1)

    results = search(query, top_k=top_k)
    print(f"Query: {query}\n")
    for i, r in enumerate(results, 1):
        print(f"--- {i} (score: {r['score']:.4f}) [{r['doc_id']}] {r.get('section', '')} ---")
        print(r["text"][:400] + ("..." if len(r["text"]) > 400 else ""))
        print()


if __name__ == "__main__":
    main()
