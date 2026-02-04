# -*- coding: utf-8 -*-
"""
Разбивка текста на чанки для индексации.
Учитывает структуру Markdown (заголовки, параграфы) и ограничение по длине.
"""
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


@dataclass
class Chunk:
    """Один чанк текста с метаданными для индекса."""
    doc_id: str
    chunk_index: int
    text: str
    source_path: str
    section: str = ""


def chunk_by_paragraphs(
    text: str,
    *,
    max_chars: int = 600,
    overlap_chars: int = 80,
    min_chunk_chars: int = 100,
) -> list[str]:
    """
    Разбивает текст на чанки по параграфам (двойной перенос или заголовки).
    Не разрывает параграф посередине; при переполнении режет по предложениям.
    """
    # Нормализуем переносы и разбиваем на "блоки" (параграфы/заголовки)
    text = text.strip()
    blocks = re.split(r'\n\s*\n', text)
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0

    for block in blocks:
        block = block.strip()
        if not block:
            continue
        block_len = len(block) + 2  # +2 за \n\n

        if current_len + block_len <= max_chars:
            current.append(block)
            current_len += block_len
        else:
            if current:
                chunk_text = "\n\n".join(current)
                if len(chunk_text) >= min_chunk_chars or not chunks:
                    chunks.append(chunk_text)
            # Начинаем новый чанк; overlap — взять конец предыдущего
            if overlap_chars > 0 and current:
                overlap_text = "\n\n".join(current)
                if len(overlap_text) > overlap_chars:
                    overlap_text = overlap_text[-overlap_chars:].strip()
                    # Начало с границы слова
                    first_space = overlap_text.find(' ')
                    if first_space > 0:
                        overlap_text = overlap_text[first_space:].strip()
                current = [overlap_text] if overlap_text else []
                current_len = len(overlap_text)
            else:
                current = []
                current_len = 0
            # Текущий блок может не влезть — режем по предложениям
            if block_len > max_chars:
                for part in _split_by_sentences(block, max_chars, overlap_chars):
                    if len(part) >= min_chunk_chars or not current:
                        chunks.append(part)
                current = []
                current_len = 0
            else:
                current = [block]
                current_len = block_len

    if current:
        chunk_text = "\n\n".join(current)
        if len(chunk_text) >= min_chunk_chars or not chunks:
            chunks.append(chunk_text)

    return chunks


def _split_by_sentences(text: str, max_chars: int, overlap_chars: int) -> list[str]:
    """Режет длинный параграф по предложениям."""
    sentences = re.split(r'(?<=[.!?])\s+', text)
    result: list[str] = []
    buf: list[str] = []
    buf_len = 0
    for s in sentences:
        s_len = len(s) + 1
        if buf_len + s_len <= max_chars:
            buf.append(s)
            buf_len += s_len
        else:
            if buf:
                result.append(" ".join(buf))
            buf = [s]
            buf_len = s_len
    if buf:
        result.append(" ".join(buf))
    return result


def chunk_text(
    text: str,
    doc_id: str,
    source_path: str,
    section: str = "",
    *,
    max_chars: int = 600,
    overlap_chars: int = 80,
    min_chunk_chars: int = 100,
) -> Iterator[Chunk]:
    """
    Разбивает уже извлечённый текст на чанки (для PDF и др.).
    """
    texts = chunk_by_paragraphs(
        text,
        max_chars=max_chars,
        overlap_chars=overlap_chars,
        min_chunk_chars=min_chunk_chars,
    )
    for i, t in enumerate(texts):
        yield Chunk(
            doc_id=doc_id,
            chunk_index=i,
            text=t,
            source_path=source_path,
            section=section,
        )


def chunk_document(path: Path, doc_id: str) -> Iterator[Chunk]:
    """
    Читает текстовый документ (например .md) и выдаёт чанки с метаданными.
    doc_id — короткий идентификатор (например, имя файла без расширения).
    """
    text = path.read_text(encoding="utf-8")
    section = ""
    # Первая строка-заголовок как section
    first_line = text.split("\n")[0].strip()
    if first_line.startswith("#"):
        section = first_line.lstrip("#").strip()
    yield from chunk_text(text, doc_id, str(path), section=section)
