# F01 Knowledge Retrieval Development Baseline — Supplementary Full-Coverage Analysis

**Run ID:** `run-20261019234346-dcc3c0`  
**Execution Timestamp:** 2026-10-19T23:43:46.639Z (UTC) / 2026-10-20T06:43:46 (GMT+07)  
**Corpus Scope:** Active 9-Document Expanded Testbed (`WHO-PA-SB-2020`: 5 docs; `NIDDK-WEIGHT-FACTORS-2023`: 4 docs)  
**Candidate Cases Evaluated:** 42 cases (24 WHO + 18 NIDDK)  
**Holdout Status:** `NO_INDEPENDENT_HOLDOUT_YET` (Development baseline on candidate dataset v1.0.0-candidate)  

---

## 1. Executive Summary

This supplementary report documents the offline metric breakdown of development baseline run `run-20261019234346-dcc3c0`.
The primary evaluation baseline adheres strictly to A-49 specifications, which evaluate all cases possessing active evidence in the verified corpus snapshot (26 evaluable cases: 21 Full Coverage + 5 Partial Coverage).

In addition to the primary baseline metrics (denominator = 26), this report provides the isolated metric performance of the **Full Coverage Only** subset (denominator = 21) computed directly from `results.jsonl`, alongside an audit of the 5 Partial Coverage cases and 16 Missing Coverage cases.

---

## 2. Metric Performance Comparison

| Metric | Primary Development Baseline (A-49 Standard)<br>*(Full + Partial Coverage, N=26)* | Full Coverage Only<br>*(Complete Target Document Coverage, N=21)* | Partial Coverage Only<br>*(Incomplete Target Document Coverage, N=5)* |
|---|:---:|:---:|:---:|
| **Status** | **`CALCULATED`** | **`CALCULATED`** | **`CALCULATED`** |
| **Denominator** | **26** | **21** | **5** |
| **Document Hit@5** | **0.8846** (23 / 26) | **0.9524** (20 / 21) | **0.6000** (3 / 5) |
| **Document Recall@5** | **0.8077** (21.0000 / 26) | **0.9286** (19.5000 / 21) | **0.3000** (1.5000 / 5) |
| **MRR@5** | **0.6744** (17.5333 / 26) | **0.7857** (16.5000 / 21) | **0.2067** (1.0333 / 5) |
| **Infrastructure Errors** | **0** | **0** | **0** |
| **Execution Failures** | **0** | **0** | **0** |
| **Schema Violations** | **0** | **0** | **0** |

---

## 3. Analysis of Partial Coverage Scoring (A-49 Rule Verification)

In A-49 evaluation semantics (`tools/evaluation-metrics.mjs` lines 356–453):
1. **Evaluable Status:** Cases with `PARTIAL_COVERAGE` are marked `evaluable: true` because at least one expected document is actively indexed in the verified testbed corpus.
2. **Recall Denominator Integrity:** The recall formula is strictly:
   $$\text{Recall} = \frac{|\text{Exact Matched Expected Doc IDs}|}{|\text{All Expected Doc IDs}|}$$
   The denominator is the **total number of expected documents** for that case, **not** just the active ones. Missing documents are not dropped from the denominator; thus, partial coverage cases naturally receive penalized recall scores rather than inflated metrics.

### Case-by-Case Breakdown of the 5 Partial Coverage Cases:

| Case Key | Question (VI) | Expected Documents | Active in Testbed | Unindexed / Missing | Retrieved at Rank $\le$ 5 | Hit@5 | Recall@5 | First Rank |
|---|---|---|---|---|---|:---:|:---:|:---:|
| `WHO-PA-SB-2020:E12` | Thời gian sedentary có trần số giờ ngồi cụ thể? | `VI-02`, `VI-11` | `VI-02` | `VI-11` | `VI-02` (Rank 3) | 1 | 0.5000 (1/2) | 3 |
| `WHO-PA-SB-2020:E16` | Bằng chứng sedentary cho người khuyết tật? | `VI-01`, `VI-09` | `VI-01` | `VI-09` | `VI-01` (Rank 5) | 1 | 0.5000 (1/2) | 5 |
| `WHO-PA-SB-2020:E19` | Trẻ em dưới 5 tuổi áp dụng khuyến nghị 18–64? | `VI-05`, `VI-16` | `VI-05` | `VI-16` | *(None)* | 0 | 0.0000 (0/2) | null |
| `WHO-PA-SB-2020:E23` | Phụ nữ mang thai áp dụng khuyến nghị 18–64? | `VI-05`, `VI-16` | `VI-05` | `VI-16` | `VI-05` (Rank 2) | 1 | 0.5000 (1/2) | 2 |
| `WHO-PA-SB-2020:E24` | Bệnh nhân tăng huyết áp có tài liệu riêng? | `VI-01`, `VI-12` | `VI-01` | `VI-12` | *(None)* | 0 | 0.0000 (0/2) | null |

