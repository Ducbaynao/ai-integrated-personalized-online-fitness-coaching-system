# P7-016 Vietnamese Copy & Display Semantics Handoff — 2026-11-27

- **Checkout:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-016-vietnamese-copy` (clone độc lập của `origin/main`)
- **Branch / Base HEAD:** `fix/p7-016-vietnamese-copy` @ `0ab884d504b36b99bfb53d17c6c3717fafbf0794` (remote `main` lúc clone; HEAD không đổi)
- **Git:** chỉ sửa working tree; chưa stage, commit, push hay tạo PR.
- **`apps/mobile/node_modules`:** junction tới `p7-012-integration` (cùng hash `package-lock.json`); không cài dependency.
- **Trạng thái:**
  - **Sẵn sàng Codex review lại:** toàn bộ thay đổi ở §3 (vòng 1–4). Vòng 4 sửa finding của Codex về snapshot error mapping (`D:\codex-review\p7-016-evidence\review-results.json`: “P2 new snapshot copy is unreachable on actual HTTP errors; source-prefix case additionally mismatches Backend”).
  - **Chưa hoàn tất P7-016:** các mục trong file thuộc patch timezone (§5) vẫn mở. Không tuyên bố toàn P7-016 hoàn tất.
- **Thiết bị:** kiểm tra runtime Android (`Pixel_7`) và Web là **`DEFERRED_FOR_RAM`**. Không bật browser, Metro, emulator, Docker hay Backend. Kết quả Jest không phải PASS trên thiết bị.

---

## 1. Kết luận semantics dùng cho thay đổi

| Khái niệm | Nguồn | Hiển thị |
|---|---|---|
| Continuity | `ProgressEngineService.java:388-556` | **Trạng thái lịch sử tập luyện**, không phải tần suất. Không dùng “Tần suất tập luyện”. |
| `RECENT_ACTIVITY_OBSERVED` | Backend cố ý không phân loại đều đặn | Câu giải thích ghi rõ “chưa đánh giá mức độ đều đặn”. |
| `RESTRICTED_HISTORY` | 5 nhánh Backend khác nhau (quyền, phạm vi thời gian, độ phủ < 100 ngày…) | Một câu trung tính “phạm vi dữ liệu… chưa đủ để đánh giá”; không chọn nguyên nhân cụ thể. |
| `UNKNOWN_HISTORY` | Không có buổi tập, không có bằng chứng người mới | “chưa thể kết luận về lịch sử tập luyện trước đây”. |
| `PROLONGED_INACTIVITY` + `RETURN_AFTER_100_DAYS_V1` | Cờ từ Backend | Chỉ nhắc lưu ý trở lại tập khi cờ có mặt; không đưa lời khuyên tải tập. |
| `TrendDirection.STABLE` | `deltaVal.compareTo(0) == 0` (dòng 327, 361) | “Không đổi”. Thiếu/không nhận diện → “Chưa xác định xu hướng” (không phải “Ổn định”). |
| Section `availability` | `AVAILABLE`/`NO_DATA`/`RESTRICTED` | `RESTRICTED` ≠ “chưa có dữ liệu”; `AVAILABLE` nhưng không có delta → “chưa đủ dữ liệu để so sánh”; giá trị lạ → trung tính. |
| F04 `resultType=BLOCKED` | `AiProgressSummarizationService.java:357-364` (lỗi xử lý) và validator | “KHÔNG THỂ HIỂN THỊ KẾT LUẬN”; không quy cho “chính sách”. Loại lạ → “CHƯA XÁC ĐỊNH LOẠI KẾT QUẢ” (tông trung tính), không gộp vào BLOCKED. |
| Field errors | `GlobalExceptionHandler` (mã annotation), `F02ContextBuilder` (`REQUIRED`, `INVALID_TYPE`, `INVALID_VALUE` 1–7 ngày/15–180 phút, `INVALID_STATUS`, `RESTRICTED`, `PAST_DATE`, `NOT_CONFIRMED`, `INVALID_DATE`) | Ánh xạ theo `field`/`code`; **không hiển thị `message` server dù có dấu tiếng Việt**. |
| Missing / zero | Invariant dự án | `null`/`undefined` → “—” (không kèm đơn vị) hoặc không hiện; `0` hiện là `0`, `0 kg`, `0%`, “Có buổi tập trong ngày tổng hợp”. |
| Đơn vị `COUNT` | V56 dòng 12 (`COUNT`, ký hiệu `count`, chiều `COUNT`), dòng 40 (`RESTING_HEART_RATE`, `COUNT`) | **Luôn trung tính:** không nối chữ sau số; nhãn loại đơn vị “Số lượng”. Vòng 3 đã rà: ngoài seed (tên chỉ số + khoảng 20–250) không có source/contract nào trong `services`, `contracts`, `apps`, `packages` định nghĩa `RESTING_HEART_RATE` là “mỗi phút”. Kết quả khớp duy nhất là tài liệu RAG trong `data/knowledge`, không phải contract chỉ số. Vì vậy không hiển thị “nhịp/phút”. |
| BMI | `BMI` lưu đơn vị `SCORE` (V56 dòng 27) | Quyết định người dùng: giá trị trần (“BMI: 24,5”), không “điểm”, không nhãn đơn vị. |
| “snapshot” ở lỗi điều chỉnh AI | `GlobalExceptionHandler.java:1013-1018`: `AiRunLifecycleConflictException` → **409** + `errorCode=AI_RUN_LIFECYCLE_CONFLICT`, diagnostic ở `ErrorResponse.message`. `apiClient.ts:95` thay `ApiError.message` bằng câu 409 chung. Diagnostic trong `AiWorkoutAdjustmentService.java`: SHARED ở `:374` “Shared snapshot data is missing or corrupted.”, `:380` “Shared snapshot payload is corrupted.”; SOURCE ở `:562,1288` “Missing plannedWorkoutId in source snapshot.”, `:792,1317` “Missing planId in source snapshot.”, `:535,1282` “Source snapshot is malformed and cannot be verified: ” + chi tiết exception, `:1158` “Source snapshot is malformed: ” + chi tiết exception | Hai nghĩa → hai câu cố định. Phân loại từ `errorResponse.message` (chỉ kiểu string, không phân biệt hoa/thường, chỉ các mẫu trên). Diagnostic không bao giờ được hiển thị (xem §3 vòng 4). |

Rationale tiếng Anh của Backend (`continuity.rationale`) không còn được render; không dịch máy. Văn bản tự do của AI (`summary`, `message`, `*Evaluation`, `limitations`, `missingData`), tên bài tập/catalog, ghi chú người dùng và tên chỉ số tùy chỉnh được giữ nguyên.

## 2. Quyết định copy người dùng đã chốt

1. **Ngoại lệ ngôn ngữ đã được người dùng duyệt:** giữ thuật ngữ **“reps”**. Câu hướng dẫn bao quanh vẫn bằng tiếng Việt (`AiWorkoutAdjustmentModal.tsx` 766-788, 2108-2129). Không đổi mã.
2. **Run ID → “Mã lần xử lý”**; giá trị ID giữ nguyên.
3. **snapshot** → diễn đạt theo semantics tại từng caller; không thay hàng loạt toàn repo.
4. **Đơn vị:** `SCORE` → Điểm (fallback theo ngữ cảnh), `SECOND` → Giây, `MINUTE` → Phút, `SERVING` → Phần (không suy trọng lượng).
5. **(Vòng 3) BMI** hiển thị giá trị không kèm “điểm”, ví dụ “BMI: 24,5”; “Điểm” không áp máy móc lên BMI.
6. **(Vòng 3) COUNT:** chỉ hiển thị “nhịp/phút” khi `metricCode` là nhịp tim **và** source/contract hiện hữu chứng minh semantics đó. Không suy từ fixture, khoảng giá trị, tên hiển thị tự do hay giá trị số. COUNT chưa có bằng chứng giữ hiển thị trung tính.

Không sửa enum, `unitCode`, giá trị lưu hay cách tính Backend.

## 3. Original → bản sửa → semantics → file:dòng

### Mapping dùng chung

| File | Nội dung |
|---|---|
| `apps/mobile/src/localization/progressLabels.ts` (mới) | `metricLabel` (16 mã seed V56 → tiếng Việt; mã khác giữ tên server; không dùng mã làm nhãn); đơn vị `UNIT_LABELS` (dòng 57), `COUNT_NEUTRAL` (80), `METRIC_UNIT_OVERRIDES` chỉ có `'BMI:SCORE'` (86-87), `unitLabel` (109), `unitTypeLabel` (117), `valueFieldLabel` (122), `withUnit` (127); `trendLabel`, `continuityStateLabel` (cùng câu chữ với `CONTINUITY_STATE_LABELS` hiện có), `continuityExplanation`, `daysSinceLastWorkoutText`, `PHOTO_VIEW_OPTIONS`/`photoViewLabel`, `sectionNotice`, `progressSummaryResultTypeLabel`. Tra cứu bằng own-property (mã như `constructor`/`toString` không trả hàm prototype). |
| `apps/mobile/src/localization/fieldErrorMessages.ts` (mới) | `localizeFieldErrorsByCode` |
| `apps/mobile/src/localization/aiRunLabels.ts` (mới) | `aiRunStatusLabel`, `aiRequestTypeLabel` (F01). |

### Vòng 4 — finding Codex: snapshot error mapping

| Original (sau vòng 2–3) | Bản sửa | Semantics | File:dòng |
|---|---|---|---|
| Phân loại snapshot đọc `error.message`, vốn đã bị `apiClient` thay bằng câu 409 chung, nên hai câu snapshot **không bao giờ tới được** qua request/parser thật | `backendDiagnostic(error)` đọc `error.errorResponse?.message`, chỉ nhận `typeof === 'string'` (object/null/number/array → chuỗi rỗng, không ép kiểu), rồi trim + lowercase | Đọc đúng metadata, không ép kiểu | `services/aiWorkoutAdjustmentApi.ts:26-34` |
| `msg.includes('Shared snapshot' \| 'snapshot payload' \| 'missing or corrupted')`, `msg.includes('source snapshot')` (bỏ sót “Source snapshot is malformed…” viết hoa đầu câu), `msg.includes('snapshot')` (khớp quá rộng) | `classifySnapshotDiagnostic`: SHARED `^shared snapshot (data is missing or corrupted\|payload is corrupted)\b`; SOURCE `^missing [a-z_]+ in source snapshot\b` hoặc `^source snapshot is malformed\b`; còn lại `null`. Bỏ nhánh bare `snapshot` và nhánh `missing or corrupted` | Chỉ các diagnostic Backend đã xác minh; không phân biệt hoa/thường; không khớp chỉ bằng từ “snapshot” | `services/aiWorkoutAdjustmentApi.ts:36-50` |
| Nhánh snapshot nằm trong `code === 'AI_RUN_LIFECYCLE_CONFLICT'`, không kiểm status | Chỉ phân loại khi `error.status === 409` **và** `code === 'AI_RUN_LIFECYCLE_CONFLICT'`; mã khác, 403, 500 hay lỗi mạng không bao giờ đi vào nhánh snapshot. Diagnostic không nhận diện → câu chung “Xung đột trạng thái đề xuất điều chỉnh…” như trước | Không che lỗi auth/network/status khác | `services/aiWorkoutAdjustmentApi.ts:68-75` |
| SOURCE: “Dữ liệu nguồn được lưu khi tạo đề xuất không đầy đủ. Vui lòng tạo đề xuất mới.” | “Dữ liệu nguồn được lưu khi tạo đề xuất bị thiếu hoặc không hợp lệ. Vui lòng tạo đề xuất mới.” | Nhóm SOURCE nay gồm cả “malformed” đã xác minh, nên câu phải bao cả “thiếu” lẫn “không hợp lệ”. Câu SHARED giữ nguyên | `services/aiWorkoutAdjustmentApi.ts:73-75` |

Không đổi `apiClient`, Backend hay shared contract. Không đưa diagnostic lên UI. Payload, commandKey, retry, quyền và navigation không đổi.

### Vòng 3 — BMI và COUNT

| Original (sau vòng 2) | Bản sửa | Semantics | File:dòng |
|---|---|---|---|
| `COUNT_BY_METRIC = { RESTING_HEART_RATE: 'nhịp/phút' }` | Bỏ; `COUNT` → `COUNT_NEUTRAL` (hậu tố rỗng, nhãn loại “Số lượng”) cho mọi chỉ số | Không có source/contract cho “mỗi phút” (§1) | `progressLabels.ts:74-80, 99-101` |
| BMI + `SCORE` → “24.5 điểm”, badge “Điểm” | `METRIC_UNIT_OVERRIDES['BMI:SCORE']` → không hậu tố, không nhãn loại | Quyết định 5; chỉ áp khi đơn vị là `SCORE` (BMI với đơn vị khác vẫn theo đơn vị đó) | `progressLabels.ts:82-87, 99-100` |
| — | `valueFieldLabel(base, unitType)` → “Giá trị (kg):” hoặc “Giá trị:” khi không có đơn vị để gọi tên | Không còn “Giá trị ():” hay “Giá trị (đơn vị):” cho BMI | `progressLabels.ts:121-124` |
| `Giá trị ({unitTypeLabel(…) \|\| 'đơn vị'}):` | `valueFieldLabel('Giá trị', code ? unitTypeLabel(…) : 'đơn vị')`. Không có mã đơn vị → giữ “(đơn vị)” như trước; BMI → “Giá trị:” | Giữ hành vi cũ khi thiếu mã | `AddMeasurementModal.tsx:138-146` |
| `Giá trị mới ({unitTypeLabel(…)}):` | `valueFieldLabel('Giá trị mới', unitTypeLabel(…))` | như trên | `CorrectMeasurementModal.tsx:113-116` |
| Badge đơn vị luôn render | Chỉ render khi có nhãn loại đơn vị; thêm `testID="target-unit-{id}"` | BMI không có badge rỗng | `GoalTargetsTab.tsx:86-97` |
| Dashboard ghép `{giá trị} {unitLabel(…)}` với dấu cách cứng → dư khoảng trắng khi đơn vị rỗng (“62 ”, “-8 ”); target thiếu giá trị hiện “— kg” | `formatWithUnit` (thiếu → “—” không kèm đơn vị; có giá trị, kể cả 0 → `withUnit`) và `withUnit` cho delta | Phát hiện bởi regression mới (§7); missing ≠ 0 | `ProgressDashboardScreen.tsx:150-153, 250, 290-291, 298-300` |

Tất cả caller của mapping chung đã được rà và truyền `metricCode`: Dashboard (target, delta), `MeasurementsScreen.tsx:173`, `CorrectMeasurementModal.tsx:101,113`, `AddMeasurementModal.tsx:138`, `GoalTargetsTab.tsx:39-64,86-97`, `GoalProposalDetailScreen.tsx:595,609,611`. Riêng cân nặng ảnh tiến độ (`ProgressDashboardScreen.tsx:412`) không có metric nhưng dùng đơn vị khối lượng, nên không chịu ảnh hưởng. ProgressSummary không hiển thị đơn vị.

### Vòng 2 — theo quyết định 1–4

| Original | Bản sửa | Semantics | File:dòng |
|---|---|---|---|
| `'Run ID không được để trống.'` | `'Mã lần xử lý không được để trống.'` | Quyết định 2 | `services/aiKnowledgeApi.ts:225` |
| “Mã phiên hỗ trợ: {runId}” (thêm ở vòng 1 thay cho “(Run ID: …)”) | “Mã lần xử lý: {runId}” | Quyết định 2; ID giữ nguyên | `features/ai-knowledge/AiKnowledgeScreen.tsx:570` |
| *(Đã thay bằng vòng 4.)* `msg.includes('snapshot') \|\| …` → “Bản nháp chia sẻ bị thiếu hoặc hỏng dữ liệu snapshot. Vui lòng liên hệ Huấn luyện viên để chia sẻ lại.” cho **mọi** message có `snapshot` | (a) “Shared snapshot”/“snapshot payload”/“missing or corrupted” → “Dữ liệu của bản nháp đã chia sẻ (được lưu tại thời điểm chia sẻ) bị thiếu hoặc hỏng. Vui lòng liên hệ Huấn luyện viên để chia sẻ lại.” (b) “source snapshot” → “Dữ liệu nguồn được lưu khi tạo đề xuất không đầy đủ. Vui lòng tạo đề xuất mới.” (c) `snapshot` khác → “Dữ liệu đã lưu của đề xuất không đầy đủ hoặc không hợp lệ. Vui lòng tải lại đề xuất.” | Trước đây lỗi thiếu dữ liệu nguồn (`AiWorkoutAdjustmentService.java:562,792,1288`) bị báo nhầm là lỗi bản chia sẻ và hướng dẫn sai (“chia sẻ lại”). Vẫn giữ cơ chế so khớp message có sẵn; không thêm errorCode | `services/aiWorkoutAdjustmentApi.ts:55-64` |
| (vòng 1) “snapshot v{sharedRevision}” | “phiên bản đã chia sẻ v{sharedRevision}” | Phiên bản học viên đang thấy | `AiWorkoutAdjustmentModal.tsx:1858` |
| `` `${value} ${unitSymbol \|\| unitCode \|\| ''}` `` (ký hiệu seed tiếng Anh `score`/`count`/`s`/`min`/`serving`; dư khoảng trắng khi trống) | `withUnit(value, unitCode, { metricCode, symbol })` | Quyết định 4; đơn vị tùy chỉnh dùng ký hiệu server | `MeasurementsScreen.tsx:173`; `CorrectMeasurementModal.tsx:101`; `GoalTargetsTab.tsx:43,51,54,57,64`; `GoalProposalDetailScreen.tsx:595,609,611` |
| Badge “{unitCode} ({unitSymbol})”, ví dụ “KG (kg)”, “COUNT (count)”; “Giá trị ({unitCode})” | `unitTypeLabel(…)` (vòng 3: qua `valueFieldLabel` / badge có điều kiện) | Nhãn loại đơn vị | `GoalTargetsTab.tsx:86-97`; `AddMeasurementModal.tsx:138`; `CorrectMeasurementModal.tsx:113` |

Giá trị đơn vị gửi lên API (`unitCode` trong `AddMeasurementModal.tsx:86`) không đổi.

### Vòng 1 — `apps/mobile/src/features/progress/ProgressDashboardScreen.tsx` (số dòng theo bản cuối)
| Original | Bản sửa | Semantics | Dòng |
|---|---|---|---|
| `err?.message \|\| 'Không thể tải bảng tiến độ'` | `apiErrorMessage(err, 'Không thể tải bảng tiến độ. Vui lòng thử lại.')` | Không render thông điệp thô | 81 |
| `alert(err?.message \|\| 'Không thể xóa ảnh')` | `apiErrorMessage(err, 'Không thể xóa ảnh. Vui lòng thử lại.')` | như trên | 121 |
| Chip mặc định “Ổn định” cho mọi giá trị khác | `trendLabel`: STABLE “→ Không đổi”, INSUFFICIENT “Chưa đủ dữ liệu”, khác “Chưa xác định xu hướng” | Unknown ≠ stable | 125-148 |
| “Toàn thời gian (Lifetime)” | “Toàn bộ thời gian” | — | 192 |
| `{summary.goal.status}` (mã) | `goalStatusLabels[...] ?? 'Chưa xác định trạng thái'` | — | 236 |
| `• {tgt.metricCode}: {value} {unitCode}` | `metricLabel` / `formatWithUnit` (“—” khi thiếu) | Missing ≠ 0 | 250 |
| “Số đo cơ thể & Thay đổi” | “Số đo cơ thể và thay đổi” | — | 263 |
| `NO_DATA` hoặc rỗng → “Chưa có dữ liệu số đo”; `RESTRICTED` rơi vào bảng | `sectionNotice('measurement', …)` | RESTRICTED / NO_DATA / partial tách riêng | 271-277 |
| `{delta.metricCode}`, `{delta.unitCode}` | `metricLabel`, `formatWithUnit`/`withUnit` | — | 287-300 |
| “Tuân thủ & Khối lượng tập luyện” | “Tuân thủ và khối lượng tập luyện” | — | 318 |
| Chỉ `NO_DATA` có thông báo; `RESTRICTED` hiện “—” như thiếu số | `sectionNotice('workout', …)` | RESTRICTED ≠ thiếu số | 320-323 |
| “Số hiệp chính (Sets)” | “Số hiệp chính” | — | 340 |
| “Tính liên tục tập luyện” | “Tình trạng lịch sử tập luyện” | Không phải tần suất | 360 |
| `{summary.continuity.state}` (mã) | `continuityStateLabel` | Fallback trung tính | 364 |
| “{n} ngày từ buổi tập gần nhất” | `daysSinceLastWorkoutText` (0 → “Có buổi tập trong ngày tổng hợp”) | 0 ≠ missing | 367-369 |
| `{summary.continuity.rationale}` (tiếng Anh Backend) | `continuityExplanation(summary.continuity)` | Từ state/flags; không suy thêm | 371-375 |
| `{p.viewType}` (mã) | `photoViewLabel` | — | 408 |
| “…tự động từ Progress Engine. AI không can thiệp…” | “Số liệu trên do hệ thống tính từ dữ liệu đã ghi nhận. AI không thay đổi số liệu gốc.” | Bỏ tên module nội bộ | 434 |
| “Xem tóm tắt phân tích bằng AI (F04) →” | “Xem tóm tắt phân tích bằng AI →” | Bỏ mã tính năng | 440 |

### Vòng 1 — `apps/mobile/src/features/progress-summary/ProgressSummaryScreen.tsx`
| Original | Bản sửa | Semantics | Dòng |
|---|---|---|---|
| `else` → “BỊ CHẶN BỞI CHÍNH SÁCH” cho mọi loại khác | `progressSummaryResultTypeLabel` + tông `neutral` cho loại lạ | Không gán POLICY/BLOCKED cho loại chưa nhận diện | 80, 712-757 |
| “Tính liền mạch tập luyện” | `CONTINUITY_SECTION_TITLE` | — | 789 |
| `CONTINUITY_STATE_LABELS[state] \|\| state` | `continuityStateLabel(state)` | Không echo mã | 794 |
| `TREND_DIRECTION_LABELS[...] \|\| {label: trendDirection}` | `trendLabel(...)` | Không echo mã; unknown ≠ stable | 858 |
| `… \|\| metric.metricCode` | `METRIC_LABELS` (giữ, own-property) → `METRIC_OPTIONS` → `metricLabel` | Không dùng mã làm nhãn | 859-864 |

### Vòng 1 — các file khác
| File:dòng | Original → bản sửa |
|---|---|
| `features/progress/components/UploadProgressPhotoModal.tsx:45` | “Chính diện (Front)”, “Phía sau (Back)”, “Khác” → `PHOTO_VIEW_OPTIONS` (“Chính diện”, “Phía sau”, …, “Góc khác”) |
| `features/measurements/MeasurementsScreen.tsx:158,257`; `components/AddMeasurementModal.tsx:128`; `components/CorrectMeasurementModal.tsx:95`; `features/goal/components/GoalTargetsTab.tsx:84`; `features/goal/GoalProposalDetailScreen.tsx:586` | `metricDisplayName`/`displayName` (seed V56 tiếng Anh) → `metricLabel(code, serverName)`; chỉ số tùy chỉnh giữ tên gốc |
| `services/aiWorkoutProposalApi.ts:17`, `aiWorkoutAdjustmentApi.ts:21`, `aiWorkoutRescheduleApi.ts:43` | `localizedServerMessage(fe.message, …)` (cho qua khi có dấu) → `localizeFieldErrorsByCode(fieldErrors)`; bỏ điều kiện `fe.message` bắt buộc |
| `services/aiWorkoutRescheduleApi.ts:85` | “…không ở trạng thái CONFIRMED…” → “…không còn ở trạng thái đã xác nhận…” |
| `features/ai-knowledge/AiKnowledgeScreen.tsx:481` | “…kỳ huấn luyện hoạt động (ACTIVE).” → “Bạn chưa có học viên nào trong kỳ huấn luyện đang hoạt động.” |
| `features/ai-knowledge/AiKnowledgeScreen.tsx:563-571` | “Lịch sử tra cứu (Run ID: …)”, `runStatus`/`requestType` thô → tiêu đề “Lịch sử tra cứu”, `aiRunStatusLabel`, `aiRequestTypeLabel`, dòng mã (vòng 2: “Mã lần xử lý”) |
| `features/workout-plan/components/AiWorkoutAdjustmentModal.tsx` | 729/2069 “mã định danh đơn vị (unit ID) cho đơn vị "x"” → “mã định danh đơn vị cho "x"”; 1335 “(AI)” → “bằng AI”; 1400 bỏ “(Recommendation ID)”; 1601 bỏ “(Load Adjustment)”; 1628 bỏ “(Volume Adjustment)”; 1858 xem vòng 2; 1889 bỏ “(Revision Diff)”; 1976 bỏ “(Exercise Catalog)”; 2108/2125 bỏ “(Reps Min/Max)”; 2112/2129 accessibility “Reps tối thiểu/đa” → “Số reps tối thiểu/đa”; 2230 bỏ “(Draft Controls)” |

Quyền actor, mutation, retry/command key, request body, navigation và testID hiện có không đổi.

## 4. Test

- **Mới:** `src/__tests__/progressLabels.test.ts`
  - Vòng 1: unknown trend ≠ stable; STABLE/INSUFFICIENT/unknown phân biệt; RESTRICTED ≠ NO_DATA kể cả khi có dòng; AVAILABLE rỗng ≠ NO_DATA; availability lạ trung tính; 0 ≠ missing; continuity không “tần suất”, không suy đều đặn/nguyên nhân restricted, cờ 100 ngày chỉ khi có; metric hệ thống/tùy chỉnh/không tên/khóa prototype; BLOCKED không “chính sách”, loại lạ không thành BLOCKED; run status/request type không echo mã.
  - Vòng 2: ký hiệu seed tiếng Anh của SCORE/SECOND/MINUTE/SERVING được thay, SERVING không suy trọng lượng; 0 kg giữ, đơn vị tùy chỉnh giữ ký hiệu, không dư khoảng trắng.
  - Vòng 3 (dòng 180-209): BMI trần, không “điểm”, `valueFieldLabel` → “Giá trị:”; SCORE khác (`VISCERAL_FAT`, chỉ số tùy chỉnh, không metric) → “điểm”; ngoại lệ BMI gắn với đơn vị `SCORE` (BMI + `KG` vẫn “kg”); `RESTING_HEART_RATE` + COUNT ở 62/20/250 vẫn trần và nhãn “Số lượng”; COUNT chưa biết metric trần, nhãn “Số lượng”. Test COUNT vòng 2 khẳng định nhịp tim có hậu tố đã được thay theo quyết định 6.
- **Mới (vòng 4):** `src/__tests__/aiWorkoutAdjustmentSnapshotErrors.test.ts`, 21 test.
  - Đi qua `aiWorkoutAdjustmentApi.accept` → `request` → `parseResponse` → `ApiError` → `getWorkoutAdjustmentErrorMessage`, tất cả là code thật; chỉ mock `fetch` (**HTTP mock, không phải live HTTP/DB**).
  - Tiền đề: `ApiError.message` không chứa diagnostic, còn `errorResponse.message` thì có.
  - SHARED (2 diagnostic Backend + 2 biến thể hoa/thường) → đúng câu cố định.
  - SOURCE (missing plannedWorkoutId/planId, malformed có/không “and cannot be verified”, toàn chữ hoa) → đúng câu cố định.
  - Diagnostic chứa chi tiết nhạy cảm giả lập (đường dẫn, token, email) không lọt ra.
  - Chữ “snapshot” trơ trọi / diagnostic lạ → câu chung.
  - Metadata sai kiểu (object, null, number, array) → câu chung, không ép kiểu.
  - Body không phải JSON → câu 409 chung.
  - 403/`AI_FORBIDDEN`, 500, 409 với mã khác, lỗi mạng → không vào nhánh snapshot.
- **Bổ sung (vòng 4):** `AiWorkoutAdjustmentModal.test.tsx:706`: accept bị từ chối với `ApiError` đúng hình dạng `apiClient` tạo ra (message chung, diagnostic nhạy cảm trong `errorResponse`) → `adjustment-error-message` chỉ hiện câu SOURCE cố định; toàn bộ text UI không chứa diagnostic; không hiện thẻ đã áp dụng; `accept` gọi đúng 1 lần.
- **Mới (vòng 3):** `src/__tests__/goalTargetsUnits.test.tsx`: GoalTargetsTab với BMI (không “điểm”, không badge), `VISCERAL_FAT` (“8 điểm”, có badge), nhịp tim và COUNT tùy chỉnh (trần, badge “Số lượng”, tên tùy chỉnh giữ nguyên).
- **Mới:** `src/__tests__/fieldErrorMessages.test.ts` (không hiển thị message server kể cả tiếng Việt; phân biệt required/format/range; khoảng 1–7, 15–180 chỉ cho đúng field; dữ liệu thiếu/khóa prototype; cả 3 API dùng mapping theo code).
- **Bổ sung vào suite hiện có:**
  - `ProgressDashboardScreen.test.tsx`: 6 test vòng 1; thêm 1 test vòng 3 ở dòng 307, gồm delta BMI/VISCERAL_FAT/nhịp tim/COUNT tùy chỉnh và kiểm không dư khoảng trắng.
  - `MeasurementsScreen.test.tsx:108` (vòng 3): danh sách số đo với cùng 4 trường hợp; tên tùy chỉnh giữ nguyên.
  - `ProgressSummaryScreen.test.tsx`: 2 test.
  - `AiKnowledgeScreen.test.tsx`: assertion readback.
  - `aiWorkoutAdjustmentApi.test.ts:238` (vòng 2): source snapshot → “tạo đề xuất mới”.
- **Assertion hiển thị được đổi có chủ đích:**
  - `aiWorkoutAdjustmentApi.test.ts:278-294` và `aiWorkoutRescheduleApi.test.ts:360-362` (vòng 1): trước khẳng định raw message tiếng Việt được hiển thị; nay khẳng định câu theo `code`.
  - `aiKnowledgeApi.test.ts:192` (vòng 2): “Run ID…” → “Mã lần xử lý không được để trống.”.
  - `aiWorkoutAdjustmentApi.test.ts:231-236` (vòng 2): bỏ kỳ vọng chữ “snapshot”.
  - `progressLabels.test.ts` (vòng 3): test COUNT do chính P7-016 thêm ở vòng 2.
  - `aiWorkoutAdjustmentApi.test.ts:232-233` (vòng 4): fixture “Shared draft snapshot data is missing or corrupted” (chuỗi không tồn tại trong Backend) → đúng diagnostic Backend “Shared snapshot data is missing or corrupted.”. Các assertion giữ nguyên.
  - Các assertion về dữ liệu, quyền, payload, commandKey, retry và navigation không bị đổi, xoá hay nới.

## 5. Trùng ownership với patch timezone — chỉ ghi nhận, chưa sửa

Đối chiếu lại với danh sách 56 file của patch timezone: **không file nào đã sửa nằm trong danh sách**.

| File:dòng | Vấn đề | Đề xuất sau khi merge timezone |
|---|---|---|
| `services/aiProgressSummaryApi.ts:41-47` | `CONTINUITY_STATE_LABELS` trùng nội dung với `progressLabels` | Re-export/xoá bản trùng |
| `services/aiProgressSummaryApi.ts:52` | `TREND_DIRECTION_LABELS.STABLE = 'Ổn định'` (ProgressSummaryScreen không còn dùng) | Dùng `trendLabel` |
| `services/aiProgressSummaryApi.ts:112-119` | `getAiProgressSummaryErrorMessage` trả `error.message` thô | Map theo code/status |
| `features/workout-schedule/StudentConfirmBatchScreen.tsx:116`, `features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx:162` | Còn dùng `localizedServerMessage` (cho qua khi có dấu) cho `itemErrors` | Map theo `itemErrors[].code` |
| `features/workout-plan/components/AiWorkoutProposalModal.tsx:610-611` | “Máy cáp (Cable)”, “Máy tạ (Machine)” | Bỏ ngoặc tiếng Anh |
| `ProgressDashboardScreen.tsx:416` `{p.takenAt}`, `AiKnowledgeScreen.tsx:574` `{completedAt}` | Hiển thị chuỗi ISO thô | Thuộc phạm vi định dạng thời gian/timezone |
| Các màn Nutrition (thuộc patch timezone) | Có thể hiển thị đơn vị `SERVING`/ký hiệu seed | Áp `unitLabel`/`withUnit` sau khi merge |

`localizedServerMessage` được giữ trong `displayLabels.ts` vì hai màn trên còn dùng.

## 6. Ghi chú cho reviewer (không chặn review)

1. **COUNT trung tính** cho cả `RESTING_HEART_RATE`: nếu sau này contract/Backend định nghĩa đơn vị theo thời gian (ví dụ một mã đơn vị nhịp/phút), chỉ cần thêm vào `METRIC_UNIT_OVERRIDES` hoặc `UNIT_LABELS`, kèm dẫn nguồn.
2. **Ngoại lệ BMI** chỉ khớp chính xác `metricCode === 'BMI'` với đơn vị `SCORE`.
3. **Ghi đè tên chỉ số hệ thống:** nếu sau này quản trị đổi `display_name` của mã V56, app vẫn ưu tiên nhãn trong `progressLabels`.
4. **`err?.message` ở Measurements/Goal** (`MeasurementsScreen.tsx:71`, `AddMeasurementModal.tsx:96`, `CorrectMeasurementModal.tsx:82`, `GoalHistoryTab.tsx:78,80,105`, `GoalProposalsTab.tsx:95,97`): đã kiểm bằng chứng — `apiClient.ts:95,128,236` dựng `ApiError.message` tiếng Việt; lỗi tự ném duy nhất của `measurementApi.ts:120` là tiếng Việt. Không có nguồn rò rỉ cụ thể nên chưa đổi.
5. Nhánh lỗi snapshot vẫn phân loại bằng văn bản diagnostic (nay lấy đúng từ `errorResponse.message`). Nên chuyển sang errorCode riêng khi Backend có mã ổn định.
8. **Cùng loại lỗi với finding, chưa sửa (ngoài phạm vi hẹp của vòng 4):** `getWorkoutAdjustmentErrorMessage` còn 10 phép so khớp `msg.includes(...)` trên `error.message` đã bị chuẩn hoá (`aiWorkoutAdjustmentApi.ts` từ dòng 55): `expired`, `only SCHEDULED`, `Idempotency conflict`, `Revision conflict`, `already been decided/applied`, `invalid load`, `Cannot share draft in SELF_DIRECTED` (2 chỗ), `not been shared`, `Only trainer/student may edit`. Qua request thật, các nhánh này rơi về câu chung của code/status. Có từ trước P7-016; cần một task riêng để quyết định có phân loại theo diagnostic hay không. **→ Đã xử lý ở follow-up §11 (nhánh `fix/p7-016-ai-error-mapping`).**
6. Placeholder/nhãn “(UUID)” ở modal điều chỉnh AI và câu hỏi mẫu “Barbell Bench Press” (tên bài tập) giữ nguyên.
7. Câu “từ 100 ngày trở lên” lấy từ ngưỡng code Backend `>= 100`; ngưỡng vận hành chính thức vẫn là việc của milestone.

## 7. Lệnh đã chạy

### Lịch sử Jest

- **Vòng 1:** Lượt `--maxWorkers=2` trên 9 suite trọng tâm có 1 lỗi ở `ProgressSummaryScreen.test.tsx:181` (nhãn continuity); đã căn nhãn theo câu chữ hiện có, rồi 3 suite chạy lại qua 66/66. Lượt `--findRelatedTests` 2 worker sau đó qua 24 suite / 393 test.
- **Lượt lỗi chưa rõ nguyên nhân:** Đã quan sát một lượt chạy 2 worker lỗi 36 test; không giữ log, nguyên nhân chưa xác định. Các lượt sau PASS. Lượt đó gồm 36 test trong `AiWorkoutAdjustmentModal.test.tsx` và `CurrentGoalScreen.test.tsx`. Các lượt sau là: hai suite đó chạy riêng `--maxWorkers=1` qua 76/76; `--findRelatedTests` 2 worker qua 24/393; vòng 2 và vòng 3 `--runInBand` (bên dưới).
- **Vòng 2:** `--runInBand`, giữ log đầy đủ; 8 suite / 169 test, rồi 25 suite / 422 test; không có lượt lỗi.
- **Vòng 4:** `--runInBand`, giữ log đầy đủ.
  - Trước khi sửa (`jest-r4-snapshot-BEFORE-fix.log`): regression mới **fail đúng như finding: 10 fail / 11 pass / 21**. Cả 10 là các ca SHARED/SOURCE nhận câu chung “Xung đột trạng thái đề xuất điều chỉnh…”. Các ca guard qua vì code cũ không bao giờ tới nhánh snapshot.
  - Sau khi sửa (`jest-r4-snapshot-AFTER-fix.log`): `aiWorkoutAdjustmentSnapshotErrors` + `aiWorkoutAdjustmentApi`: 2/2 suite, 44/44 test.
  - Focused (`jest-r4-focused.log`): 8/8 suite, 120/120 test (bên dưới).
- **Vòng 3:** `--runInBand`, giữ log đầy đủ.
  - Lượt 1 (`jest-r3-units.log`): **1 lỗi**, chính ở test mới `ProgressDashboardScreen.test.tsx:307`. Kỳ vọng `"| -8 |"`, nhận `"… Hiện tại: 62  | -8  | ▼ Giảm"`. Đã điều tra: Dashboard ghép `{giá trị} {đơn vị}` với dấu cách cứng nên dư khoảng trắng khi đơn vị rỗng. Đây là lỗi thật ở caller, không phải ở mapping. Đã sửa caller (§3 vòng 3); assertion giữ nguyên.
  - Lượt 2 (`jest-r3-units-after-fix.log`), sau khi sửa code: 9/9 suite, 165/165 test.

### Vòng 4 (snapshot cuối, chạy tuần tự trong `apps/mobile`)

Trước khi chạy: Codex đã nhường lượt; C: trống 16,50 GB, RAM trống 1,67 GB. Chỉ có 2 tiến trình node runtime nhàn rỗi (0–2 MB), không có Jest/tsc/build khác. Đã đọc evidence Codex (`review-results.json`, `independent-extra-results.json`) và chỉ đọc, không sửa thư mục evidence.

| Lệnh | Kết quả |
|---|---|
| `npx jest --runInBand src/__tests__/aiWorkoutAdjustmentSnapshotErrors.test.ts` (trước khi sửa) | exit 1; 10 fail / 11 pass |
| `npx jest --runInBand aiWorkoutAdjustmentSnapshotErrors.test.ts aiWorkoutAdjustmentApi.test.ts` (sau khi sửa) | exit 0; 2/2 suite, 44/44 |
| `npx jest --runInBand --findRelatedTests src/services/aiWorkoutAdjustmentApi.ts src/__tests__/aiWorkoutAdjustmentSnapshotErrors.test.ts` | exit 0; **8/8 suite, 120/120 test**: `AiWorkoutAdjustmentModal`, `aiWorkoutAdjustmentSnapshotErrors`, `workoutScheduleChange`, `studentWorkoutPlanScreens`, `workoutPlanScreens`, `ai-workout-reschedule/entryScreens`, `aiWorkoutAdjustmentApi`, `fieldErrorMessages` |
| `npx tsc --noEmit` | exit 0, không output |
| `npx expo lint <file đã sửa>` | exit 0, không output |
| `npm run lint` | 0 lỗi, 12 cảnh báo **baseline** (cùng 2 file, không sửa); **không có cảnh báo mới** |
| `npm run audit:ui-mixed` | 13 dòng (không đổi) |
| `node tools/validate-ux-docs.mjs` (sau khi ghi tài liệu) | “UX documentation validation passed (18 required files).” |
| `git diff --check` + kiểm khoảng trắng cuối dòng ở file mới | exit 0 (chỉ có cảnh báo chuẩn hoá LF→CRLF của Git) |
| `sha256sum -c` 30 hash ở §8 | khớp |

Không chạy lại toàn bộ 430 test. Thay đổi chỉ nằm trong một mapper; mọi caller của `aiWorkoutAdjustmentApi.ts` đều có trong 8 suite focused. Kết quả request/parser dùng **HTTP mock**, không phải live HTTP/Backend/DB PASS.

### Vòng 3 (chạy tuần tự trong `apps/mobile`)

Trước khi chạy: C: trống 16,47 GB, RAM trống 0,85 GB. Chỉ có 2 tiến trình node runtime nhàn rỗi (3 MB, 17 MB), không có Jest/tsc/build khác.

| Lệnh | Kết quả |
|---|---|
| `npx jest --runInBand --findRelatedTests progressLabels.ts AddMeasurementModal.tsx CorrectMeasurementModal.tsx GoalTargetsTab.tsx goalTargetsUnits.test.tsx` | exit 1; 8/9 suite, 164/165 test (lỗi ở trên) |
| Cùng lệnh, thêm `ProgressDashboardScreen.tsx`, sau khi sửa caller | exit 0; **9/9 suite, 165/165 test**: `ProgressDashboardScreen`, `ProgressSummaryScreen`, `goalModalP2Races`, `CurrentGoalScreen`, `GoalProposalDetailScreen`, `MeasurementsScreen`, `goalTargetsUnits`, `UploadProgressPhotoModal`, `progressLabels` |
| `npx tsc --noEmit` | exit 0, không output |
| `npx expo lint <file đã sửa>` | exit 0, không output |
| `npm run lint` | 0 lỗi, 12 cảnh báo `no-require-imports`, đều là **baseline** ở `CurrentGoalScreen.test.tsx`, `coachingQrScreens.test.tsx` (cùng số và file như vòng 1–2; không sửa). **Không có cảnh báo mới** |
| `npm run audit:ui-mixed` | 13 dòng (không đổi so với vòng 2): `coach-alex` 3, `Barbell Bench Press` 1, “reps” 9 (đã duyệt) |
| `node tools/validate-ux-docs.mjs` (gốc repo), sau khi ghi tài liệu | “UX documentation validation passed (18 required files).” |
| `git diff --check` + kiểm khoảng trắng cuối dòng ở file mới | exit 0 (chỉ có cảnh báo chuẩn hoá LF→CRLF của Git trên Windows; file gốc là LF và được giữ LF) |
| `sha256sum -c` các hash ở §8 | khớp |

Không chạy lại toàn bộ 25 suite / 422 test ở vòng 3. Thay đổi chỉ nằm trong mapping đơn vị và các caller của nó, và các caller đó đều có trong 9 suite focused qua `--findRelatedTests`. Không chạy full suite Mobile, Backend, Docker, Metro, emulator hay browser.

## 8. SHA-256 cuối (source/test/tài liệu audit)

```
f9418ccee9bc10deb034326a6b454d342816e2a7f69af407fb638df0e9072d9b  apps/mobile/src/__tests__/AiKnowledgeScreen.test.tsx
60b125693830b29a3de151073e2cc1093087f651a36a67cab56f09c3c974948c  apps/mobile/src/__tests__/AiWorkoutAdjustmentModal.test.tsx
44ae2700d97146b8ca74de1ca0aba57bdb2713e581df80869809ab34a29d97dd  apps/mobile/src/__tests__/MeasurementsScreen.test.tsx
d88fa7e3b4e333f37134f672ce284f06162cbba47a9ee8608768fdd6540547a9  apps/mobile/src/__tests__/ProgressDashboardScreen.test.tsx
ea6e04573b3c58563f644344a52e7eeef4d6eeb027615b3cf58f6c1da9096c75  apps/mobile/src/__tests__/ProgressSummaryScreen.test.tsx
b393a49f89486d9ddecca1de0d0c10fd31126649532d389cd954a2d491d39426  apps/mobile/src/__tests__/aiKnowledgeApi.test.ts
61d78c139d964ff5bd286ccd5059a615c256a753b4c422cebb697ab0a7d8a3e6  apps/mobile/src/__tests__/aiWorkoutAdjustmentApi.test.ts
ca498720ead812f0c181544a67e1ec3228b3953f6e1593c4bc64b4c6efe3cb1d  apps/mobile/src/__tests__/aiWorkoutAdjustmentSnapshotErrors.test.ts
fb3b5726169b2ef45bee361d61c5bacfaf43fa8520bd3611b9b7b394dbbf3283  apps/mobile/src/__tests__/aiWorkoutRescheduleApi.test.ts
42aa8b4c828c4b0a64bbc76ff286fff980fcfc0d264575f22292022fdb98566c  apps/mobile/src/__tests__/fieldErrorMessages.test.ts
38c07efebade48fe7e6c2740cb0bc64411ce39bd0ee1edf02c510f42bf5eaa2c  apps/mobile/src/__tests__/goalTargetsUnits.test.tsx
dc6746c95638ab10425355cae225df4736c62a7036ca006689beeba6cf0df415  apps/mobile/src/__tests__/progressLabels.test.ts
8020dc3df948374421d8593d27c7b184f4efc876f5e8c5b80431ae703497b49f  apps/mobile/src/features/ai-knowledge/AiKnowledgeScreen.tsx
acd7b2c5749b2d7310c2ff637f9018e222b60bf19ea0204132000ea78f4d245d  apps/mobile/src/features/goal/GoalProposalDetailScreen.tsx
9e0e1e63fcc268f60d2021a1c0ee8bd9f7b56bae9a7858004227b52dd2a9f916  apps/mobile/src/features/goal/components/GoalTargetsTab.tsx
26a3c1a8c686bc25e74f622b5ac193103bf953518d7f68f9ffbe0698d124b131  apps/mobile/src/features/measurements/MeasurementsScreen.tsx
4525aa43d817acf9be27eb4e4bf928c85642b0664165993231a436386293f091  apps/mobile/src/features/measurements/components/AddMeasurementModal.tsx
4862eb197974a304e7ed475459d51cb8ce18536fc3da13a94ea66060c52a53ed  apps/mobile/src/features/measurements/components/CorrectMeasurementModal.tsx
cbbea93b494c1df4d649d98c511d18e982720924aae77f7ee4e7d9b20bec1ae4  apps/mobile/src/features/progress-summary/ProgressSummaryScreen.tsx
dec8b221c3d101c487a69645d07203fc9b582a36b761f6f11aa5f2bf095fc062  apps/mobile/src/features/progress/ProgressDashboardScreen.tsx
bdb277d8f08e90afdbe6bf19868e284d32e1f90e44a7749b05a40b7fa7e09376  apps/mobile/src/features/progress/components/UploadProgressPhotoModal.tsx
69a66aab4da367c61b4c12ceb059bf87dc4f567d632547581772aafa23082f97  apps/mobile/src/features/workout-plan/components/AiWorkoutAdjustmentModal.tsx
0346ffaf078f313a4d96aa3a7ee8d1b29134d66441fd00a736d5470a65e3e3a8  apps/mobile/src/localization/aiRunLabels.ts
ee77f1fec5edfdabddfec847b25abcff59b483e1ea47686133b948eaa1828de9  apps/mobile/src/localization/fieldErrorMessages.ts
aeef1134edd1a8f272d7c9a1f8baa7b7843811387c48149907c34acfba31de4d  apps/mobile/src/localization/progressLabels.ts
857cfef9a98a525c767dcfaf704a2cb4fda869e96dc6ece85a95a650e1cdcab5  apps/mobile/src/services/aiKnowledgeApi.ts
0767e7edff37210f1d4f489e91f7f8e4d46f92bfde70c139d0accf9c2b7e5c1f  apps/mobile/src/services/aiWorkoutAdjustmentApi.ts
56526397ec126a4f4d3ad2d258a7b76539dc7644ceed9d6c0683392e7ddc83db  apps/mobile/src/services/aiWorkoutProposalApi.ts
fbdc851e1dd29ae34f544084885c44ddb921440b35981faa1f2d08d4f56d0c2f  apps/mobile/src/services/aiWorkoutRescheduleApi.ts
6f0742afdb22019b8b0721c32d7945f93d5ce57aa546e5ac7bb570dbbf555c19  docs/06-ui-ux/mobile-vietnamese-audit.md
```

## 9. Git status cuối

23 file sửa (`git diff --stat`: 543 thêm, 166 xoá) + 7 file nguồn/test mới + handoff này. Không có thay đổi ngoài `apps/mobile/src` và `docs/`. Checkout gốc `ai-integrated-personalized-online-fitness-coaching-system` và các checkout khác không bị đụng tới.


## 10. Codex independent snapshot re-review and main integration — 2026-11-27

- Finding snapshot **FIXED** within the reviewed delta. No new blocking finding. Independently exercised actual `accept → request → parseResponse → ApiError → getWorkoutAdjustmentErrorMessage` source with mocked HTTP transport: 12 checks PASS, 0 FAIL. Shared corruption requests re-sharing; source missing/malformed requests a new proposal. Checked case/whitespace, both source field names, incorrect metadata types, unknown/standalone snapshot text, sensitive fake diagnostics, wrong status/code and network failures. Wire URL/method/body/revision/commandKey asserted without weakening payload checks. This is not live Backend/DB or device evidence.
- Source branch remains read-only at `0ab884d504b36b99bfb53d17c6c3717fafbf0794`, with 23 modified / 8 untracked files and empty index. All 30 handoff hashes plus the handoff file were verified. Delta versus the previous independent review: one API mapper, two changed/added API test files, the modal UI test, audit and handoff only. All other accepted source hashes remain identical.
- Confirmed exact Backend diagnostics in `AiWorkoutAdjustmentService.java:374,380,535,562,792,1158,1282,1288,1317`; `GlobalExceptionHandler.java:1014-1017` emits status 409 and `AI_RUN_LIFECYCLE_CONFLICT`. Classification uses diagnostic metadata only; fixed Vietnamese strings are displayed. The common parser's safe public message behavior remains intact.
- AST comparison with baseline proves the ten legacy non-snapshot `msg.includes(...)` conditions and bodies are unchanged. Their existing normalization limitation remains a separate follow-up (§6), not a new blocker or a claim that they were repaired.
- Reused the review checkout's own installed dependencies after verifying identical package/lock bytes; no npm ci rerun, no dependency junction into Claude. Preserved prior independent FAIL logs and prior 430/430 evidence. No rerun of unchanged 430 tests.
- Current remote main independently fetched: `0ab884d504b36b99bfb53d17c6c3717fafbf0794`. Integration branch: `codex/fix-p7-016-vietnamese-copy`. Main is identical to the reviewed baseline; switching the separate review checkout to that base introduced no source change or conflict. The 30 source/test/audit hashes in §8 therefore remain the integration hashes. Only this handoff gains independent results; it is intentionally not self-hashed.
- Focused source/caller regression: `NODE_OPTIONS=--max-old-space-size=512 npm test -- --runInBand --testTimeout=30000 --findRelatedTests src/services/aiWorkoutAdjustmentApi.ts src/__tests__/aiWorkoutAdjustmentSnapshotErrors.test.ts`: **8 suites, 120/120 PASS**, exit 0 (16.766 s), including the 21-case HTTP snapshot suite and modal UI negative control. Unit fixture corrected to the exact Backend wording; existing mutation/auth/retry assertions retained.
- Remaining typecheck/lint/docs results are recorded below before checkpoint. Full Mobile suite belongs to new-head CI. No local Backend, Docker, browser, Metro, emulator, runtime/DB or unrelated worktree actions.
- This is an independent P7-016 copy/semantics checkpoint. It does **not** finish all P7-016 work: timezone-owned follow-ups in §5 stay open, the ten legacy mapper paths stay follow-up, historical 36 two-worker failures remain unexplained, and Android/Web/device/visual QA remains **DEFERRED_FOR_RAM**. Source/test audit counts do not imply full UI QA.
- Independent evidence remains outside Git at `D:\codex-review\p7-016-snapshot-evidence`; original review evidence at `D:\codex-review\p7-016-evidence` remains intact.


### Final independent checkpoint checks

- `NODE_OPTIONS=--max-old-space-size=1024 npm run typecheck`: exit 0.
- `npx --no-install eslint` on `aiWorkoutAdjustmentApi.ts`, `aiWorkoutAdjustmentApi.test.ts`, `aiWorkoutAdjustmentSnapshotErrors.test.ts`, `AiWorkoutAdjustmentModal.test.tsx`: exit 0, 0 warnings. Prior full lint result (0 errors / 12 baseline warnings) is historical from the previous accepted snapshot; full lint on this new head is assigned to CI.
- Root UX docs and diff/staged checks run after this handoff edit; results retained in independent evidence. No source changes followed the passing tests. Local heavy-test slot handed back before GitHub checkpoint/CI work.


## 11. Follow-up: AI Workout adjustment error mapping — 2026-11-27

- **Checkout:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-016-ai-error-mapping`, clone mới.
- **Branch / base:** `fix/p7-016-ai-error-mapping` @ `5fbf931c3b83b51ed6ba6342f5d40263e46d96bf` (main sau PR #179; `git ls-remote` xác nhận; CI main #420 6/6 theo người dùng).
- **Môi trường:** `apps/mobile/node_modules` là junction tới `p7-012-integration`, cùng hash `apps/mobile/package-lock.json` và `package-lock.json` gốc; không cài dependency.
- **Checkout P7-016 cũ:** `p7-016-vietnamese-copy` giữ nguyên, không đụng tới.
- **Phạm vi:** chỉ mapper `getWorkoutAdjustmentErrorMessage`, copy hiển thị và test. Không áp lại diff P7-016 cũ.
- **Không sửa:** Backend, `apiClient`, shared contract hay file thuộc patch timezone. Patch timezone chưa vào main (`git diff 0ab884d5 HEAD` trên 56 file timezone: rỗng). `aiWorkoutAdjustmentApi.ts` không thuộc danh sách đó.

### 11.1 Đường lỗi thật (đã đối chiếu)

- **409 + `AI_RUN_LIFECYCLE_CONFLICT`:** `AiRunLifecycleConflictException` → `GlobalExceptionHandler.java:1013-1018`.
- **403 + `AI_FORBIDDEN`:** `AiRunAccessDeniedException` → `GlobalExceptionHandler.java:1006-1010`.
- **400 + `VALIDATION_FAILED`:** `AiRunValidationException` → `GlobalExceptionHandler.java:1041-1045`.
- **Đường parser:** `apiClient.parseResponse` (`apiClient.ts:95`) thay `ApiError.message` bằng câu tiếng Việt chung theo status. Diagnostic gốc chỉ còn ở `errorResponse.message`.
- **Hệ quả:** source cũ trên main (`5fbf931c`) có **10 nhánh `if`** dựa trên `msg`, gồm **14 phép `msg.includes(...)`**: 5 nhánh lifecycle (6 phép), 2 nhánh VALIDATION (3 phép), 3 nhánh 403 (5 phép). Không phép nào khớp qua request thật. Regression trước khi sửa: 16 fail (§11.4). Bảng dưới tách theo diagnostic Backend nên có số dòng khác 10.

### 11.2 Từng nhánh và kết quả xử lý

Diagnostic trích từ `AiWorkoutAdjustmentService.java`. Mọi mẫu khớp đều chạy trên `errorResponse.message` (chỉ chấp nhận kiểu string, trim + lowercase) và neo trọn câu Backend. Chỉ áp khi đúng status và mã lỗi.

| # | Nhánh cũ (`msg.includes`) | Backend thực tế | Kết quả | Copy hiển thị |
|---|---|---|---|---|
| 1 | `expired` | `:517,1010,1109,1243` “Recommendation has expired and cannot be accepted\|rejected\|edited\|shared.” | **Cần sửa → đã sửa** | “Đề xuất điều chỉnh đã hết hạn nên không thể tiếp tục thao tác.” Câu cũ “…Vui lòng tạo đề xuất mới.” tự đưa ra bước tiếp theo mà Backend không nêu, nên đã bỏ phần đó. |
| 2 | `only SCHEDULED` | `:580` “Cannot adjust occurrence with status X (only SCHEDULED occurrences can be adjusted).”; `:1306` “Cannot share adjustment for occurrence with status X (only SCHEDULED occurrences can be shared).” | **Cần sửa → đã sửa**, tách 2 ca | “Chỉ có thể điều chỉnh buổi tập đang ở trạng thái đã lên lịch.” / “Chỉ có thể chia sẻ đề xuất cho buổi tập đang ở trạng thái đã lên lịch.” (bỏ “ĐÃ LÊN LỊCH (SCHEDULED)”). |
| 3 | `Idempotency conflict` | `:501` “Idempotency conflict: commandKey matches a previous command with differing parameters.” | **Cần sửa → đã sửa** | Giữ câu cũ: “Xung đột thao tác (trùng khóa lệnh nhưng nội dung hoặc phiên bản kỳ vọng khác nhau).” |
| 4 | `Revision conflict` | `:522,1140,1273` “Revision conflict: expected revision N\|null but current is M”; `:526` “Revision conflict: draft has been modified to revision M; expectedRevision is required when accepting.” | **Cần sửa → đã sửa** | “Bản nháp đã được cập nhật sang phiên bản khác (xung đột phiên bản). Vui lòng tải lại đề xuất mới nhất.” Bỏ “ở một phiên khác” (không chứng minh). Hướng dẫn “tải lại” được giữ vì Backend báo đã có revision mới hơn, và modal vốn hiện khung xung đột kèm nút tải lại cho mọi 409. |
| 5a | `already been applied` | `:507` “Recommendation has already been applied.” | **Cần sửa → đã sửa** | “Đề xuất điều chỉnh này đã được áp dụng trước đó.” Câu cũ “đã được quyết định” không đúng nghĩa “đã áp dụng”. |
| 5b | `already been decided` | Không có trong module AI (chỉ có ở module goal) | **Không áp dụng → bỏ** | Ca gần nghĩa có thật là “Recommendation is in status X and cannot be accepted\|rejected\|edited\|shared.” (`:512,1005,1104,1238`). X có thể là EXPIRED, CANCELLED…, nên **không** gán “đã quyết định”; ca này giữ câu trung tính chung của lifecycle. |
| 6 | `invalid load` / `load must be positive` (VALIDATION) | Không có ở Backend (`services/` grep rỗng) | **Không áp dụng → bỏ** | 400 không có fieldErrors rơi về `ApiError.message` đã chuẩn hoá của `apiClient` (câu 400 chung). |
| 7 | `Cannot share draft in SELF_DIRECTED` (VALIDATION) | Không có ở Backend | **Không áp dụng → bỏ** | — |
| 8 | `not been shared` (403) | `:364` “Access denied: Private trainer draft has not been shared with the student.” | **Cần sửa → đã sửa** | Giữ câu cũ: “Bản nháp của Huấn luyện viên chưa được chia sẻ cho học viên.” |
| 9 | `Cannot share draft in SELF_DIRECTED` (403) | Chuỗi không tồn tại; Backend thật ở `:1253` là “Draft sharing is only supported in HUMAN_COACH mode.” | **Cần sửa → đã sửa theo diagnostic thật** | “Chỉ có thể chia sẻ bản nháp ở chế độ Có huấn luyện viên.” Nhãn chế độ lấy theo `RelationshipDetailScreen.tsx:82`; không còn mã `SELF_DIRECTED`. |
| 10 | `Only trainer may edit` / `Only student may edit` (403) | Chuỗi không tồn tại; Backend thật ở `:1123,1131` là “Only the currently assigned coach\|the student has authority to edit this recommendation.” | **Cần sửa → đã sửa theo diagnostic thật** | Giữ câu cũ: “Bạn không có thẩm quyền chỉnh sửa đề xuất điều chỉnh này trong chế độ hiện tại.” |
| 11 | *(không có nhánh cũ; rơi vào câu 403 chung “không có thẩm quyền quyết định…”)* | `:1265` “Only the currently assigned coach has authority to share drafts with the student.” | **Bổ sung theo quyết định của người dùng (đợt tích hợp)** | “Chỉ huấn luyện viên đang phụ trách mới có thể chia sẻ bản nháp cho học viên.” Chỉ khi 403 + `AI_FORBIDDEN`. Các 403 khác trên đường chia sẻ (`:1249` thiếu coaching context, `:1257` chuyển chế độ, `:1261` khác kỳ huấn luyện) và đường quyết định (`:443,451,1024,1032`) vẫn giữ câu 403 chung. |

- **Không còn `msg`:** mapper không còn đọc `error.message` để phân loại.
- **Fallback giữ nguyên:**
  - Lifecycle lạ → “Xung đột trạng thái đề xuất điều chỉnh. Vui lòng tải lại trang và xem xét lại.”
  - 403 lạ hoặc mã `AI_RUN_ACCESS_DENIED` → câu thẩm quyền chung.
  - 404, 409 chung, mạng: không đổi.
  - Cuối hàm: `ApiError.message`, đã được `apiClient` chuẩn hoá.
- **Snapshot (PR #179) không đổi:** `classifySnapshotDiagnostic` và hai câu snapshot giữ nguyên.
- **Không đổi:** auth, quyền, payload/commandKey/retry, navigation. Modal chỉ dựa vào `status === 409` để hiện khung xung đột (`AiWorkoutAdjustmentModal.tsx:949,994,1061`), không đọc message.
- **File:dòng:**
  - `services/aiWorkoutAdjustmentApi.ts:52-119`: bảng `LIFECYCLE_DIAGNOSTIC_COPY` (56-89), `FORBIDDEN_DIAGNOSTIC_COPY` (92-114), `copyForDiagnostic` (116-119).
  - `:144-150`: nhánh 409, chỉ khi `status === 409`.
  - `:154-158`: VALIDATION chỉ còn kiểm fieldErrors.
  - `:160-167`: nhánh 403, chỉ khi `status === 403` và `code === 'AI_FORBIDDEN'`.

**Không thuộc 10 nhánh, ghi nhận, chưa sửa:**
- Ca chia sẻ `:1265` đã xử lý (dòng 11 ở bảng trên). Các ca “decision authority to accept/reject” vẫn dùng câu 403 chung “không có thẩm quyền quyết định…”, đúng nghĩa với các ca đó.
- Nhánh cuối `error instanceof Error && error.message` vẫn có thể hiện message tiếng Anh của lỗi không phải `ApiError`. Đây là hành vi có từ trước; `aiWorkoutAdjustmentApi` không truyền `signal` nên không phát sinh `AbortError` từ request.

### 11.3 Test

- **Mới:** `src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts`, 49 test (40 ban đầu, thêm 9 cho 403 chia sẻ). Phần thêm phủ: ca `:1265` và biến thể chữ hoa; 6 diagnostic 403 khác trên đường chia sẻ/quyết định/chỉnh sửa, chuỗi bị nối thêm chi tiết nhạy cảm và chuỗi rút gọn (không được map sang câu chia sẻ); sai mã (403 `AI_RUN_ACCESS_DENIED`) và sai status (409).
  - Đi qua `aiWorkoutAdjustmentApi.accept` → `request` → `parseResponse` → `ApiError` → mapper, tất cả là code thật; chỉ mock `fetch`. Đây là **HTTP mock**, không phải live HTTP/Backend/DB.
  - Phủ: mọi diagnostic Backend ở bảng trên; chữ hoa/thường; chuỗi gần giống nhưng không phải Backend (“already been decided”, fixture cũ “only SCHEDULED…”, “Idempotency conflict detected”, “Revision conflict” trơ trọi…); diagnostic Backend thật chưa có câu riêng (“is in status X…”, “Coaching period has transitioned…”, “Student has no active workout plan.”); diagnostic chứa chi tiết nhạy cảm giả lập; metadata object/null/number/array.
  - Guard: sai status (400 với mã lifecycle, 409 với diagnostic 403), sai mã (409 `WORKOUT_PLAN_VERSION_CONFLICT`, 403 `AI_RUN_ACCESS_DENIED`); VALIDATION không echo diagnostic; lỗi mạng.
- **Cập nhật `aiWorkoutAdjustmentApi.test.ts`:** 4 fixture đổi sang nguyên văn Backend (`:216-221`, `:224`, `:229`, `:254-259`). Hai assertion hiển thị đổi theo copy mới: bỏ kỳ vọng “ĐÃ LÊN LỊCH (SCHEDULED)” / “tự tập luyện (SELF_DIRECTED)”, thêm kiểm không còn mã tiếng Anh. Các assertion khác giữ nguyên.

### 11.4 Lệnh đã chạy (tuần tự, `apps/mobile`)

Trước khi chạy: C: trống 16,34 GB, RAM trống 1,87 GB. Chỉ có 2 tiến trình node runtime Codex nhàn rỗi (2 MB, 6 MB), không có Jest/tsc/Gradle khác. Log nằm ngoài Git, ở scratchpad của phiên (`…\scratchpad\p7-016-ai-error-mapping\`).

| Lệnh | Kết quả |
|---|---|
| `npx jest --runInBand src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts`, **trước khi sửa** (`jest-BEFORE-fix.log`) | exit 1; **16 fail / 24 pass / 40**: toàn bộ ca Backend đã xác minh rơi về câu chung; các ca guard/fallback qua |
| `npx jest --runInBand --findRelatedTests src/services/aiWorkoutAdjustmentApi.ts src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts`, sau khi sửa, timeout mặc định 5 s (`jest-AFTER-fix-focused.log`) | exit 1; 8/9 suite, 124/160. **`AiWorkoutAdjustmentModal.test.tsx` lỗi 36 test.** Log giữ nguyên. |
| Điều tra lượt lỗi trên | Test đầu tiên của suite (`:313`) vượt timeout mặc định 5000 ms. React báo “overlapping act() calls”, renderer bị unmount, 35 test sau lỗi dây chuyền (“Can't access .root on unmounted test renderer”). Checkout clone mới nên cache transform Jest còn trống, và test đầu phải biên dịch component modal khoảng 2.700 dòng. Không liên quan mapper: file modal và test modal không đổi so với main. |
| `npx jest --runInBand --verbose --testTimeout=30000 src/__tests__/AiWorkoutAdjustmentModal.test.tsx` (chẩn đoán, `jest-modal-diagnose-timeout30s.log`) | exit 0; 37/37. Test đầu chậm nhất suite: 1.462 ms khi cache đã nóng (các test khác ≤ 653 ms). |
| `npx jest --runInBand --testTimeout=30000 --findRelatedTests …` (cùng tập, timeout như lượt focused của Codex ở §10; `jest-AFTER-fix-focused-timeout30s.log`) | exit 0; **9/9 suite, 160/160 test**: `AiWorkoutAdjustmentModal`, `studentWorkoutPlanScreens`, `workoutScheduleChange`, `aiWorkoutAdjustmentErrorMapping`, `workoutPlanScreens`, `ai-workout-reschedule/entryScreens`, `fieldErrorMessages`, `aiWorkoutAdjustmentApi`, `aiWorkoutAdjustmentSnapshotErrors` |
| **Đợt tích hợp:** `npx jest --runInBand --testTimeout=30000 src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts`, trước khi sửa ca chia sẻ (`jest-share-authority-BEFORE-fix.log`) | exit 1; 1 fail / 48 pass / 49: đúng ca `:1265` |
| **Đợt tích hợp:** `npx jest --runInBand --testTimeout=30000 --findRelatedTests src/services/aiWorkoutAdjustmentApi.ts src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts`, sau khi sửa (`jest-share-authority-AFTER-fix-focused.log`) | exit 0; **9/9 suite, 169/169 test** |
| `npx tsc --noEmit` | exit 0, không output |
| `npx expo lint` 3 file đã sửa | exit 0, không output |
| `npm run lint` | 0 lỗi, 12 cảnh báo **baseline** ở `CurrentGoalScreen.test.tsx`, `coachingQrScreens.test.tsx` (không sửa). **Không có cảnh báo mới** |
| `npm run audit:ui-mixed` | 13 dòng (không đổi) |
| `node tools/validate-ux-docs.mjs`, `git diff --check` | xem §11.6 |

- **Về các lượt lỗi 36 test:**
  - Lượt lỗi mới ở follow-up này có evidence riêng (`jest-AFTER-fix-focused.log`): test đầu vượt timeout 5 s khi cache transform còn trống, sau đó lỗi dây chuyền.
  - Lượt cũ ở §7 có cùng dạng, nhưng nguyên nhân của lượt cũ **vẫn chưa được chứng minh** vì không có log.
  - Không sửa timeout/config CI và không tối ưu modal trong phạm vi này.
- **Không chạy full Mobile:** thay đổi chỉ nằm ở một mapper, và mọi caller đều có trong 9 suite focused.
- **Không chạy:** Backend, Docker, Metro, emulator hay browser. Device/Web runtime vẫn **`DEFERRED_FOR_RAM`**.

### 11.5 SHA-256 cuối (follow-up)

```
a89e19f30b9279a378facb63a67471aac9421e10aad9820228d1d59680321a1f  apps/mobile/src/services/aiWorkoutAdjustmentApi.ts
15cada8eee658b9e6cf08e02dcc940730aa74fbdae6b0c170cd5c4ef9d979580  apps/mobile/src/__tests__/aiWorkoutAdjustmentApi.test.ts
508fc1585fc49131f6df01f12b456bd928679f3928cd0639bd49e0fa08ae6e24  apps/mobile/src/__tests__/aiWorkoutAdjustmentErrorMapping.test.ts
58cbbcf185fc9028088e624df5be04774353edb703c1c640ec607dc8db015673  docs/06-ui-ux/mobile-vietnamese-audit.md
```

Các file khác của P7-016 trên main không đổi trong follow-up này. Handoff không tự hash chính nó.

### 11.6 Trạng thái và giới hạn

- **Git:** sửa `aiWorkoutAdjustmentApi.ts`, `aiWorkoutAdjustmentApi.test.ts`, `mobile-vietnamese-audit.md` và handoff này; thêm 1 file test mới. Người dùng đã cho phép commit chọn lọc, push, tạo PR base `main` và merge bằng merge commit khi đủ điều kiện. Kết quả PR/CI/merge được báo ngoài tài liệu, không tạo commit chỉ để ghi trạng thái CI. PR #179 và task timezone không bị đụng tới.
- **Review:** thay đổi thuần mapping/copy. Theo quyết định của người dùng, không cần thêm Codex review. Câu “hết hạn” chốt là “Đề xuất điều chỉnh đã hết hạn nên không thể tiếp tục thao tác.”, không hướng dẫn “tạo đề xuất mới”.
- **P7-016 chưa hoàn tất:** các mục Việt hoá trong file thuộc patch timezone (§5) vẫn chờ.
- **Đã chạy sau khi ghi tài liệu:** `node tools/validate-ux-docs.mjs` → “UX documentation validation passed (18 required files).”; `git diff --check` → exit 0.
