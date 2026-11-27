# Coaching QR: tương thích web và SSR

## Phạm vi

F12 / P2-A-QR, màn SH-08 `/coaching/my-qr`, Student và Trainer.
Baseline tích hợp: `6f48057c8da0952a312c59e32eb1de176124a29b`.

Expo dev SSR duyệt các route và import `MyCoachingQrScreen`. Import trực tiếp
`expo-media-library` ở màn này làm web lỗi `Class extends value undefined is not
a constructor or null` trong lớp `Query` của thư viện. Production export không
đủ để phát hiện đường lỗi dev SSR này.

## Thay đổi

- Tách `useCoachingQrImageExport` theo nền tảng. Web không import thư viện ảnh
  hoặc file native; tạo Blob PNG, yêu cầu trình duyệt tải tệp, rồi dọn anchor và URL.
- Native giữ quyền lưu ảnh, PNG trong cache, `Asset.create` và cleanup hiện có.
- Web thông báo đã yêu cầu tải ảnh, không khẳng định ảnh đã được lưu vào thư viện.
- Giữ guard actor/unmount; không đổi QR URI, backend, quyền, body/key/retry,
  dependency hoặc cấu hình môi trường.

## Kiểm chứng

- Regression web: đúng bytes/MIME/filename, cleanup khi thành công hoặc lỗi,
  operation bị vô hiệu hóa và không có `document` khi SSR.
- Jest sau tích hợp: 3 suite, 21/21 PASS (QR screens, PNG codec, web export).
- Bản main có cleanup QueryClient; không đưa cleanup trùng từ patch cũ vào PR.
- Typecheck PASS; ESLint bốn file thay đổi, UX docs và diff check được ghi
  trong PR theo kết quả thực chạy. Không chạy lại production export vì nó
  không tái hiện lỗi dev SSR ban đầu.
- Bản QA main + cùng ba file chức năng: root HTTP 200, không có Expo static error.
  Đây là kiểm tra HTTP/biên dịch, không phải QA tương tác trên trình duyệt.

Không khởi động lại dịch vụ QA, sửa env, reset DB hoặc thay dữ liệu QA khi tích hợp.
Android/device, thao tác tải PNG trên trình duyệt thật và QR01–QR10 vẫn cần QA;
test mock và export không đóng các gate đó.
