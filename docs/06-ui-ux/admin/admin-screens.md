# Admin Screen Specifications

## AD-01 Dashboard

Layout desktop có sidebar và top bar. Hàng đầu là Action Required: trainer applications, moderation cases, failed AI runs, degraded integrations, content review và incidents. Platform/AI/System metrics nằm sau. Widget chỉ xuất hiện khi Admin có permission.

## AD-02 Users and Roles

Data table: identity, roles, profiles, lifecycle status, security state và last activity. Row detail chia Account, Roles/Permissions, Security, Reports, Audit. Sensitive action dùng AdminActionDialog.

## AD-03 Trainer Applications

Queue theo status/age/risk. Detail có application data, certificates, document viewer, verification vs activity status và review history. Decision bắt buộc reason.

## AD-05 Exercise Library

Table/list có status Draft/Active/Archived, muscle, equipment và trạng thái tư liệu khả dụng lấy từ backend. “Khả dụng” nghĩa là có ít nhất một file đã scan sạch và chưa bị xóa; không đồng nghĩa với việc không tồn tại media đang chờ xử lý hoặc bị từ chối. Danh sách không hiển thị object-storage reference và phải giữ phân trang/thứ tự ổn định khi một Exercise có nhiều variation, muscle hoặc equipment. Archived thay hard-delete khi đã có historical reference. Merge flow hiển thị canonical mapping và impact.

Canonical replacement trong B02 chỉ cập nhật ánh xạ từ Exercise đã lưu trữ đến một Exercise đang hoạt động; không viết lại tham chiếu lịch sử. Dialog hiển thị target hiện tại, tìm/chọn target mới, lý do và optimistic version. Khi B04/B05 usage consumer chưa có, impact phải ghi `Chưa khả dụng`, không hiển thị `0`; thao tác đặt lại cùng target hoặc xóa khi chưa có target bị khóa.

Usage counting và khả năng đọc Exercise archived từ màn hình lịch sử thuộc tích hợp B04 Workout Plan/B05 Workout Log; B02 chỉ cung cấp nền tảng lưu trữ, lifecycle và Admin read bảo toàn ID. “Duplicate signal” chưa có semantics phát hiện được phê duyệt, không được suy ra từ `ARCHIVED` hoặc canonical mapping và được deferred cho đến khi Product xác định nguồn tín hiệu, confidence/evidence và hành động review.

Create/Edit dùng Admin form metadata và stable code từ API. Create luôn tạo `DRAFT`; chỉ `DRAFT` có action Edit. Form hỗ trợ variations, muscles và equipment theo contract, cảnh báo thay đổi chưa lưu và xử lý optimistic version conflict mà không ghi đè âm thầm. `ACTIVE` và `ARCHIVED` giữ read-only trong checkpoint Draft Create/Edit.

## AD-06 Knowledge Publishing

Version list và pipeline state. Editor metadata tách processing/review/publish. Active version read-only; Create New Version là CTA thay cho Edit Active.

## AD-07 AI Operations

Dashboard có success/error/validation-failed/timeouts, latency và cost trend. Run Explorer filter request type, model, prompt version, status và time. Không lộ raw private input khi permission/scope không cho phép.

## AD-09 Moderation Cases

Case lifecycle, evidence, previous cases và action history. Action panel theo permission. Escalation giữ reason và target team.

## AD-10 Support Cases

Status Open/In Progress/Waiting User/Resolved/Closed. Tabs conversation, linked resources, correction actions và audit. Data correction luôn có before/after.

## AD-11 Privileged Access

Request list có reason, scope, target, opened/expired time và actor. Active session banner luôn nhìn thấy. Không cho quyền vô thời hạn mặc định.

## AD-14 Audit Log

Search/filter actor, action, target, time và correlation ID. Detail hiển thị before/after phù hợp với permission. Admin UI không có edit/delete audit event.

## AD-15 Configuration and Flags

Feature flag có environment, rollout strategy, audience, owner, status và history. Critical change yêu cầu validation, reason và audit; staged rollout cho Nutrition AI, AI Workout Generation, Pose Estimation và integrations.
