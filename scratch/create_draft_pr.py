import subprocess, urllib.request, json

p = subprocess.Popen(['git', 'credential', 'fill'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
out, err = p.communicate('protocol=https\nhost=github.com\n\n')
token = None
for line in out.splitlines():
    if line.startswith('password='):
        token = line.split('=', 1)[1]

if not token:
    raise Exception("GitHub token not found in git credentials")

pr_title = "feat(p2-m4): add F03 backend workout adjustment proposals and decisions"
pr_body = """## Tổng Quan & Mục Tiêu (Milestone P2-M4 / Feature F03)

Pull Request này bàn giao toàn bộ gói **Backend, AI Contracts, AI Service Internal Routes và Bằng chứng Kiểm chứng Toàn diện** cho tính năng **F03 (AI Workout Adjustment Proposals & Decisions)** thuộc Milestone P2-M4:
- **Baseline tích hợp:** `b471e25c024447ead5abc08783a8c044a7cfb021` (Merge PR #124)
- **Branch:** `feat/p2-m4-f03-contract-package-20261101`
- **Chi phí AI:** **$0.00** (sử dụng 100% Simulated Provider)
- **Phạm vi bàn giao:** Backend Slice (chưa bao gồm Mobile Client UI)

---

## 1. Năng Lực & Ranh Giới Nghiệp Vụ F03 Đã Triển Khai

1. **Phạm vi điều chỉnh (Scopes):**
   - `ONE_MATERIALIZED_OCCURRENCE`: Điều chỉnh 1 buổi tập cụ thể đã lên lịch (`planned_workout_occurrences`).
   - `TEMPLATE_OR_FUTURE_OCCURRENCES`: Điều chỉnh bản mẫu giáo án gốc (`workout_plans` / `workout_plan_versions`) cho toàn bộ buổi tập tương lai.
2. **Loại hình điều chỉnh (Change Types):**
   - `LOAD_ADJUSTMENT`: Điều chỉnh mức tạ, bảo vệ an toàn dựa trên tín hiệu liên tục tập luyện (`allowLoadIncrease`).
   - `VOLUME_ADJUSTMENT`: Điều chỉnh số hiệp (sets), số lần lặp (reps), thời lượng (duration).
   - `EXERCISE_SUBSTITUTION`: Thay thế biến thể bài tập tương đương (ràng buộc cùng bài tập gốc theo OD-3).
3. **Ma trận thẩm quyền & An toàn (Authority & Security Guards):**
   - Chế độ `SELF_DIRECTED`: Học viên (`STUDENT`) yêu cầu và quyết định duyệt/từ chối.
   - Chế độ `HUMAN_COACH`: Huấn luyện viên (`TRAINER`) phụ trách có thẩm quyền; Học viên bị chặn 403 Forbidden nếu cố tình duyệt.
   - Quản trị viên (`ADMIN`): Là thẩm quyền nền tảng, không có thẩm quyền huấn luyện.
   - Rà soát trạng thái tài khoản trước và sau khi gọi AI model: nếu tài khoản không còn `ACTIVE`, lập tức hủy bỏ và không bàn giao proposal.
4. **Giao dịch nguyên tử & Idempotency (Atomic Accept-and-Apply):**
   - Khóa hàng bi quan (`SELECT ... FOR UPDATE`) trên recommendation.
   - Re-validation danh mục bài tập trước khi thực thi domain command.
   - Ràng buộc trạng thái mục tiêu: chỉ cho phép điều chỉnh buổi tập ở trạng thái `SCHEDULED` (từ chối `ATTEMPTED` hoặc `COMPLETED`).
   - Không có lời gọi mạng AI nào nằm trong apply transaction.
   - Idempotency replay đối soát đầy đủ fingerprint (`commandKey`, `actorId`, `appliedRevision`, `expectedTargetVersion`, `decisionNote`), không coi trường tùy chọn là wildcard.

---

## 2. Kết Quả Kiểm Thử & Nghiệm Thu Tự Động

- **Full-Slice Integration Test (`AiWorkoutAdjustmentFullSliceIntegrationTest`):** **15 / 15 TESTS PASS** (PostgreSQL Testcontainers, kiểm chứng concurrency, stale version guard, race condition accept/reject, catalog inactivation guard).
- **Backend Unit Tests:** **30 / 30 PASS**
  - `AiWorkoutAdjustmentServiceUnitTest`: 11 / 11 PASS
  - `F03ContractValidationUnitTest`: 7 / 7 PASS
  - `F03ContextBuilderUnitTest`: 4 / 4 PASS
  - `F03RuntimeOutputValidatorUnitTest`: 8 / 8 PASS
- **Proposal Boundary Guards (`AiWorkoutProposalServiceUnitTest`):** **33 / 33 PASS** (bảo đảm F02 không thể chấp thuận/sửa F03 recommendations).
- **AI Contracts & Fixtures (`validate-ai-schemas.mjs`):** **7 schemas, 32 fixtures PASS** (10 fixtures F03).
- **FastAPI AI Service Tests (`pytest`):** **67 / 67 PASS** (bao gồm `test_f03_schemas.py` và `test_f03_provider.py`).
- **OpenAPI 3.1.0 Contract (`openapi.yaml`):** Redocly lint **0 errors**, đồng bộ 4 endpoints F03:
  - `POST /api/v1/ai/workout-adjustments`
  - `GET /api/v1/ai/workout-adjustments/{recommendationId}`
  - `POST /api/v1/ai/workout-adjustments/{recommendationId}/accept`
  - `POST /api/v1/ai/workout-adjustments/{recommendationId}/reject`
- **Live HTTP E2E (Backend :8080 → FastAPI :8000 → PostgreSQL :5433):** **5 / 5 Scenarios PASS** (Occurrence, Human Coach Authority, Template Scope, Reject, Target Version Conflict).

---

## 3. Danh Mục Tài Liệu Bàn Giao

- `docs/08-ai/f03-contract-and-authority-design.md`: Bản thiết kế hợp đồng, ma trận thẩm quyền, DTO mapping và domain boundary F03.
- `docs/08-ai/f03-backend-acceptance-report-20261101.md`: Báo cáo nghiệm thu chi tiết, nhật ký kiểm thử 15 integration tests và 5 ca live API.
- `docs/08-ai/f03-backend-checkpoint.md`: Checkpoint trạng thái kỹ thuật và đối soát môi trường.

---

## 4. Kế Hoạch Tiếp Theo

- Review và nghiệm thu gói Backend F03.
- Tiếp tục triển khai gói Mobile F03 Client (Student/Trainer UI review proposals, modals, change preview cards).
"""

payload = {
    "title": pr_title,
    "head": "feat/p2-m4-f03-contract-package-20261101",
    "base": "main",
    "body": pr_body,
    "draft": True
}

req = urllib.request.Request(
    "https://api.github.com/repos/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pulls",
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "Antigravity-F03-Agent"
    },
    method="POST"
)

try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        print("SUCCESS")
        print("PR URL:", data.get("html_url"))
        print("PR Number:", data.get("number"))
        print("Draft:", data.get("draft"))
except urllib.error.HTTPError as e:
    print("HTTPError:", e.code)
    print(e.read().decode())
except Exception as e:
    print("Error:", e)
