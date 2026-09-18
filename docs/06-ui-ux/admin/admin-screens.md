# Admin Screen Specifications

## AD-01 Dashboard

Layout desktop có sidebar và top bar. Hàng đầu là Action Required: trainer applications, moderation cases, failed AI runs, degraded integrations, content review và incidents. Platform/AI/System metrics nằm sau. Widget chỉ xuất hiện khi Admin có permission.

## AD-02 Users and Roles

Data table: identity, roles, profiles, lifecycle status, security state và last activity. Row detail chia Account, Roles/Permissions, Security, Reports, Audit. Sensitive action dùng AdminActionDialog.

## AD-03 Trainer Applications

Queue theo status/age/risk. Detail có application data, certificates, document viewer, verification vs activity status và review history. Decision bắt buộc reason.

## AD-05 Exercise Library

Table/list có status Draft/Active/Archived, muscle, equipment, media, duplicate signal và usage. Archived thay hard-delete khi đã có historical reference. Merge flow hiển thị canonical mapping và impact.

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
