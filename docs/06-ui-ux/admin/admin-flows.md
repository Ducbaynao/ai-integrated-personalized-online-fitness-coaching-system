# Admin Flows

## 1. Trainer application review

1. Admin có `TRAINER_VERIFY` mở review queue.
2. Xem profile, application, certificate và previous review.
3. Approve, Reject hoặc Request More Information.
4. Sensitive decision yêu cầu reason và có thể step-up authentication.
5. Kết quả lưu audit; capability coaching chỉ kích hoạt khi policy đầy đủ.

## 2. User lifecycle action

Admin tìm User trong allowed scope, xem lifecycle/security/report context, chọn Suspend/Unsuspend/Disable/Force Logout. Dialog hiển thị impact, reason, permission và audit. Không cho sửa workout/goal tùy ý.

## 3. Knowledge publishing

Create/Import → Metadata → Processing → Review → Publish. ACTIVE version không sửa đè. Thay đổi tạo DRAFT version mới và publish qua permission/step-up policy.

## 4. Moderation case

Review reporter, subject, evidence và history. Chọn Dismiss, Warn, Feature Restriction, Suspend hoặc Escalate. Mọi action lưu reason, actor, target và time.

## 5. Privileged data access

Admin mở từ Support/Report/Security case, nhập reason, target, scope và duration. Step-up khi cần. Session hiển thị expiry banner và mọi truy cập được audit. Không dùng quyền ADMIN chung để đọc private chat hoặc progress photo.

## 6. Data correction

Support Case → proposed correction → business validation → before/after review → execute → audit. Không cung cấp generic database editor trong Admin UI.

## 7. AI operations

Admin xem run status, latency, token usage, model/prompt version, validation và error. Replay/evaluation tạo evaluation result, không tự tạo business action hoặc áp dụng vào Student data.

