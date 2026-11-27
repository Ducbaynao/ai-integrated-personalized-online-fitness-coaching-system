"""Run AG-06 / AG-09 retrieval ablation through the real FastAPI RetrievalPipeline."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_SERVICE_DIR = ROOT / "services" / "ai-service"
sys.path.insert(0, str(AI_SERVICE_DIR))

from app.core.config import settings  # noqa: E402
from app.rag.mmr import apply_mmr  # noqa: E402
from app.rag.context_budget import pack_context_chunks  # noqa: E402
from app.rag.pipeline import RetrievalPipeline  # noqa: E402
from app.rag.reranker import DisabledReranker, HttpRerankerAdapter, RerankResult  # noqa: E402
from app.rag.retriever import KnowledgeRetriever  # noqa: E402

APPROVED_SOURCES = [
    "WHO-PA-SB-2020",
    "ISSN-PROTEIN-EXERCISE-2017",
    "NIH-ODS-EXERCISE-SUPPLEMENTS",
    "NIDDK-WEIGHT-FACTORS-2023",
]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_meta() -> dict[str, object]:
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    return {"git_sha": sha, "working_tree_clean": not dirty}


def active_doc_set(snapshot_path: Path) -> set[tuple[str, str]]:
    snap = json.loads(snapshot_path.read_text(encoding="utf-8"))
    out: set[tuple[str, str]] = set()
    for d in snap.get("active_documents", []):
        if d.get("status") == "ACTIVE" and d.get("index_ready") is True:
            out.add((str(d["document_id"]), str(d["version"])))
    return out


def case_coverage(case: dict, active: set[tuple[str, str]]) -> str:
    expected = case.get("expected_documents") or []
    if not expected:
        return "NOT_APPLICABLE_NO_DOCS"
    matched = sum(
        1 for d in expected if (str(d["document_id"]), str(d["target_version"])) in active
    )
    if matched == len(expected):
        return "FULL_COVERAGE"
    if matched > 0:
        return "PARTIAL_COVERAGE"
    return "NO_COVERAGE"


async def evaluate_config(
    config_name: str,
    manifest: dict,
    active: set[tuple[str, str]],
    backend_url: str,
    internal_key: str,
    top_k: int,
) -> dict:
    retriever = KnowledgeRetriever(backend_base_url=backend_url, internal_key=internal_key)
    pipeline = RetrievalPipeline(retriever=retriever)

    evaluable = 0
    hits = 0
    sum_recall = 0.0
    sum_mrr = 0.0
    per_case: list[dict] = []

    for case in manifest["cases"]:
        cov = case_coverage(case, active)
        res = await pipeline.run(
            query=case["question_vi"],
            allowed_source_ids=APPROVED_SOURCES,
            limit=top_k,
        )
        expected = [
            (str(d["document_id"]), str(d["target_version"]))
            for d in (case.get("expected_documents") or [])
        ]
        retrieved_pairs = [(c.documentCode, c.version) for c in res.chunks]

        is_evaluable = cov in {"FULL_COVERAGE", "PARTIAL_COVERAGE"}
        hit = None
        recall = None
        first_rank = None
        if is_evaluable:
            evaluable += 1
            matched_docs = {pair for pair in expected if pair in retrieved_pairs}
            hit = 1 if matched_docs else 0
            recall = len(matched_docs) / len(expected)
            for idx, pair in enumerate(retrieved_pairs, start=1):
                if pair in expected:
                    first_rank = idx
                    break
            hits += hit
            sum_recall += recall
            if first_rank is not None:
                sum_mrr += 1.0 / first_rank

        per_case.append(
            {
                "case_key": case["case_key"],
                "coverage_status": cov,
                "evaluable": is_evaluable,
                "hit": hit,
                "recall": recall,
                "first_rank": first_rank,
                "candidate_count": res.candidate_count,
                "returned_count": len(res.chunks),
                "mmr_applied": res.mmr_applied,
                "rerank_applied": res.rerank_applied,
                "stage_ranks": res.stage_ranks,
            }
        )

    return {
        "config_name": config_name,
        "status": "COMPLETED",
        "evaluable_cases": evaluable,
        "document_hit_at_k": round(hits / evaluable, 4) if evaluable else None,
        "document_recall_at_k": round(sum_recall / evaluable, 4) if evaluable else None,
        "mean_reciprocal_rank_at_k": round(sum_mrr / evaluable, 4) if evaluable else None,
        "cases": per_case,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config-name",
        required=True,
        choices=["dense", "hybrid", "rrf_current", "rerank", "rerank_mmr"],
    )
    parser.add_argument("--backend-url", default="http://127.0.0.1:18080")
    parser.add_argument("--internal-key", required=True)
    parser.add_argument("--manifest", default="data/evaluation/ag02-dev-benchmark.json")
    parser.add_argument("--coverage-snapshot", required=True)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--output-file", required=True)
    args = parser.parse_args()

    manifest_path = (ROOT / args.manifest).resolve()
    snap_path = (ROOT / args.coverage_snapshot).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    active = active_doc_set(snap_path)

    # Ensure flags match the requested ablation arm without fabricating mock scores
    if args.config_name in {"dense", "hybrid", "rrf_current"}:
        settings.rag_rerank_enabled = False
        settings.rag_mmr_enabled = False
    elif args.config_name == "rerank":
        if not settings.rag_rerank_url or not settings.rag_rerank_api_key.get_secret_value().strip():
            raise RuntimeError("RAG_RERANK_URL and RAG_RERANK_API_KEY must be configured for --config-name=rerank")
        settings.rag_rerank_enabled = True
        settings.rag_mmr_enabled = False
    elif args.config_name == "rerank_mmr":
        if not settings.rag_rerank_url or not settings.rag_rerank_api_key.get_secret_value().strip():
            raise RuntimeError("RAG_RERANK_URL and RAG_RERANK_API_KEY must be configured for --config-name=rerank_mmr")
        settings.rag_rerank_enabled = True
        settings.rag_mmr_enabled = True

    result = asyncio.run(
        evaluate_config(
            config_name=args.config_name,
            manifest=manifest,
            active=active,
            backend_url=args.backend_url,
            internal_key=args.internal_key,
            top_k=args.top_k,
        )
    )
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git": git_meta(),
        "dataset_path": args.manifest,
        "dataset_sha256": sha256_file(manifest_path),
        "coverage_snapshot_path": args.coverage_snapshot,
        "coverage_snapshot_sha256": sha256_file(snap_path),
        "top_k": args.top_k,
        "rag_context_max_chars": settings.rag_context_max_chars,
        "result": result,
    }
    out_path = Path(args.output_file)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"{args.config_name}: evaluable={result['evaluable_cases']} "
        f"hit@{args.top_k}={result['document_hit_at_k']} "
        f"recall@{args.top_k}={result['document_recall_at_k']} "
        f"mrr@{args.top_k}={result['mean_reciprocal_rank_at_k']}"
    )


if __name__ == "__main__":
    main()
