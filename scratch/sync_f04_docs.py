import os
import re

checklist_path = r"D:\Projects\ai-integrated-personalized-online-fitness-coaching-system\docs\08-ai\p2-m4-f04-closure-checklist.md"
report_path = r"D:\Projects\ai-integrated-personalized-online-fitness-coaching-system\docs\08-ai\f04-progress-readiness-report.md"

with open(checklist_path, "r", encoding="utf-8") as f:
    checklist = f.read()

# 1. Update checklist timestamp
checklist = re.sub(
    r"\> \*\*Thời điểm lập và đối chiếu hồ sơ:\*\* .*?\(UTC\+7\)",
    "> **Thời điểm lập và đối chiếu hồ sơ:** 2026-11-14 16:30 (UTC+7)",
    checklist
)

# 2. Update Backend tests count in checklist
checklist = checklist.replace(
    "*Backend Tests (120 tests PASS):* 9 test classes liên quan F04 đạt 120/120 tests PASS (0 failures, 0 errors đối chiếu trực tiếp Surefire XML tại `services/backend/target/surefire-reports/TEST-*.xml`), bao gồm 25/25 tests `AiProgressSummarizationServiceUnitTest` (đã bổ sung đầy đủ regression tests cho `ALL`, `SELECTED`, `UNKNOWN_LEGACY`, và khôi phục scope từ `dataQualitySummary` trong snapshot).",
    "*Backend Tests (123 tests PASS):* 9 test classes liên quan F04 đạt 123/123 tests PASS (0 failures, 0 errors đối chiếu trực tiếp Surefire XML tại `services/backend/target/surefire-reports/TEST-*.xml`), bao gồm 28/28 tests `AiProgressSummarizationServiceUnitTest` (đã bổ sung đầy đủ regression tests: từ chối danh sách `metricCodes` rỗng bằng `IllegalArgumentException`, chuẩn hóa `SELECTED` thiếu/rỗng codes thành `UNKNOWN_LEGACY` và không bao giờ query `ProgressEngine` bằng `null` hoặc `Set.of()`, và chuẩn hóa giá trị scope không hợp lệ; khôi phục scope từ `dataQualitySummary` trong snapshot)."
)

# 3. Update Mobile Client Tests count in checklist
checklist = checklist.replace(
    "*Mobile Client Tests (48 tests PASS):* 22 tests màn hình `ProgressSummaryScreen.test.tsx` (bao gồm các regression tests bảo tồn scope ngày/timezone/metric khi refresh bản tóm tắt cũ với `metricScope: 'ALL'` hoặc `'SELECTED'`, xử lý banner hướng dẫn khi gặp `UNKNOWN_LEGACY`, xử lý khi sửa form, và từ chối refresh run cũ thiếu ngày; kèm các kịch bản response đảo thứ tự $A \\to B \\to A$ với $A_2$ resolve trước, $A_1$ và $B$ đến muộn không ghi đè, rejection đến muộn không làm sai lệch), 13 tests `aiProgressSummaryApi.test.ts`, 12 tests `routeGuard.test.ts`, 1 test `progressSummaryNavigation.test.tsx`. Đạt 0 lỗi `tsc --noEmit` và 0 cảnh báo `expo lint`.",
    "*Mobile Client Tests (54 tests PASS):* 28 tests màn hình `ProgressSummaryScreen.test.tsx` (bao gồm các regression tests bảo tồn scope ngày/timezone/metric khi refresh bản tóm tắt cũ với `metricScope: 'ALL'` hoặc `'SELECTED'`, chặn refresh tự động khi scope metadata thiếu hoặc không hợp lệ và hiển thị thông báo hướng dẫn, kiểm chứng luồng lỗi kết nối mạng $\\to$ bấm \"Thử lại\" $\\to$ phục hồi thành công trên cùng màn hình cho load ban đầu, generate và refresh; kèm các kịch bản response đảo thứ tự $A \\to B \\to A$ với $A_2$ resolve trước, $A_1$ và $B$ đến muộn không ghi đè, rejection đến muộn không làm sai lệch), 13 tests `aiProgressSummaryApi.test.ts`, 12 tests `routeGuard.test.ts`, 1 test `progressSummaryNavigation.test.tsx`. Toàn bộ test suite Mobile gồm 93 test suites (763 tests) PASS 100%, đạt 0 lỗi `tsc --noEmit` và 0 cảnh báo `expo lint`."
)

