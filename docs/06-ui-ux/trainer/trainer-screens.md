# Trainer Screen Specifications

## TR-01 Trainer Overview

Ưu tiên `Action Required` trước KPI. Section: Today's Sessions, Review Due, High/Medium Attention, Pending Coaching Requests, Unread Messages và Pending AI Recommendations. KPI Active Students nằm sau hàng đợi.

## TR-02 Student List

Filter: Needs Attention, On Track, Inactive, Review Due. Search theo tên. Row/card hiển thị goal, coaching status, last activity, adherence, top signal và next review. Không hiển thị metric ngoài sharing scope.

## TR-03 Student Coaching Workspace

Sticky student header có current goal, mode, relationship, last activity và permission summary. Overview tổng hợp next action; các tab chuyên sâu không làm mất context Student. History hiển thị Goal/Coaching/Plan transitions.

## TR-04 Attention Signal Detail

Loại signal, severity, detected time, status, deterministic rule, evidence references và related trend. Actions: acknowledge, contact Student, request data, open relevant tab, resolve with note. Không gắn nhãn AI.

## TR-05 Plan Builder

Builder theo hierarchy Plan → Day/Session → Exercise → Prescription. Hỗ trợ reorder, duplicate, replace, notes và validation. Publish dialog tóm tắt significant/minor change, affected dates và version behavior.

## TR-06 Goal Proposal Builder

Current vs proposed target/timeline, reason, supporting evidence, effective choice `new version` hoặc `new goal`. UI giải thích rõ khác biệt và việc Student phải xác nhận.

## TR-08 Coaching Review

Review period, progress summary, completion, RPE, nutrition completeness, measurement quality, attention và AI insights. Actions: send feedback, request check-in, update plan, create proposal, schedule follow-up.

## TR-09 Trainer Schedule

Calendar theo timezone, appointment type, conflict và availability. Planned Workout marker tách khỏi appointment marker. Bulk recurring change yêu cầu chọn scope `this`, `this and future`, `series`.

## TR-11 AI Insight Review

Recommendation card không trộn trong deterministic alert queue. Detail gồm context, evidence, missing data, model/prompt metadata ở mức phù hợp, Accept/Modify/Reject và audit outcome.

## TR-12 Student Data Permission

Danh sách domain data và access level/time scope. `Not shared` khác `No data`. Trainer có thể request access nhưng Student quyết định theo policy.

