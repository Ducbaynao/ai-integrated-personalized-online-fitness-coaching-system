from __future__ import annotations

from app.rag.retriever import RetrievedChunkDto


def pack_context_chunks(
    chunks: list[RetrievedChunkDto],
    max_chars: int = 8000,
) -> list[RetrievedChunkDto]:
    """Pack ranked chunks within a bounded character/token budget without splitting chunks."""
    if not chunks or max_chars <= 0:
        return []

    packed: list[RetrievedChunkDto] = []
    used_chars = 0

    for chunk in chunks:
        chunk_cost = len(chunk.heading) + len(chunk.content) + len(chunk.documentCode) + 32
        if not packed or used_chars + chunk_cost <= max_chars:
            packed.append(chunk)
            used_chars += chunk_cost
        else:
            break

    return packed
