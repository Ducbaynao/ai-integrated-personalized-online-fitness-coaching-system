# Light UI — hành trình Coaching

Baseline main `7ebeb970db75dc0768144699e3c832c75c0a886c` (#187, CI #438); branch `codex/ui-light-coaching-details`. Checklist lấy từ source/route thật trước khi sửa; không tạo màn còn thiếu.

| Route / component thật | Đợt này |
|---|---|
| `/coaching` — CoachingHubScreen (ST-17 / entry TR-02) | **Sửa:** card kết nối QR, CTA chính/phụ, navigation có icon, danh sách lời mời/quan hệ có trạng thái và hướng đến/đã gửi, shared loading/empty/error/retry và phân trang. Giữ actor filter, canCoach và mọi destination. |
| `/coaching/qr-profile` — QrProfilePreviewScreen (SH-09) | **Sửa:** hồ sơ công khai từ resolver, chữ viết tắt tên không gọi là ảnh thật; xác minh dùng nhãn Việt hiện có, nhận học viên từ boolean thật, chuyên môn/kinh nghiệm giữ thiếu/null/0. CTA gửi/xem yêu cầu theo canInitiate và outcome; nhắc QR/lời mời chưa mở sharing. Không có route hồ sơ PT công khai độc lập. |
| `/coaching/[relationshipId]` — RelationshipDetailScreen (ST-17 / TR-03) | **Sửa:** chi tiết lời mời và quan hệ chung, người gửi/nhận, trạng thái, kỳ hiện tại và thời gian máy chủ trả về; icon cho hành động theo policy, navigation dữ liệu/trao đổi. Xác nhận lifecycle nêu đối tác và tác động, lý do kết thúc, lỗi trong modal, safe area/keyboard avoidance/cuộn. Không có route chi tiết lời mời hoặc Coaching Period riêng. |
| `/coaching/[relationshipId]/sharing` — SharingPermissionsScreen (ST-17 / TR-12) | **Sửa:** card từng scope với icon/state/level/window thật; Student owner chọn decision/level bằng radio có checked + icon + border, vùng chạm tối thiểu 44 px; Trainer chỉ đọc. Save/revoke vẫn xác nhận trước command; modal nêu scope/tác động, có cuộn/safe area/loading/disabled/error. |
| `/coaching/qr`, `/coaching/my-qr` — scanner/QR identity | **Giữ nguyên:** payload/deep link/decoder/media/camera/rotate và runtime web; không trộn task web của tab khác. |
| `/coaching/trainers`, `/coaching/invite` | **Giữ nguyên:** redirect tương thích sang QR đã có. TrainerDirectoryScreen/StudentLookupScreen legacy không được route hiện hành dùng; không polish hoặc nối lại flow tên/email. |
| Các route con Goal/Nutrition/Program/Progress/Attention/AI | **Giữ nguyên màn đích:** chỉ entry có sẵn tại RelationshipDetail đổi sang NavigationAction, giữ nguyên toàn bộ predicate và callback/destination. |
| Lịch sử quan hệ trong detail | Giữ event/actor/time/reason/pagination, dùng AppCard cùng màn. **Deferred:** đợt UI history toàn diện, không thêm lịch sử period/relationship mới. |

B03 Coaching sở hữu relationship/period/sharing; F12/P2-A-QR chỉ discovery/preview và tái dùng lệnh mời. Student quyết định sharing; counterparty accept/reject, initiator cancel; backend revalidate. Tất cả phần xử lý ngoài JSX và 44 callback trong bốn feature files giữ nguyên qua đối chiếu AST với baseline, gồm mutation payload/key/version/retry/receipt, session/context guards, query/cache/navigation và formatter Việt Nam. Không đổi shared API/token/theme, dependencies, Backend/OpenAPI/Flyway hoặc route/auth guards.

Reference: tái dùng context thực đã xem ở #185 trong SuperFit `ONFwRWdC9bj4Z6mPSe6yr4`: Workout plans `2835:719`, My Workout `2785:86`, Notification `2763:61`. Coaching là **thiết kế nghiệp vụ bổ sung theo pattern**, không có frame Coaching đã xem. Không đọc được frame mới trong đợt này.

**Preview / before-after mới / viewport 360–390–430 / native keyboard và dynamic type visual: NOT RUN.** Browser inventory trả `Local browser access is unavailable for this chat`. Không thay đổi hoặc chiếm runtime QA `D:/codex-work/web-qa-main-*`, không bật Backend/Docker/emulator/server/fixture mới. Test renderer không phải visual PASS hoặc E2E backend/device.

Focused checks đạt **10 suite / 69 test**: màn Coaching/QR, policy/navigation/client/query/messages, shared accessibility/token/font và native event-shaped interactions. Giữ assertion payload/authority/retry; bổ sung xác nhận/cancel, role-action matrix hai chiều, resume version, end reason/pending và revoke exact retry. Typecheck/lint/UX docs/diff và CI đúng head được ghi kết quả cuối trong PR. Test QR dùng gcTime=0 và dọn QueryClient sau mỗi test để không giữ timer; không dùng forceExit. Log FAIL/success và audit JSON giữ cục bộ ngoài repo; không commit dữ liệu riêng hoặc runtime artifacts.

Ngoài đợt này: Goal/Nutrition detail, AI modal, UI history toàn diện và workout-terminal follow-up. Follow-up terminal cần đối chiếu policy correction riêng; chưa kết luận mọi terminal state phải read-only. Chưa polish dark mode, QC toàn app hoặc nghiệm thu release.
