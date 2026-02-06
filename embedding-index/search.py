# -*- coding: utf-8 -*-
"""
Поиск по индексу по текстовому запросу (гибридный: слова запроса + FAISS, как в RAG-сервере).

Использование:
  python search.py "инвестиции и дивиденды"
  python search.py "кто такой Глазунов Анатолий" 10
  set RAG_MIN_SCORE=0.8        (CMD) — только чанки с score >= 0.8
  $env:RAG_MIN_SCORE="0.8"     (PowerShell)
"""
import os
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

    raw = os.environ.get("RAG_MIN_SCORE", "").strip()
    try:
        min_score = float(raw) if raw else 0.0
    except (TypeError, ValueError):
        min_score = 0.0
    use_reranker = os.environ.get("RAG_USE_RERANKER", "").strip().lower() in ("1", "true", "yes")
    if min_score > 0:
        print(f"min_score (filter): {min_score}\n")
    if use_reranker:
        print("use_reranker: true\n")

    results = search(query, top_k=top_k, min_score=min_score, use_reranker=use_reranker)
    print(f"Query: {query}\n")
    for i, r in enumerate(results, 1):
        print(f"--- {i} (score: {r['score']:.4f}) [{r['doc_id']}] {r.get('section', '')} ---")
        print(r["text"][:400] + ("..." if len(r["text"]) > 400 else ""))
        print()


if __name__ == "__main__":
    main()