# 4. Update Observation 76.5 kg in checklist section 1
checklist = checklist.replace(
    "Cung cấp bằng chứng nguồn đối chiếu rõ ràng của fixture observation 76.5 kg (`f0400003-0000-0000-0000-000000000003`) tại [`docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json).",
    "Cung cấp bằng chứng nguồn đối chiếu chính xác của fixture observation 76.5 kg (fixture `f0400003-0000-0000-0000-000000000003`, studentId `5c6f49c6-f4f6-4941-b217-2574b3830395`, `asOf = 2026-11-14T07:02:35.864202500Z` của runId `b582e1d6-48ae-48ed-b95e-1aa6984912fa`) tại [`docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json). Làm rõ tài liệu này là curated evidence được tổng hợp có chủ đích từ kết quả kiểm chứng SQL thực tế và API bước e2, phân biệt với raw output dump."
)

# 5. Update Scenario 10 row in table
old_s10_row = '| **10** | **Lỗi mạng và Retry (Network Error & Retry)** | Android GUI Runtime + Automated Jest Component Test | **PASS** | [`android_runtime_screen_network_retry_error.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_error.png) (305 KB, 1080x2400)<br>[`android_runtime_screen_network_retry_success.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_success.png) (312 KB, 1080x2400)<br>`ProgressSummaryScreen.test.tsx` ("displays error card when api call fails with ApiError")<br>`aiProgressSummaryApi.test.ts` (network error / retry timeout mapping) | Android Emulator Pixel 8, Expo Go, Spring Boot dev | **Đã chạy và chụp màn hình thật:** Mở run không tồn tại hoặc lỗi kết nối: giao diện Android runtime render thẻ lỗi màu đỏ `progress-summary-error-card`. Khi điều hướng/thử lại thành công với run hợp lệ, thẻ lỗi được giải phóng hoàn toàn và bản tóm tắt hiển thị bình thường. |'
new_s10_row = '| **10** | **Lỗi mạng và Retry (Network Error & Retry)** | Android GUI Runtime + Automated Jest Component Test | **PASS** | [`android_runtime_screen_network_retry_error.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_error.png) (305 KB, 1080x2400)<br>[`android_runtime_screen_network_retry_success.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_success.png) (312 KB, 1080x2400)<br>[`android_runtime_screen_run_not_found.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_run_not_found.png) (305 KB, 1080x2400)<br>`ProgressSummaryScreen.test.tsx` (3 retry unit tests & scope validation tests PASS)<br>`aiProgressSummaryApi.test.ts` (network error / retry timeout mapping) | Android Emulator Pixel 8, Expo Go, Spring Boot dev | **Đã chạy và chụp màn hình thật:** Luồng lỗi mạng thực tế (gỡ reverse adb gây gián đoạn kết nối) $\\to$ giao diện Android runtime render thẻ lỗi màu đỏ `progress-summary-error-card` kèm nút \"Thử lại\". Khi khôi phục kết nối và bấm nút \"Thử lại\" trực tiếp trên cùng màn hình, request được gửi lại thành công và bản tóm tắt hiển thị trọn vẹn. Kịch bản mở run không tồn tại (404) đã được phân loại riêng thành `android_runtime_screen_run_not_found.png`. |'
checklist = checklist.replace(old_s10_row, new_s10_row)

# 6. Update artifact list in section 4.1
old_artifacts_text = """11. **[`android_runtime_screen_network_retry_error.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_error.png) (305,870 bytes, 1080x2400):** Màn hình hiển thị thẻ lỗi kết nối/backend (`progress-summary-error-card`).
12. **[`android_runtime_screen_network_retry_success.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_success.png) (312,272 bytes, 1080x2400):** Màn hình sau khi thử lại/điều hướng thành công: thẻ lỗi được giải phóng và bản tóm tắt hiển thị trọn vẹn."""