**Partial Coverage Summary:**
- Hits: 3 / 5 (Hit@5 = 0.6000)
- Sum Recall: $0.5 + 0.5 + 0.0 + 0.5 + 0.0 = 1.5000$ (Recall@5 = 0.3000)
- Sum Reciprocal Rank: $1/3 + 1/5 + 0 + 1/2 + 0 = 0.3333 + 0.2000 + 0.5000 = 1.0333$ (MRR@5 = 0.2067)

---

## 4. Analysis of Full Coverage Subset (21 Cases)

For the 21 cases where 100% of expected documents were active in the testbed corpus:
- **Hits:** 20 / 21 (Hit@5 = **0.9524**)
- **Sum Recall:** 19.5000 / 21 (Recall@5 = **0.9286**)
- **MRR@5:** 16.5000 / 21 (MRR@5 = **0.7857**)
- **Single Missed Case:**
  - `WHO-PA-SB-2020:E15`: *"Trên 300 phút vừa bị WHO cấm?"*
  - Expected: `WHO-PA-SB-2020-VI-05` (Khuyến nghị vận động ở tuổi 18–64, v1.1.0)
  - Chunks retrieved: `WHO-PA-SB-2020-VI-01` (R1), `WHO-PA-SB-2020-VI-06` (R2), `WHO-PA-SB-2020-VI-01` (R3), `WHO-PA-SB-2020-VI-03` (R4), `WHO-PA-SB-2020-VI-06` (R5).
  - Target document `VI-05` was not in top 5, resulting in Hit=0. This represents an authentic semantic ranking challenge to be analyzed during prompt/reranking refinement.

---

## 5. Analysis of Excluded Cases (16 Cases with No Coverage)

16 candidate cases were formally excluded from quality metrics with exclusion reason `VERIFIED_CORPUS_COVERAGE_MISSING`:
- **WHO Cases (8 cases):** `E04`, `E08`, `E10`, `E13`, `E14`, `E15`, `E18`, `E22` (Wait: E15 is evaluable in Full Coverage, the 8 no-coverage WHO cases are: `E04`, `E08`, `E10`, `E13`, `E14`, `E17`, `E18`, `E22`). Expected documents include `VI-04`, `VI-07`, `VI-08`, `VI-10`, `VI-13`, `VI-14`, `VI-15`, `VI-17`, `VI-18` which are not active in the 9-document testbed.
- **NIDDK Cases (8 cases):** `E02`, `E04`, `E08`, `E11`, `E13`, `E14`, `E16`, `E18`. Expected documents include `NIDDK-WEIGHT-FACTORS-2023-VI-05` and `VI-06`, which are excluded from active publication by governance rule.

---

## 6. Metadata Limitation Disclosure

In `run-metadata.json`, the field `model_space` is recorded as:
```json
"model_space": {
  "model_space": "UNKNOWN",
  "source": "UNKNOWN"
}
```
**Traceability & Disclosure:**
- The internal endpoint `POST /internal/v1/knowledge/retrieval` does not emit an `X-Embedding-Model-Space` header or `modelSpace` response field.
- `--model-space` was not explicitly passed in CLI runner arguments during live execution.
- **Integrity Rule:** In accordance with audit standards, `run-metadata.json` is preserved without retroactive edits.
- **Runtime Ground Truth:** Direct PostgreSQL database auditing of `fitness.knowledge_embeddings` confirms that all 29 active chunks in the testbed belong to embedding version `e1000000-0000-0000-0000-000000000002` (`openai/text-embedding-3-small/1536`).

---

## 7. Development Baseline Classification

- **Candidate Dataset:** F01 General Fitness Knowledge Candidate Dataset v1.0.0-candidate (hash: `0bdb6c6ebb24b4fc50c2104dc2315309cb7eb667e40e09639b46f40bec84b970`).
- **Holdout Status:** Development baseline without independent holdout (`NO_INDEPENDENT_HOLDOUT_YET`).
- **Release Limit:** This development baseline establishes the retrieval benchmark for milestone A-49. Production release gates will require specialist rubric review (A-52) and independent holdout evaluation before promotion.
