# Báo cáo Đối chứng Lexical Ranking Offline (PostgreSQL 18 / V68) — 27/11/2026

**Phạm vi:** `reports/evaluation/baselines/live-9docs-hybrid-v68-20261127/` (không gọi thêm provider trả phí)  
**Cấu hình:** `ag02-lex-bench` (`pgvector/pgvector:pg18`, Flyway `V1–V68`), 9 docs WHO/NIDDK (29 chunks thật từ `KnowledgeChunker`), `limit=5`, `candidateLimit=30`, RRF `1/(60 + rank)` đối chứng trên dense Top-5 đã lưu từ `live-9docs-20261127/retrieval/`.  
**Tài liệu tham chiếu:** PostgreSQL 18 `textsearch-controls.html` (`norm=0` bỏ qua độ dài; `1`: `1+log(len)`; `8`: chia số từ duy nhất; `32`: `rank/(rank+1)` chỉ co giãn hiển thị, không đổi thứ tự).

---

## 1. Bảng so sánh 6 phương án trên 10 dev queries (7 evaluable + 3 `EMPTY_EVIDENCE`)

| Phương án | Công thức PostgreSQL | `fused_doc_hit@5` (7 ca) | `VI-NO-DIACRITICS-01` (`VI-05#1` lex / fused) | `WHO:E01` (`VI-05#1` lex / fused) | Regression / Tradeoff |
|---|---|---:|---:|---:|---|
| **`V0_cd_norm0`** *(V68 hiện tại)* | `ts_rank_cd(..., q)` (`norm=0`) | **`7/7`** | `#8` / `None` | `#8` / `#3` | Baseline V68; giữ `7/7` doc hit nhưng `VI-05#1` chưa vào Top-5 |
| **`V1_cd_norm1`** | `ts_rank_cd(..., q, 1)` | `7/7` | `#8` / `None` | `#11` / `#5` | Tụt hạng `VI-05#1` trên `WHO:E01` (`#8→#11`) |
| **`V2_cd_norm8`** | `ts_rank_cd(..., q, 8)` | `7/7` | `#10` / `None` | `#10` / `#5` | Tụt hạng `VI-05#1` trên cả hai ca (`#10`) |
| **`V3_rank_norm1`** | `ts_rank(..., q, 1)` | **`6/7`** | `#6` / `None` | `#4` / `#3` | Cải thiện `VI-05#1` lên `#6` lex nhưng vẫn ngoài Top-5 sau RRF; **regression `AG02-DEV:EN-02`** (`7/7 → 6/7`) |
| **`V4_rank_norm8`** | `ts_rank(..., q, 8)` | `7/7` | `#8` / `None` | `#7` / `#4` | Không cải thiện `VI-05#1` trên ca không dấu |
| **`V5_cov_plus_rank1`** | `distinct_overlap/N + 0.1*ts_rank(..., 1)` | **`6/7`** | `#8` / `None` | `#8` / `#3` | Không cải thiện `VI-05#1`; **regression `AG02-DEV:EN-02`** (`7/7 → 6/7`) |

Chi tiết từng case: [lexical-ranking-ablation-offline.json](lexical-ranking-ablation-offline.json).

---

## 2. Kết luận & Quyết định

- **Giữ nguyên implementation hiện tại (`V68` + `KnowledgeRetrievalService`, `hybridEnabled=false` mặc định):** Không có phương án `ts_rank_cd` / `ts_rank` nào đưa được `VI-05#1` vào Top-5 sau RRF trên `AG02-DEV:VI-NO-DIACRITICS-01`, trong khi `V3` và `V5` gây regression trên `AG02-DEV:EN-02`.
- **Chưa đủ căn cứ để chạy live retest chỉ với thay đổi `ts_rank_cd`:** `VI-05#1` đã xuất hiện ổn định trong tập `candidateLimit=30` (hạng `#6–#8` lexical), nên bước tiếp theo có căn cứ là **đánh giá reranker thật (`AD-04`)** trên 30 candidates thay vì tiếp tục tinh chỉnh `ts_rank_cd`.
- *Giới hạn:* RRF replay dùng dense Top-5 đã lưu (không có dense rank #6–#30 offline); nhãn dev giữ `PROVISIONAL`, holdout chưa mở.
