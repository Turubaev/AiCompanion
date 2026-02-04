# -*- coding: utf-8 -*-
"""
Извлечение текста из PDF для индексации.
"""
from pathlib import Path


def extract_text(path: Path) -> str:
    """
    Извлекает весь текст из PDF постранично.
    Возвращает одну строку с переносами между страницами.
    """
    import fitz  # PyMuPDF
    doc = fitz.open(path)
    parts = []
    for page in doc:
        parts.append(page.get_text())
    doc.close()
    return "\n\n".join(p.strip() for p in parts if p.strip())
