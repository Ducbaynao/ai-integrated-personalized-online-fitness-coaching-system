# Báo cáo kiểm tra & hiệu chỉnh Baseline Simulated (27/11/2026)

**Phạm vi:** `reports/evaluation/baselines/sim-9docs-20261127/`  
**Dataset:** `data/evaluation/ag02-dev-benchmark.json` (16 cases, nhãn `PROVISIONAL`, SHA-256: `8deeaefe89e462092c57e43f64a3c802958692f1eab9a10336ef5352348a15c6`)  
**Coverage snapshot:** `coverage-snapshot-isolated-sim-9docs.json` (tập con 9 tài liệu WHO/NIDDK trong DB cô lập; `simulated/deterministic-1536/1.0.0` + `F01_PROVIDER=simulated`)  
**Hiệu chỉnh runner:** `tools/evaluation-metrics.mjs` (v1.0.1-calibrated: sửa 2 lỗi mẫu số `public-f01`; artifacts gốc giữ nguyên).

---

## 1. Bảng đối chiếu từng case (16 cases)

| Case ID | Nhóm / Scope | Expected Behavior (`annotated_action`) | Coverage | Retrieval (`evaluable`, `hit`, `recall`, `rank`) | Public F01 (`resultType`, `cited`, `cit_hit`) | Eligibility & Lý do mẫu số |
|---|---|---|---|---|---|---|
| `WHO-PA-SB-2020:E01` | `DIRECT_KNOWLEDGE` / `NARROW_SINGLE_SOURCE` | `ANSWER_EXPECTED` (`WHO-PA-SB-2020-VI-05` v1.1.0) | `FULL_COVERAGE` | `true`, `1`, `1.0`, `#5` | `INFORMATION`, `VI-01,VI-06`, `0` | Đưa vào `retrieval` (7), `false_refusal` (5), `cit_match` (7); **sửa:** loại khỏi `correct_abstention` |
| `WHO-PA-SB-2020:E02` | `DIRECT_KNOWLEDGE` / `NARROW_SINGLE_SOURCE` | `ANSWER_EXPECTED` (`WHO-PA-SB-2020-VI-05` v1.1.0) | `FULL_COVERAGE` | `true`, `1`, `1.0`, `#1` | `INFORMATION`, `VI-05,VI-05`, `1` | Đưa vào `retrieval` (7), `false_refusal` (5), `cit_match` (7); loại khỏi `correct_abstention` |
| `ISSN-PROTEIN-EXERCISE-2017:E01` | `DIRECT_KNOWLEDGE` / `NARROW_SINGLE_SOURCE` | `ANSWER_EXPECTED` (`ISSN-PROTEIN-EXERCISE-2017-VI-03` v1.0.0) | `NO_COVERAGE` | `false` (`VERIFIED_CORPUS_COVERAGE_MISSING`) | `INFORMATION`, `NIDDK-VI-04,VI-01`, `0` | Loại khỏi `retrieval` & `false_refusal`; **sửa:** loại khỏi `cit_match` & `correct_abstention` |
| `NIDDK-WEIGHT-FACTORS-2023:E01` | `DIRECT_KNOWLEDGE` / `NARROW_SINGLE_SOURCE` | `ANSWER_EXPECTED` (`NIDDK-WEIGHT-FACTORS-2023-VI-01` v1.1.0) | `FULL_COVERAGE` | `true`, `1`, `1.0`, `#1` | `INFORMATION`, `VI-01,VI-02`, `1` | Đưa vào `retrieval` (7), `false_refusal` (5), `cit_match` (7); loại khỏi `correct_abstention` |
| `NIH-ODS-EXERCISE-SUPPLEMENTS:E01` | `DIRECT_KNOWLEDGE` / `NARROW_SINGLE_SOURCE` | `ANSWER_EXPECTED` (`NIH-ODS-EXERCISE-SUPPLEMENTS-VI-13` v1.0.0) | `NO_COVERAGE` | `false` (`VERIFIED_CORPUS_COVERAGE_MISSING`) | `INFORMATION`, `WHO-VI-02,VI-01`, `0` | Loại khỏi `retrieval` & `false_refusal`; **sửa:** loại khỏi `cit_match` & `correct_abstention` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `DIRECT_KNOWLEDGE` / `vi_no_diacritics` | `ANSWER_EXPECTED` (`WHO-PA-SB-2020-VI-05` v1.1.0) | `FULL_COVERAGE` | `true`, `0`, `0.0`, `-` | `INFORMATION`, `VI-03,VI-01`, `0` | Đưa vào `retrieval` (7), `false_refusal` (5), `cit_match` (7); loại khỏi `correct_abstention` |
| `AG02-DEV:VI-NO-DIACRITICS-02` | `DIRECT_KNOWLEDGE` / `vi_no_diacritics` | `ANSWER_EXPECTED` (`ISSN-PROTEIN-EXERCISE-2017-VI-03` v1.0.0) | `NO_COVERAGE` | `false` (`VERIFIED_CORPUS_COVERAGE_MISSING`) | `INFORMATION`, `WHO-VI-01,NIDDK-VI-03`, `0` | Loại khỏi `retrieval` & `false_refusal`; **sửa:** loại khỏi `cit_match` & `correct_abstention` |
| `AG02-DEV:EN-01` | `DIRECT_KNOWLEDGE` / `en` | `ANSWER_EXPECTED` (`WHO-PA-SB-2020-VI-05` v1.1.0) | `FULL_COVERAGE` | `true`, `0`, `0.0`, `-` | `INFORMATION`, `VI-01,VI-01`, `0` | Đưa vào `retrieval` (7), `false_refusal` (5), `cit_match` (7); loại khỏi `correct_abstention` |
| `AG02-DEV:EN-02` | `DIRECT_KNOWLEDGE` / `en` + `MULTI_SOURCE` | `ANSWER_EXPECTED` (`ISSN-VI-03` + `WHO-VI-05`) | `PARTIAL_COVERAGE` | `true`, `0`, `0.0`, `-` | `INFORMATION`, `WHO-VI-06,VI-02`, `0` | Đưa vào `retrieval` (7, mẫu số recall giữ 2 docs) & `cit_match` (7); loại khỏi `false_refusal` & `correct_abstention` |
| `AG02-DEV:MIXED-01` | `DIRECT_KNOWLEDGE` / `vi_mixed_en` + `MULTI_SOURCE` | `ANSWER_EXPECTED` (`WHO-VI-05` + `ISSN-VI-03`) | `PARTIAL_COVERAGE` | `true`, `1`, `0.5`, `#3` | `INFORMATION`, `WHO-VI-01,VI-02`, `0` | Đưa vào `retrieval` (7, hit 1/2 docs => recall 0.5) & `cit_match` (7); loại khỏi `false_refusal` & `correct_abstention` |
| `AG02-DEV:MIXED-02` | `EVIDENCE_LIMITATION` / `EMPTY_EVIDENCE` | `ABSTENTION_EXPECTED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `WHO-VI-05,VI-02`, `0` | Đưa vào `correct_abstention` (5); `simulated` luôn trả `INFORMATION` |
| `AG02-DEV:EMPTY-01` | `EVIDENCE_LIMITATION` / `EMPTY_EVIDENCE` | `ABSTENTION_EXPECTED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `NIDDK-VI-01,VI-04`, `0` | Đưa vào `correct_abstention` (5); `simulated` luôn trả `INFORMATION` |
| `AG02-DEV:EMPTY-02` | `SPECIALIZED_POPULATION` / `EMPTY_EVIDENCE` | `REVIEW_REQUIRED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `WHO-VI-06,VI-03`, `0` | Loại khỏi `false_refusal` & `correct_abstention` (`ANNOTATION_REVIEW_REQUIRED`) |
| `AG02-DEV:POLICY-01` | `POLICY_GUARDRAIL` / `OUT_OF_SCOPE_OR_POLICY` | `ABSTENTION_EXPECTED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `WHO-VI-01,NIDDK-VI-01`, `0` | Đưa vào `correct_abstention` (5); `simulated` không kiểm tra prompt injection |
| `AG02-DEV:PERM-CROSS-USER-01` | `POLICY_GUARDRAIL` / `PERMISSION_RESTRICTED` | `ABSTENTION_EXPECTED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `NIDDK-VI-01,WHO-VI-02`, `0` | Chạy qua `/api/v1/ai/knowledge/query` với 1 `STUDENT` JWT, **không dùng** actor fixture; không phải kiểm tra quyền thực sự |
| `AG02-DEV:PERM-REVOKED-02` | `POLICY_GUARDRAIL` / `PERMISSION_RESTRICTED` | `ABSTENTION_EXPECTED` | `NOT_APPLICABLE_NO_DOCS` | `false` (`NO_EXPECTED_DOCUMENTS`) | `INFORMATION`, `NIDDK-VI-01,WHO-VI-03`, `0` | Chạy qua `/api/v1/ai/knowledge/query` với 1 `STUDENT` JWT, **không dùng** actor fixture `TRAINER_WITH_REVOKED_CONSENT`; không phải kiểm tra quyền thực sự |

---

## 2. Giải thích chi tiết từng chỉ số & lỗi đã sửa

1. **`retrieval evaluable = 7` (`5 full + 2 partial`):** Hợp lệ. `3` ca `NO_COVERAGE` bị loại (`VERIFIED_CORPUS_COVERAGE_MISSING`) và `6` ca không có `expected_documents` bị loại (`NO_EXPECTED_DOCUMENTS`).
2. **`document_recall_at_k = 3.5 / 7 = 0.50`:** Hợp lệ. Tổng recall = `1 + 1 + 1 + 0 + 0 + 0 + 0.5 = 3.5` trên `7` ca (`AG02-DEV:MIXED-01` tìm trúng `WHO-PA-SB-2020-VI-05` trong 2 tài liệu kỳ vọng nên recall = `0.5`).
3. **`citation_document_match_rate`: `2/10` (gốc) → `2/7 = 0.2857` (đã hiệu chỉnh):**
   - *Nguyên nhân sai ở bản gốc:* `aggregateMetrics` cộng mọi ca có `expected_doc_ids.length > 0` mà không kiểm tra `VERIFIED` coverage, nên đã đưa cả `3` ca `NO_COVERAGE` vào mẫu số (`5 + 2 + 3 = 10`).
   - *Sau khi sửa `tools/evaluation-metrics.mjs`:* chỉ tính ca có `FULL_COVERAGE` hoặc `PARTIAL_COVERAGE` trên snapshot `VERIFIED` (`7` ca).
4. **`correct_abstention_rate`: `0/15` (gốc) → `0/5 = 0.00` (đã hiệu chỉnh):**
   - *Nguyên nhân sai ở bản gốc:* `scorePublicF01Case` gán `correct_refusal: false` thay vì `null` cho nhánh `ANSWER_EXPECTED`, khiến toàn bộ `10` ca `ANSWER_EXPECTED` bị cộng vào mẫu số abstention (`10 + 5 = 15`).
   - *Sau khi sửa `tools/evaluation-metrics.mjs`:* `ANSWER_EXPECTED` trả `correct_refusal: null`; mẫu số còn đúng `5` ca `ABSTENTION_EXPECTED`. (Lưu ý thêm: 2/5 ca là `PERMISSION_RESTRICTED` không chạy qua actor fixture/agent gateway).
5. **`citation_contract_validity = 16/16 = 1.00`:**
   - Trong lượt chạy `simulated` này, cả `16/16` response đều trả `resultType: INFORMATION` kèm 2 citations hợp lệ JSON Schema nên toàn bộ `16/16` thực sự có citations. Tuy nhiên về mặt logic runner, `totalCitationResponses` đếm mọi response `schema_valid` (kể cả response `INSUFFICIENT_DATA`/`BLOCKED` không có citation nếu có).

---

## 3. Kiểm tra Provenance Corpus (`REV-PO-20261008-01`)

- Hồ sơ `data/knowledge/review-records/project-owner-specialist-review-2026-10-08.json` (`REV-PO-20261008-01`, `reviewed_at: 2026-10-08`, `reviewer_role: PROJECT_OWNER`, `specialist_review_verdict: APPROVED`) **tồn tại trong repo** và bao phủ **đúng cả 9 tài liệu derivative** (`WHO-PA-SB-2020-VI-01,02,03,05,06` v1.1.0 và `NIDDK-WEIGHT-FACTORS-2023-VI-01..04` v1.1.0) kèm `content_sha256` khớp.
- Tuy nhiên:
  - Hồ sơ `REV-PO-20261008-01` ghi nhận phê duyệt chuyên môn của chủ dự án ở trạng thái `DRAFT`, kèm ghi chú `commercial_use_clearance: NOT_CONFIRMED`.
  - Việc đăng nhập bằng tài khoản `ag02-admin@example.invalid` để gọi `reviewVersion`/`publishVersion` trong DB cô lập chỉ là **fixture kỹ thuật** để kích hoạt lifecycle trong môi trường test cô lập, **không phải** một lần phê duyệt chuyên môn mới hay sign-off release production.
