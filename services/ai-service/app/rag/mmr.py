from __future__ import annotations

import math
import re
from collections import Counter

from app.rag.retriever import RetrievedChunkDto

_TOKEN_RE = re.compile(r"\w+", flags=re.UNICODE)


def _term_vector(text: str) -> Counter[str]:
    tokens = _TOKEN_RE.findall(text.lower())
    counts: Counter[str] = Counter(tokens)
    for i in range(len(tokens) - 1):
        counts[f"{tokens[i]}_{tokens[i + 1]}"] += 1
    return counts


def _cosine_text_similarity(a: Counter[str], b: Counter[str]) -> float:
    if not a or not b:
        return 0.0
    dot = sum(val * b[tok] for tok, val in a.items() if tok in b)
    if dot == 0:
        return 0.0
    norm_a = math.sqrt(sum(v * v for v in a.values()))
    norm_b = math.sqrt(sum(v * v for v in b.values()))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def normalize_relevance_scores(
    chunks: list[RetrievedChunkDto],
    override_scores: dict[str, float] | None = None,
) -> dict[str, float]:
    """Min-max normalize relevance scores to [0.0, 1.0] before MMR combination."""
    if not chunks:
        return {}
    raw: dict[str, float] = {}
    for idx, chunk in enumerate(chunks):
        if override_scores and chunk.chunkId in override_scores:
            raw[chunk.chunkId] = float(override_scores[chunk.chunkId])
        elif chunk.similarityScore != 0.0:
            raw[chunk.chunkId] = float(chunk.similarityScore)
        else:
            # Fallback to inverse rank when upstream did not provide distinct scores
            raw[chunk.chunkId] = 1.0 / (idx + 1)

    values = list(raw.values())
    min_val = min(values)
    max_val = max(values)
    span = max_val - min_val
    if span <= 1e-12:
        return {cid: 1.0 for cid in raw}
    return {cid: (val - min_val) / span for cid, val in raw.items()}


def apply_mmr(
    chunks: list[RetrievedChunkDto],
    top_k: int,
    lambda_param: float = 0.7,
    override_scores: dict[str, float] | None = None,
) -> list[RetrievedChunkDto]:
    """Select up to top_k chunks using Maximal Marginal Relevance.

    Balances normalized relevance against pairwise text redundancy.
    """
    if not chunks or top_k <= 0:
        return []
    if len(chunks) <= 1 or top_k == 1:
        norm = normalize_relevance_scores(chunks, override_scores)
        best = max(chunks, key=lambda c: norm.get(c.chunkId, 0.0))
        return [best]

    lam = min(max(lambda_param, 0.0), 1.0)
    norm_rel = normalize_relevance_scores(chunks, override_scores)
    vectors = {c.chunkId: _term_vector(f"{c.heading} {c.content}") for c in chunks}

    remaining = list(chunks)
    selected: list[RetrievedChunkDto] = []

    while remaining and len(selected) < top_k:
        best_chunk: RetrievedChunkDto | None = None
        best_mmr = -float("inf")

        for candidate in remaining:
            rel = norm_rel.get(candidate.chunkId, 0.0)
            if not selected:
                mmr_score = rel
            else:
                max_sim = max(
                    _cosine_text_similarity(vectors[candidate.chunkId], vectors[s.chunkId])
                    for s in selected
                )
                mmr_score = lam * rel - (1.0 - lam) * max_sim

            if mmr_score > best_mmr:
                best_mmr = mmr_score
                best_chunk = candidate

        if best_chunk is None:
            break
        selected.append(best_chunk)
        remaining.remove(best_chunk)

    return selected
