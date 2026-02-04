# -*- coding: utf-8 -*-
"""
Поиск по индексу по подстроке (без эмбеддингов). Показывает, есть ли текст в чанках вообще.

Использование:
  python find_in_index.py Глазунов
  python find_in_index.py "Анатолий Алексеевич"
"""
from pathlib import Path
import sys

OUT_DIR = Path(__file__).resolve().parent / "output"
JSON_INDEX = OUT_DIR / "index.json"


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python find_in_index.py <substring>")
        sys.exit(1)
    substring = sys.argv[1].strip()
    if not substring:
        print("Usage: python find_in_index.py <substring>")
        sys.exit(1)

    if not JSON_INDEX.exists():
        print(f"Index not found: {JSON_INDEX}. Run build_index.py first.")
        sys.exit(1)

    from index_store import load_json_index
    chunks, _, _ = load_json_index(JSON_INDEX)
    sub_lower = substring.lower()
    found = [(i, c) for i, c in enumerate(chunks) if sub_lower in (c.get("text") or "").lower()]

    print(f"Query (substring): «{substring}»")
    print(f"Total chunks in index: {len(chunks)}")
    print(f"Chunks containing this substring: {len(found)}\n")
    if not found:
        print("Текст не найден в индексе. Возможные причины:")
        print("  - фрагмент не извлёкся из PDF (таблица, изображение, нестандартная вёрстка);")
        print("  - опечатка в подстроке.")
        return

    for idx, ch in found:
        text = (ch.get("text") or "")[:600]
        if len(ch.get("text") or "") > 600:
            text += "..."
        print(f"--- chunk index {idx} (doc_id={ch.get('doc_id', '')}) ---")
        print(text)
        print()


if __name__ == "__main__":
    main()
