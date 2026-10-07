# Mapping corpus chuẩn bị sang Knowledge

Phạm vi: F01 kiến thức chung + nền F09, P2-M0/P2-M2. Content sở hữu Document/Version và
publish; AI sở hữu chunk/index/retrieval qua contract. Actor import/review/publish là
Admin có permission tương ứng; Student/Trainer đọc F01 qua Spring. Admin không có quyền
coaching qua thao tác publish. Không dùng context Student trong corpus chung.

Đây là contract **staging trên filesystem** (`package-index.json`, format version 1),
chưa phải endpoint Content, OpenAPI hay domain command đã triển khai.

| Dữ liệu package | Mapping dự kiến vào schema hiện có |
|---|---|
| `source_id`, URL, publisher, attribution | Provenance nguồn và `knowledge_documents.source_name/source_url/author` |
| `document_id`, title, language, content role | External lineage của bài; tạo internal UUID do backend quản lý; không dùng ID text thay UUID |
| normalization `version` như `1.0.0` | Giữ trong lineage; không ép trực tiếp vào `knowledge_versions.version_number` integer |
| `content_sha256` | Hash bytes bài nhập cho `knowledge_versions.content_hash`; nếu normalize thêm thì lưu cả input hash và hash sau biến đổi |
| năm nguồn/last reviewed | Ngày nguồn có provenance; unknown vẫn null, không giả ngày 01/01 hay ngày review là publication date |
| PDF pages/section IDs/raw lines, snapshot hash | Source locator trong metadata của chunk, giữ citation tới đúng edition |
| reviewer/review status/issues | Review records qua Content; không tự dùng báo cáo AI làm human reviewer |
| chunk/index | Chỉ tạo sau chọn representation; semantic chunk giữ điều kiện/đơn vị/nhóm dân số/giới hạn |
| PDF và `object_key` | Binary qua object storage có quyền; local staging không phải object ref production |
| evaluation cases | Dataset evaluation tách riêng; không thành Knowledge Chunk/live recommendation |

V9/V15 đã có schema Document/Version/Chunk/Embedding và review/processing; chưa cần thêm
migration chỉ để lưu corpus. Khi triển khai importer domain, xác định chính xác chỗ lưu
external IDs/license/source refs và constraints còn thiếu trước migration mới.

## Điều kiện trước sử dụng thật

1. Content importer thực tế kiểm permission, checksum, dedup và mapping rồi tạo DRAFT;
   không cho FastAPI ghi DB hoặc tự publish.
2. Reviewer nội dung và chuyển ngữ có danh tính, resolve issue, scope và attribution.
   Hướng dẫn dự án tách khỏi tri thức; rule định lượng review/version riêng.
3. Publish có permission/step-up theo policy và audit bắt buộc. Không chỉnh ACTIVE tại chỗ.
4. Chunk giữ Document/Version/locator. Embedding query/index cùng model space/config;
   baseline `vector(1536)` không tự cắt/đệm vector. Chưa chọn model trong integration này.
5. Retrieval lọc lifecycle/eligibility hiện tại kể cả cache; archive không làm mất lineage.
6. F01 request/read qua Spring, INFORMATION không Apply, AI Run/evidence có version.

Test liên quan: T12 nguồn DRAFT/ARCHIVED và injection; T13 thiếu nguồn/validation/provider;
T20 publish quyền/audit; T27 INFORMATION; T28 embedding space. Validator corpus hiện chỉ
chứng minh cấu trúc, snapshot, cờ staging và source/case references. Không chứng minh các
authorization, runtime retrieval, model hoặc release tests này đã đạt.