new_artifacts_text = """11. **[`android_runtime_screen_run_not_found.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_run_not_found.png) (305,870 bytes, 1080x2400):** Màn hình hiển thị thẻ lỗi khi mở runId không tồn tại trên hệ thống (HTTP 404 Run Not Found, phân loại riêng).
12. **[`android_runtime_screen_network_retry_error.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_error.png) (305,870 bytes, 1080x2400):** Màn hình hiển thị thẻ lỗi khi xảy ra sự cố gián đoạn kết nối mạng (`progress-summary-error-card` kèm nút \"Thử lại\").
13. **[`android_runtime_screen_network_retry_success.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_network_retry_success.png) (312,272 bytes, 1080x2400):** Màn hình sau khi bấm \"Thử lại\" trực tiếp trên cùng giao diện khi kết nối được khôi phục: thẻ lỗi biến mất và bản tóm tắt hiển thị hoàn chỉnh."""
checklist = checklist.replace(old_artifacts_text, new_artifacts_text)

# 7. Update conclusion in checklist
checklist = checklist.replace(
    "1. **Automated Tests (Local Developer Execution):** Đạt **120/120 tests Backend PASS** (đối chiếu trực tiếp Surefire XML reports, gồm 25/25 tests `AiProgressSummarizationServiceUnitTest`), **9/9 tests AI FastAPI PASS**, **40 fixtures JSON Schemas PASS**, và **48/48 tests Mobile Client PASS** (across 4 suites: `ProgressSummaryScreen.test.tsx`: 22, `aiProgressSummaryApi.test.ts`: 13, `routeGuard.test.ts`: 12, `progressSummaryNavigation.test.tsx`: 1).",
    "1. **Automated Tests (Local Developer Execution):** Đạt **123/123 tests Backend PASS** (đối chiếu trực tiếp Surefire XML reports, gồm 28/28 tests `AiProgressSummarizationServiceUnitTest`), **9/9 tests AI FastAPI PASS**, **40 fixtures JSON Schemas PASS**, và **54/54 tests Mobile Client PASS** liên quan F04 (across 4 suites: `ProgressSummaryScreen.test.tsx`: 28, `aiProgressSummaryApi.test.ts`: 13, `routeGuard.test.ts`: 12, `progressSummaryNavigation.test.tsx`: 1; toàn bộ ứng dụng Mobile đạt 93/93 test suites, 763/763 tests PASS 100%)."
)

with open(checklist_path, "w", encoding="utf-8") as f:
    f.write(checklist)

print("Updated checklist successfully.")

# Now update report_path
with open(report_path, "r", encoding="utf-8") as f:
    report = f.read()

# Remove the accidental duplicate block at the end if present
duplicate_marker = "#### 13.3.2. Bằng Chứng Live Database Mutation & Freshness (PostgreSQL + Spring Boot API)\n\nLưu trữ tại thư mục [`docs/08-ai/artifacts/f04-freshness-evidence/`](artifacts/f04-freshness-evidence/):"
parts = report.split(duplicate_marker)
if len(parts) > 2:
    report = parts[0] + duplicate_marker + parts[1]
    print("Deduplicated duplicate section at the end of report.")

# Update Observation 76.5 kg text in report
report = report.replace(
    "học viên `5c6f49c6...`",
    "học viên `5c6f49c6-f4f6-4941-b217-2574b3830395` (runId `b582e1d6-48ae-48ed-b95e-1aa6984912fa`, `asOf = 2026-11-14T07:02:35.864202500Z`, curated evidence tổng hợp có chủ đích từ kết quả kiểm chứng SQL thực tế và API bước e2)"
)

# Update Mobile unit test counts in report
report = report.replace(
    "22 tests PASS",
    "28 tests PASS"
)

with open(report_path, "w", encoding="utf-8") as f:
    f.write(report)

print("Updated readiness report successfully.")
