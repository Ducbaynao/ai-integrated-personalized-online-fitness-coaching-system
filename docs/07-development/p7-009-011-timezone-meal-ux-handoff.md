# Bàn giao P7-009/P7-011 (UX Múi giờ & Bộ chọn Bữa ăn) — R5: AbortError là kết quả chưa xác định

## R5 — Sửa hẹp finding AbortError của Codex rereview r4 (chờ Codex review lại)

> Nguồn patch không đổi: `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux-claude`, branch `fix/p7-009-011-timezone-meal-ux` @ `f8e564dca39a27acdc5f7192e31dbb399d471216`. Chưa stage/commit/push/PR và chưa tích hợp main (Codex ghi nhận `0ab884d504b36b99bfb53d17c6c3717fafbf0794`). Evidence của Codex `D:\codex-review\timezone-meal-r4-rereview\` được giữ nguyên. Không chạy `npm ci`, không chạy lại catalog, không đụng P7-016/PR #179 hay checkout khác. **Toàn task chưa hoàn tất** cho tới khi Codex review lại.

- **Tiếp nhận:** 56/56 hash R4.4 và handoff `b7bf823d…e39f` khớp; status 45 `M` / 12 `??` / 0 staged.
- **Giữ nguyên:** hai guard đã đạt ở R4 (không POST khi mất readiness trong lúc token read pending; callback validation cũ không đổi UI mới) và mô hình A/B/C. Ở source, R5 chỉ đổi thông báo và chú thích của nhánh `AbortError` trong `send`.

### R5.1 Finding và invariant

- **Finding:** `NutritionScreen` và `NutritionStrategyScreen` coi mọi `AbortError` là "Lệnh chưa được gửi". Nhưng `fetch` có thể đã nhận POST rồi mới ném `AbortError`. Guard dừng trước một lần retry 401 hoặc trước một lần replay cũng không chứng minh intent gốc chưa từng được gửi.
- **Invariant:** `error.name` không phải bằng chứng chưa dispatch. Mọi `AbortError` được báo là **chưa xác định được kết quả**. UI không khẳng định server đã ghi hay chưa ghi.

### R5.2 Sửa (nhỏ, không refactor API client và không thêm metadata dispatch)

- Nhánh `AbortError` của continuation C ở cả hai màn hình đổi sang thông báo trung tính: "Chưa xác định được kết quả của lệnh. Khi kết nối và ngữ cảnh hợp lệ, hãy thử lại cùng lệnh."
- Không còn câu "chưa được gửi" nào trong source. Chưa có bằng chứng đáng tin cậy cho toàn bộ intent, nên không giữ câu này cho riêng trường hợp nào.
- Hành vi giữ nguyên:
  - Giữ saved intent (`pending`, cùng body/`commandKey`) và nhả operation lock.
  - Dispatch vẫn bị guard B chặn khi readiness lỗi; nút "Thử lại cùng lệnh" chỉ hiện khi context sẵn sàng.
  - Scope cũ không sửa UI hay operation mới (guard A/C).
  - Không hủy intent, không tạo key mới, không tự gửi lại.

### R5.3 Kiểm chứng (thực chạy tuần tự, Node `v22.23.4`)

Regression bổ sung trong `apps/mobile/src/__tests__/timezoneMealReviewR4Regressions.test.tsx` đi qua screen thật → `nutritionApi` thật → `apiClient` thật. Chỉ thay storage/env/`fetch`; token read giữ bằng deferred promise; không sleep.

1. `fetch` nhận 1 POST rồi ném `AbortError`: UI không khẳng định chưa gửi, hiển thị trạng thái chưa xác định; retry gửi đúng URL, body và key (Nutrition và Strategy).
2. Token read pending → GET cùng scope trả 409: 0 POST, nút bị chặn, không deadlock; readiness phục hồi thì retry replay đúng body/key. Hai ca R4 được cập nhật để đòi thông báo trung tính thay vì "chưa được gửi".
3. Intent đã có một lần gửi chưa rõ kết quả (`fetch` nhận POST rồi lỗi mạng) → replay bị guard chặn trước `fetch` (token pending + 409): vẫn 1 POST, UI không nói "chưa từng gửi"; khi context hợp lệ, replay gửi đúng body/key (Nutrition và Strategy).
4. Giữ nguyên các ca validation cũ (actor/ngày, cả hai màn hình) và ca success trong lúc refetch.

| Kiểm tra | Lệnh (cwd `apps/mobile`, trừ khi ghi khác) | Kết quả | Log (`…\p7-009-011-timezone-meal-ux-claude-evidence\r5\`) |
|---|---|---|---|
| Regression **trước sửa** | `NODE_OPTIONS=--max-old-space-size=1024 npx jest src/__tests__/timezoneMealReviewR4Regressions.test.tsx --runInBand --testTimeout=30000` | **FAIL 6/13**, tất cả đúng ở chỗ UI khẳng định "chưa được gửi": 2 ca readiness409, 2 ca abort sau khi `fetch` đã nhận POST, 2 ca replay của intent đã từng gửi. Số POST và body/key đã đúng trước khi tới assertion này. | `r5-pre-fix-fail.log` |
| Regression sau sửa | như trên | **13/13 PASS** | `r5-post-fix.log` |
| Suite chịu ảnh hưởng (mọi suite render NutritionScreen/Strategy) | `NODE_OPTIONS=--max-old-space-size=1280 npx jest <7 file: R4, R3, R2, Codex, timezoneAndMealUx, nutritionScreens, nutritionStrategyScreen> --runInBand --testTimeout=30000` | **7/7 suites, 63/63 tests PASS** | `affected-jest.log` |
| Typecheck | `NODE_OPTIONS=--max-old-space-size=1280 npx tsc --noEmit` | **PASS** | `typecheck.log` |
| Lint có chọn lọc (3 file đổi) | `npx eslint <NutritionScreen.tsx> <NutritionStrategyScreen.tsx> <timezoneMealReviewR4Regressions.test.tsx>` | **PASS**, 0 errors, 0 warnings. 5 warning baseline nằm ở `CurrentGoalScreen.test.tsx`, ngoài delta. | `targeted-lint.log` |
| UX docs | (repo root) `node tools/validate-ux-docs.mjs` | **PASS** | `ux-docs.log` |
| Diff hygiene | (repo root) `git diff --check` | **PASS** | `diff-check.log` |

- Không chạy lại toàn bộ focused 28 suite/300 test: delta chỉ là chuỗi thông báo trong hai màn hình cùng file test của chúng. Kết quả 28/300 của R4 là evidence lịch sử.
- Không chạy Backend, Docker, Metro, browser hay emulator. Device/Web runtime: **`DEFERRED_FOR_RAM`**.

### R5.4 SHA-256 cuối (56 file source/test/doc/script, không gồm chính handoff)

```text
5F46C17B45BEC0E7E247F83D33818B7C9C47FCEF6E33CADE57AC00F3AA6DA3C7  apps/mobile/scripts/generate-backend-timezone-catalog.cjs
0689B712D7D9BA6E50B9C5FA486E9B6680A41504BCE9127C8CAAC2F806B20374  apps/mobile/scripts/timezone-catalog/BackendZoneIds.java
1950F4723D544029F628FD27C33DEDC543574F992A9F47CB048C1A77EBC2B270  apps/mobile/src/__tests__/appointmentScreens.test.tsx
B7C17AC1CBFB8F6CD1895BA06F2FA3D46CB8C74805B926DEA546F7FE296F1AA0  apps/mobile/src/__tests__/foodAnalysisScreen.test.tsx
77A71D0257751986751525C3BC7958761E3B13BEDF2EE8EDBCC59E937FCE21DE  apps/mobile/src/__tests__/nutritionApi.test.ts
A7371D085FC4E853DD75DF006E7EE187B418CB1EFC7BDDA5695F13F02E6DBFFA  apps/mobile/src/__tests__/nutritionScreens.test.tsx
F6F47D534AB31372B7A43CD88D08DA02361C48B7E33B893A9E942B9470FA2C5F  apps/mobile/src/__tests__/nutritionStrategyScreen.test.tsx
C431A75974EBD99054862813F8309E85D3AEDD0E4D33F9B2EDB3DDE79A75E918  apps/mobile/src/__tests__/pushSettingsScreen.test.tsx
FBD2668CC403D1C85D7CE975AD091369D8663239B93B8E7E0FAF329526FC9B67  apps/mobile/src/__tests__/studentChangeProposalScreens.test.tsx
83B47404E485252F0222FAFC4060736E29707AB5096AB00B810251D25A75A41E  apps/mobile/src/__tests__/studentDirectScheduling.test.tsx
5713A0CAC5C0E02BC9D59669908F730108F9D0BBF26AC5EE11BE5E168FF619A0  apps/mobile/src/__tests__/timezoneAndMealUx.test.tsx
E8CAE500BA22A1A9475437CC752B94CD1FB90948BDB9BDB1E8AF8FA9C36F5919  apps/mobile/src/__tests__/timezoneMealCodexRegressions.test.tsx
AE0C5477F6A9DC330795D2D48AAEB6EA31CCC248CAE6FCCF35C403CF7F73DA18  apps/mobile/src/__tests__/timezoneMealReviewR2Regressions.test.tsx
D166ED17457A6A9CF1F55A3FC5FDDE7F223C9BB0BF19A34858C8B899E15B6581  apps/mobile/src/__tests__/timezoneMealReviewR3Regressions.test.tsx
6277FA2F616DE6C9668AC7A2741933A6DD27B6BDABBDCBD3027555894A836371  apps/mobile/src/__tests__/timezoneMealReviewR4Regressions.test.tsx
5397309ADE26616005180019F9B9FE9AE05585742F86A7BEB328364917D104FD  apps/mobile/src/__tests__/trainerChangeProposalScreens.test.tsx
E394970B506FC05E4FE6AEF3318B6A47736A824802B6A9A6C455E5E44D9757C3  apps/mobile/src/__tests__/trainerCreateScheduleProposal.test.tsx
553FC60B2E8C76EF3209D736FB103DDE013F702D1E72374EABDEAF71F8574389  apps/mobile/src/__tests__/trainerWorkoutScheduleScreen.test.tsx
0FCA2C681990A39652B75EF71391CC5BD9B271CEB74E4FD912090E7833367457  apps/mobile/src/__tests__/workoutScheduleChange.test.tsx
C05B9922584376D1467472A5CD2B2334D640BABAD2127D977790489A9B20ED04  apps/mobile/src/__tests__/workoutScheduleProposalScreens.test.tsx
1111A974C73979296C4048129524DFB23D064F1B65634E0C99AF2AD19DE0660F  apps/mobile/src/__tests__/workoutScheduleScreen.test.tsx
24BFAD53F17BE38785BB534634B9E4912ABF0B9D45E415BE968F1554B014B4EA  apps/mobile/src/components/ui/MealTypeSelector.tsx
E2380AA85D90877506E4E12F0D682590B04D5731FFDD9F1F54EE35B0AD00A312  apps/mobile/src/components/ui/TimezoneContextPicker.tsx
4255FDFC217572C92B04B482A1A062DF73F19138D973CA62DBB4886A19AF065A  apps/mobile/src/features/appointments/AppointmentScreens.tsx
E6C81DE980B770C8D72DA8F7239EA06F46477E22B731B1094EAF3FAFB81B747C  apps/mobile/src/features/attention/AttentionReviewScreen.tsx
0A737CE3FB410410E81840216A9000071B330D936D125D8910EBB6E9AE2BA24A  apps/mobile/src/features/auth/HomeScreen.tsx
42CAB7BD3C9DE8CD63428D80A59FEC5DB216A12F360B192F418C09F0608C78AF  apps/mobile/src/features/chat/PushSettingsScreen.tsx
2E04BA87E7BCA7754322FF9479ED9BDCBFB67DFCF1329D2E5129C8BA650EC1B3  apps/mobile/src/features/nutrition/FoodAnalysisScreen.tsx
7E4F927E7742EA4FCC5EE7500C52C3FC9F185932E8DB1F564DAB4F906BDEB085  apps/mobile/src/features/nutrition/NutritionScreen.tsx
C2E04991ED0AACA89AA448CE789C6641C5900670C2C91C70AE3E05A63EAA185B  apps/mobile/src/features/nutrition/NutritionStrategyScreen.tsx
29293AA979D4A24AFCEC57676636A26206D6647A015402E8CAF03154A3097885  apps/mobile/src/features/nutrition/TrainerNutritionScreen.tsx
F401F1B5BC7A6FADE2798AE1C4F5D448B284018A8C812C4A1AF4A87DE5BD9740  apps/mobile/src/features/nutrition/nutritionApi.ts
A0AB211B79CC0EFE8EF9D18066DC33131714B0A7D968DBA35BE2DBD0C6CF13D7  apps/mobile/src/features/shared/backendTimezoneCatalog.ts
9DC8F27121116C47DCA801A8A6FEEB5FB37E0F1B255E5F9E2B4DB59273C4A02B  apps/mobile/src/features/shared/timezoneUtils.ts
55AB8DEA51FF075AE8784F336F0B237185B36E98305290B8684984FE4FFA656B  apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx
2FDAAF98831E242E6F18700F042592C8594EF5B5175B2E2F5204CF99D2C59837  apps/mobile/src/features/workout-recurrence/RecurrenceCreateScreen.tsx
252F88A4E918DFE23F91B3A10C1FD89FD269F5EFE77D8C94992401623B0ABA58  apps/mobile/src/features/workout-recurrence/RecurrenceDetailScreen.tsx
DAE7B8FAAB24634DD99B45692CC4E57E0CA47C06C7E1AF4B22D9403198F8010B  apps/mobile/src/features/workout-recurrence/RecurrenceListScreen.tsx
25AE892B2577FB868964645A633A04983EC3F3AEB722B8006899E43D84B60F9D  apps/mobile/src/features/workout-recurrence/recurrenceForm.ts
E725BD474C37CF3CB083D131663E570BDE86945A6FC6EBE0BA90E08D844E8DF5  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen.tsx
9FE9250225E41B743547264721D0D0955A4050AC3BBEEFD6B9DE7AB337661492  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalsScreen.tsx
CD847FEB2D4008EAC49877D508E28E9D4CF72228845B8FB697A9E8546F29D4AF  apps/mobile/src/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen.tsx
61291B7B6E2D145F8D8579128D8C41829C04DA2E47037030D5232DC5E4031E89  apps/mobile/src/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen.tsx
B5EDB0AF0D550FE5E29A0EECB9A32E95CB8FE0E9F756A9C0B4AE41EE4BE20317  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen.tsx
66031648CAA84DD48EF5D08356DA4DE7AFF27903D759EAC9E3927B2427F9CF01  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalsScreen.tsx
69D6DED09433659D35AC3E6B4CBFFA59373EDA30737B1838E129F2C629CA9E2E  apps/mobile/src/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx
ED42E0CC53A61AF1FD79A575FBCFA4975762CFB534CA8AB6C84780F59AA5CB24  apps/mobile/src/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen.tsx
C4E1D80D2AC6A5FE318819C3945DD84C3EA0C23E0AF58CF236F716388001CD82  apps/mobile/src/features/workout-schedule-proposals/proposalPresentation.ts
4B9A164296A9712FADE6C249B1BCD8149D488F8DC07A6D8A4FE623662FBDE2C4  apps/mobile/src/features/workout-schedule/StudentConfirmBatchScreen.tsx
252979C8979218E152729DFFB045BC339A14F3C69AA2D2AD92387D4048E145B8  apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx
0BA8D311F26DE8081CE29B495B5640AE7B2B129C702D900F4031C78AEEEE9FBA  apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx
E08DB0706D290C749C8AFC8CAB18C9C4572597992AE8804AC07DE93F3225CC20  apps/mobile/src/features/workout-schedule/directBatchValidation.ts
548E7808AA20E19FB85B5B05E3CAE6C5812E65B9C368AAE8A7E09FE32C724201  apps/mobile/src/features/workout-schedule/scheduleChange.ts
20470ED5AE91D9B2E27725A2021BF5BD9B0C441E45168CE938D85D72E38B632E  apps/mobile/src/features/workout-schedule/scheduleDisplay.ts
978EB6CBC3250FFC94FEA610E1D51D1EF269122CD9200911B9461A353DCC5720  apps/mobile/src/services/aiProgressSummaryApi.ts
0164D649AF638673B169E9AB1A7D05CDA9153D0CC98DC297A295086E9A2C8B49  docs/06-ui-ux/component-specifications.md
```

So với R4.4, chỉ 3 file đổi: `NutritionScreen.tsx`, `NutritionStrategyScreen.tsx`, `timezoneMealReviewR4Regressions.test.tsx`.

---

## R4 — Xử lý 2 P2 từ Codex review r4 (chờ Codex review lại)

> Nguồn patch và nơi kiểm chứng **không đổi**: `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux-claude`, branch `fix/p7-009-011-timezone-meal-ux` @ `f8e564dca39a27acdc5f7192e31dbb399d471216`. C còn khoảng 17 GB nên không cần vị trí kiểm chứng riêng trên D. Không chạy `npm ci`; `node_modules` vẫn là junction cũ. Chưa stage/commit/push/PR và chưa tích hợp main mới (Codex ghi nhận main `0ab884d504b36b99bfb53d17c6c3717fafbf0794`). Evidence r4 của Codex (`D:\codex-review\timezone-meal-evidence-r4\`) được giữ nguyên. **Toàn task chưa hoàn tất** cho tới khi Codex review lại.

- **Tiếp nhận:** trước khi sửa, 55/55 hash mục R3.4 và handoff `69c05038…2c44` khớp; status 45 `M` / 11 `??` / 0 staged.
- **Giữ nguyên, không đổi hash:** catalog/generator (`generate-backend-timezone-catalog.cjs`, `BackendZoneIds.java`, `backendTimezoneCatalog.ts`, `timezoneUtils.ts`) cùng giới hạn `SystemV/*`/JRE đã ghi; `MealTypeSelector`; Push binding; half-open version; historical offset. Catalog không đổi nên không chạy lại generator.
- **Bộ nhớ:** lượt chạy đầu crash vì Windows hết commit memory, không phải lỗi heap của test. Sau khi người dùng giải phóng RAM (còn khoảng 3.2 GB virtual), mọi lượt chạy tuần tự với `--max-old-space-size` 1024/1280.

### R4.0 Mô hình ba loại guard (NutritionScreen và NutritionStrategyScreen)

| Guard | Điều kiện | Dùng ở đâu |
|---|---|---|
| **A – scope hiện tại** (`isScopeLive(scope, dayScoped)`) | Còn mounted; cùng session symbol/actor/capability (Strategy: cả `studentId`). Với lệnh theo ngày: cùng **identity** xác nhận (kể cả `null`), ngày và khung giờ của scope sống. | **Dòng đầu** mọi handler (`addItem`, `addCatalogItem`, `correct`, `confirm`, `createFood`, `prepareChange`, `chooseOverride`, `correctFrozenTarget`, `send`) và mọi callback UI mang state gắn scope (qua `runInScope(opScope, dayScoped, action)`), trước validation, `setMessage`, claim operation hay đổi loading. Callback cũ thì im lặng hoàn toàn. |
| **B – được phép dispatch** (`canDispatch`) | A, cộng readiness **sống** của đúng lệnh. Lệnh theo ngày: đã xác nhận và query ngày của chính scope đó vừa tải thành công (`ready`: không pending/fetching/error/409, dữ liệu khớp). Đề xuất của Trainer: quyền `canPropose` hiện hành và đúng học viên. Lệnh Student khác (Strategy): state chiến lược đã tải, không lỗi. | Kiểm tra trước khi claim operation, và là `shouldContinue` mà `apiClient` kiểm **trước đọc token, trước `fetch`, trước retry 401** (kết hợp token sở hữu operation). Readiness lấy từ `liveScopeRef` (cập nhật trong `useLayoutEffect`), không dùng boolean của render cũ, và không phụ thuộc trạng thái pending của chính mutation. |
| **C – continuation** (`owns`) | Token operation còn là `activeOpRef` và A còn đúng. **Không** đòi readiness. | success/error/`finally`. Kết quả của request đã thực sự gửi vẫn được xử lý khi query cùng scope đang refetch. Đổi actor/ngày/khung giờ thì kết quả cũ không sửa UI mới; `finally` chỉ mở lock nếu còn giữ token. |

### R4.1 P2 — readiness mất trong lúc token read pending nhưng POST vẫn phát

- **Nguyên nhân:** `shouldContinue` truyền cho `apiClient` chỉ kiểm ownership + scope (A), không kiểm readiness. GET cùng scope trả 409 trong lúc `getAccessToken()` đang chờ thì scope vẫn giống hệt, nên POST vẫn được phát. Strategy (`chooseDayType`/override/frozen correction) có cùng pattern.
- **Sửa:**
  - `shouldContinue` dùng guard B. Khi B sai, `apiClient` ném `AbortError` thay vì gọi `fetch` (hoặc thay vì gửi lại sau 401). *(Đính chính R5: điều này không chứng minh intent chưa từng tới server; xem R5.1.)*
  - Continuation C nhận diện `AbortError`, giữ nguyên intent đã lưu (`pending` với body/`commandKey` cũ) và mở lock. *(Đính chính R5: thông báo nay là trung tính "Chưa xác định được kết quả của lệnh…"; câu "Lệnh chưa được gửi…" của R4 đã bị bỏ.)*
  - Nút "Thử lại cùng lệnh" chỉ hiện khi context theo ngày đã sẵn sàng trở lại, nên retry replay đúng body/key và không phát qua context chưa giải quyết.
  - Khi GET tải lại 200, thao tác hiện tại hoạt động trở lại.
- **Không tuyên bố hoàn tác server:** guard chỉ chặn trước khi gửi. Request đã gửi thì đi theo continuation C: success/409/400/403 như cũ; lỗi mạng hoặc không rõ kết quả thì giữ intent để retry cùng key.
- **Khác biệt có chủ ý với harness r4 của Codex:** ca `readiness409` của harness còn đòi UI giữ nguyên sau khi thả token read. Bản sửa coi operation này vẫn thuộc scope hiện tại (cùng scope, chỉ mất readiness), nên được phép mở lock và hiển thị thông báo (từ R5 là thông báo trung tính "chưa xác định được kết quả"). Nếu giữ lock, thao tác hiện tại không thể hoạt động lại khi context hợp lệ. Số POST vẫn bằng 0.

### R4.2 P2 — callback validation cũ ghi lỗi vào UI mới

- **Nguyên nhân:** các handler chạy validation và `setMessage` dựa trên closure (`resolvedDayReady`, form của render cũ) trước khi tới guard trong `send`. Giữ callback khi form chưa hợp lệ, đổi actor hoặc ngày rồi gọi lại thì không có mutation, nhưng lỗi cũ ghi vào UI mới. Cùng pattern ở `prepareChange` (Strategy) và các callback set state gắn scope: `setEditing`/`setEditingFood` với món của actor cũ, `setDecision` với đề xuất cũ, `setConfirmedScope`/`setConfirmedDayScope` cũ, các toggle xác nhận.
- **Sửa:** guard A là dòng đầu của mọi handler kể trên, sau đó mới kiểm readiness (B, từ scope sống) và validation. Các callback UI gắn scope dùng `onPress={() => runInScope(opScope, dayScoped, action)}`. Không dùng helper trả về closure trong render vì rule `react-hooks/refs` (React Compiler) báo lỗi; giữ đúng rule, không tắt.
- Callback hiện tại vẫn hiển thị validation của chính nó ("Mô tả món chưa định lượng không được để trống.", "Cần chọn chất dinh dưỡng…", "Chọn loại mục tiêu, tên và ngày hiệu lực hợp lệ."). Các guard success/error/finally của R3 vẫn giữ nguyên.
- Phạm vi rà: chỉ `NutritionScreen` và `NutritionStrategyScreen`, không refactor auth/API client hay toàn app. Input component (`DateInputField`, `TimezoneContextPicker`, `TextInput`) và lựa chọn catalog dùng chung không gắn actor thì giữ nguyên.

### R4.3 Kiểm chứng (thực chạy tuần tự trên checkout tiếp nhận, Node `v22.23.4`)

Regression mới `apps/mobile/src/__tests__/timezoneMealReviewR4Regressions.test.tsx` đi qua screen thật → `nutritionApi` thật → `apiClient` thật. Chỉ thay `@/services/storage` (`tokenStorage.getAccessToken` giữ bằng deferred promise), `@/config/env` và `fetch` (ghi lại mọi request). Không sleep; `notifyManager` chạy đồng bộ.

| Kiểm tra | Lệnh (cwd `apps/mobile`, trừ khi ghi khác) | Kết quả | Log (`…\p7-009-011-timezone-meal-ux-claude-evidence\r4\`) |
|---|---|---|---|
| R4 **trước sửa** | `NODE_OPTIONS=--max-old-space-size=1024 npx jest src/__tests__/timezoneMealReviewR4Regressions.test.tsx --runInBand --testTimeout=30000` | **FAIL 7/9**, đúng nguyên nhân. Finding 1: Nutrition và Strategy mỗi bên phát **1 POST** sau khi GET cùng scope trả 409 trong lúc token read pending. Finding 2: 5 ca UI mới bị đổi bởi callback validation cũ (Nutrition actor/ngày, Strategy override actor/ngày, Strategy preview actor). 2 PASS là kết quả request đã gửi không bị bỏ khi refetch, và validation của callback hiện tại. | `r4-pre-fix-fail.log` |
| R4 sau sửa | như trên | **9/9 PASS** | `r4-post-fix.log` (trước khi đổi sang `runInScope`); lượt focused bên dưới chạy lại trên source cuối |
| Typecheck | `NODE_OPTIONS=--max-old-space-size=1280 npx tsc --noEmit` | **PASS** | `typecheck.log` |
| Lint | `npm run lint` | **PASS**: 0 errors, đúng 5 warning baseline `no-require-imports` ở `CurrentGoalScreen.test.tsx` (2126/2169/2227/2277/2328). Lượt trước đó có 20 lỗi `react-hooks/refs` do helper `inScope` gọi trong render; đã sửa bằng `runInScope` trong event handler. | `lint.log` |
| Focused Jest trên source cuối (27 suite R3 + R4) | `NODE_OPTIONS=--max-old-space-size=1280 npx jest <28 file> --runInBand --testTimeout=30000` | **28/28 suites, 300/300 tests PASS** (67.1 s) | `focused-jest.log` (lượt trước khi sửa lint cũng 28/28, 300/300: `focused-jest-before-lint-fix.log`) |
| UX docs | (repo root) `node tools/validate-ux-docs.mjs` | **PASS** (18 required files) | `ux-docs.log` |
| Diff hygiene | (repo root) `git diff --check` | **PASS** (exit 0); file test mới không có trailing whitespace | `diff-check.log` |

- Kết quả `27 suites / 291 tests` của R3 chỉ là evidence lịch sử; số liệu áp dụng cho source cuối là 28/300 ở trên.
- Sau lượt trước sửa, file test R4 có một chỉnh sửa chỉ về kiểu (`global.fetch` → `globalThis.fetch`), không đổi hành vi.
- Giữ nguyên các regression đổi actor/ngày/khung giờ, guard transport, replay cùng body/key, 401/transport 0 và continuation của R3.
- Không chạy full Mobile, browser, Metro, emulator, Docker, Backend hay stack QA. Device/Web runtime: **`DEFERRED_FOR_RAM`**.

### R4.4 SHA-256 sau R4 (lịch sử; source cuối xem R5.4) (56 file source/test/doc/script, không gồm chính handoff)

```text
5F46C17B45BEC0E7E247F83D33818B7C9C47FCEF6E33CADE57AC00F3AA6DA3C7  apps/mobile/scripts/generate-backend-timezone-catalog.cjs
0689B712D7D9BA6E50B9C5FA486E9B6680A41504BCE9127C8CAAC2F806B20374  apps/mobile/scripts/timezone-catalog/BackendZoneIds.java
1950F4723D544029F628FD27C33DEDC543574F992A9F47CB048C1A77EBC2B270  apps/mobile/src/__tests__/appointmentScreens.test.tsx
B7C17AC1CBFB8F6CD1895BA06F2FA3D46CB8C74805B926DEA546F7FE296F1AA0  apps/mobile/src/__tests__/foodAnalysisScreen.test.tsx
77A71D0257751986751525C3BC7958761E3B13BEDF2EE8EDBCC59E937FCE21DE  apps/mobile/src/__tests__/nutritionApi.test.ts
A7371D085FC4E853DD75DF006E7EE187B418CB1EFC7BDDA5695F13F02E6DBFFA  apps/mobile/src/__tests__/nutritionScreens.test.tsx
F6F47D534AB31372B7A43CD88D08DA02361C48B7E33B893A9E942B9470FA2C5F  apps/mobile/src/__tests__/nutritionStrategyScreen.test.tsx
C431A75974EBD99054862813F8309E85D3AEDD0E4D33F9B2EDB3DDE79A75E918  apps/mobile/src/__tests__/pushSettingsScreen.test.tsx
FBD2668CC403D1C85D7CE975AD091369D8663239B93B8E7E0FAF329526FC9B67  apps/mobile/src/__tests__/studentChangeProposalScreens.test.tsx
83B47404E485252F0222FAFC4060736E29707AB5096AB00B810251D25A75A41E  apps/mobile/src/__tests__/studentDirectScheduling.test.tsx
5713A0CAC5C0E02BC9D59669908F730108F9D0BBF26AC5EE11BE5E168FF619A0  apps/mobile/src/__tests__/timezoneAndMealUx.test.tsx
E8CAE500BA22A1A9475437CC752B94CD1FB90948BDB9BDB1E8AF8FA9C36F5919  apps/mobile/src/__tests__/timezoneMealCodexRegressions.test.tsx
AE0C5477F6A9DC330795D2D48AAEB6EA31CCC248CAE6FCCF35C403CF7F73DA18  apps/mobile/src/__tests__/timezoneMealReviewR2Regressions.test.tsx
D166ED17457A6A9CF1F55A3FC5FDDE7F223C9BB0BF19A34858C8B899E15B6581  apps/mobile/src/__tests__/timezoneMealReviewR3Regressions.test.tsx
7E511DE5D89E5989E820D3174E8A54785583AD30A680AFB08154C1A939C569D3  apps/mobile/src/__tests__/timezoneMealReviewR4Regressions.test.tsx
5397309ADE26616005180019F9B9FE9AE05585742F86A7BEB328364917D104FD  apps/mobile/src/__tests__/trainerChangeProposalScreens.test.tsx
E394970B506FC05E4FE6AEF3318B6A47736A824802B6A9A6C455E5E44D9757C3  apps/mobile/src/__tests__/trainerCreateScheduleProposal.test.tsx
553FC60B2E8C76EF3209D736FB103DDE013F702D1E72374EABDEAF71F8574389  apps/mobile/src/__tests__/trainerWorkoutScheduleScreen.test.tsx
0FCA2C681990A39652B75EF71391CC5BD9B271CEB74E4FD912090E7833367457  apps/mobile/src/__tests__/workoutScheduleChange.test.tsx
C05B9922584376D1467472A5CD2B2334D640BABAD2127D977790489A9B20ED04  apps/mobile/src/__tests__/workoutScheduleProposalScreens.test.tsx
1111A974C73979296C4048129524DFB23D064F1B65634E0C99AF2AD19DE0660F  apps/mobile/src/__tests__/workoutScheduleScreen.test.tsx
24BFAD53F17BE38785BB534634B9E4912ABF0B9D45E415BE968F1554B014B4EA  apps/mobile/src/components/ui/MealTypeSelector.tsx
E2380AA85D90877506E4E12F0D682590B04D5731FFDD9F1F54EE35B0AD00A312  apps/mobile/src/components/ui/TimezoneContextPicker.tsx
4255FDFC217572C92B04B482A1A062DF73F19138D973CA62DBB4886A19AF065A  apps/mobile/src/features/appointments/AppointmentScreens.tsx
E6C81DE980B770C8D72DA8F7239EA06F46477E22B731B1094EAF3FAFB81B747C  apps/mobile/src/features/attention/AttentionReviewScreen.tsx
0A737CE3FB410410E81840216A9000071B330D936D125D8910EBB6E9AE2BA24A  apps/mobile/src/features/auth/HomeScreen.tsx
42CAB7BD3C9DE8CD63428D80A59FEC5DB216A12F360B192F418C09F0608C78AF  apps/mobile/src/features/chat/PushSettingsScreen.tsx
2E04BA87E7BCA7754322FF9479ED9BDCBFB67DFCF1329D2E5129C8BA650EC1B3  apps/mobile/src/features/nutrition/FoodAnalysisScreen.tsx
9B4E836CC834E189760F925DFA6C2C6C85075B98A938758514B26C953525332C  apps/mobile/src/features/nutrition/NutritionScreen.tsx
CED105C01EB80C8612F25B39037749AD3B217FD1383E001FD1D81A4F55B128F8  apps/mobile/src/features/nutrition/NutritionStrategyScreen.tsx
29293AA979D4A24AFCEC57676636A26206D6647A015402E8CAF03154A3097885  apps/mobile/src/features/nutrition/TrainerNutritionScreen.tsx
F401F1B5BC7A6FADE2798AE1C4F5D448B284018A8C812C4A1AF4A87DE5BD9740  apps/mobile/src/features/nutrition/nutritionApi.ts
A0AB211B79CC0EFE8EF9D18066DC33131714B0A7D968DBA35BE2DBD0C6CF13D7  apps/mobile/src/features/shared/backendTimezoneCatalog.ts
9DC8F27121116C47DCA801A8A6FEEB5FB37E0F1B255E5F9E2B4DB59273C4A02B  apps/mobile/src/features/shared/timezoneUtils.ts
55AB8DEA51FF075AE8784F336F0B237185B36E98305290B8684984FE4FFA656B  apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx
2FDAAF98831E242E6F18700F042592C8594EF5B5175B2E2F5204CF99D2C59837  apps/mobile/src/features/workout-recurrence/RecurrenceCreateScreen.tsx
252F88A4E918DFE23F91B3A10C1FD89FD269F5EFE77D8C94992401623B0ABA58  apps/mobile/src/features/workout-recurrence/RecurrenceDetailScreen.tsx
DAE7B8FAAB24634DD99B45692CC4E57E0CA47C06C7E1AF4B22D9403198F8010B  apps/mobile/src/features/workout-recurrence/RecurrenceListScreen.tsx
25AE892B2577FB868964645A633A04983EC3F3AEB722B8006899E43D84B60F9D  apps/mobile/src/features/workout-recurrence/recurrenceForm.ts
E725BD474C37CF3CB083D131663E570BDE86945A6FC6EBE0BA90E08D844E8DF5  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen.tsx
9FE9250225E41B743547264721D0D0955A4050AC3BBEEFD6B9DE7AB337661492  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalsScreen.tsx
CD847FEB2D4008EAC49877D508E28E9D4CF72228845B8FB697A9E8546F29D4AF  apps/mobile/src/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen.tsx
61291B7B6E2D145F8D8579128D8C41829C04DA2E47037030D5232DC5E4031E89  apps/mobile/src/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen.tsx
B5EDB0AF0D550FE5E29A0EECB9A32E95CB8FE0E9F756A9C0B4AE41EE4BE20317  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen.tsx
66031648CAA84DD48EF5D08356DA4DE7AFF27903D759EAC9E3927B2427F9CF01  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalsScreen.tsx
69D6DED09433659D35AC3E6B4CBFFA59373EDA30737B1838E129F2C629CA9E2E  apps/mobile/src/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx
ED42E0CC53A61AF1FD79A575FBCFA4975762CFB534CA8AB6C84780F59AA5CB24  apps/mobile/src/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen.tsx
C4E1D80D2AC6A5FE318819C3945DD84C3EA0C23E0AF58CF236F716388001CD82  apps/mobile/src/features/workout-schedule-proposals/proposalPresentation.ts
4B9A164296A9712FADE6C249B1BCD8149D488F8DC07A6D8A4FE623662FBDE2C4  apps/mobile/src/features/workout-schedule/StudentConfirmBatchScreen.tsx
252979C8979218E152729DFFB045BC339A14F3C69AA2D2AD92387D4048E145B8  apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx
0BA8D311F26DE8081CE29B495B5640AE7B2B129C702D900F4031C78AEEEE9FBA  apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx
E08DB0706D290C749C8AFC8CAB18C9C4572597992AE8804AC07DE93F3225CC20  apps/mobile/src/features/workout-schedule/directBatchValidation.ts
548E7808AA20E19FB85B5B05E3CAE6C5812E65B9C368AAE8A7E09FE32C724201  apps/mobile/src/features/workout-schedule/scheduleChange.ts
20470ED5AE91D9B2E27725A2021BF5BD9B0C441E45168CE938D85D72E38B632E  apps/mobile/src/features/workout-schedule/scheduleDisplay.ts
978EB6CBC3250FFC94FEA610E1D51D1EF269122CD9200911B9461A353DCC5720  apps/mobile/src/services/aiProgressSummaryApi.ts
0164D649AF638673B169E9AB1A7D05CDA9153D0CC98DC297A295086E9A2C8B49  docs/06-ui-ux/component-specifications.md
```

So với R3.4, chỉ 3 file đổi: `NutritionScreen.tsx`, `NutritionStrategyScreen.tsx` và file mới `timezoneMealReviewR4Regressions.test.tsx`. Mọi file khác giữ nguyên hash R3.4.

---

## R3 — Tiếp nhận bởi Claude và xử lý 2 P2 còn lại (chờ Codex review độc lập)

> **Nguồn patch tiếp theo là checkout mới bên dưới.** Checkout của Antigravity giữ nguyên, chỉ đọc, làm bản bàn giao lịch sử. Chưa stage/commit/push/PR/merge. **Toàn task chưa hoàn tất**: cần Codex review độc lập.

### R3.0 Checkout và tiếp nhận

| Mục | Giá trị |
|---|---|
| Checkout tiếp nhận (nguồn review tiếp theo) | `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux-claude` |
| Branch / HEAD | `fix/p7-009-011-timezone-meal-ux` @ `f8e564dca39a27acdc5f7192e31dbb399d471216` (baseline đúng như bàn giao; chưa ghép main mới) |
| Cách tạo | `git clone --no-hardlinks --branch fix/p7-009-011-timezone-meal-ux <checkout Anti>`; sao chép byte-for-byte 43 tracked diff + 7 untracked từ checkout Anti (đối chiếu SHA-256 50/50 khớp; `git apply --check` của `git diff --binary f8e564d` cũng sạch, SHA-256 diff `5cd51e47c7f24c117233fb3ef7c3809094eb89a56a00f3b2bd85f4d84d539de6`). Không copy node_modules: `apps/mobile/node_modules` là junction tới `node_modules` của checkout Anti (gitignored, không có trong status). |
| Checkout Anti (chỉ đọc, giữ nguyên) | `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux`; kiểm lại sau R3: HEAD `f8e564d…`, 50 mục status (43 `M`, 7 `??`), 0 staged, 49/49 hash mục 3 khớp, handoff `B126C565…5BEE` không đổi. |
| Main khi tích hợp | Reviewer ghi nhận `origin/main` đã tới `57e27f8f40ed385c931979e5b35cff660b9f9fa8`. Patch này **chưa** đối chiếu với main mới; cần rebase/đối chiếu lại khi tích hợp (đặc biệt các file Nutrition/test dùng chung). |
| Evidence R3 (ngoài repo) | `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux-claude-evidence\` |

Phần đã đạt ở R3 review được giữ nguyên (không sửa): half-open version range, Push category/session/operation binding, offset nhãn lịch sử, `MealTypeSelector` (hash `24BFAD53…4EA` không đổi), default Việt Nam cho bản ghi mới, saved-intent replay, ngày cũ không có metadata timezone vẫn cần chọn context thủ công.

### R3.1 P2 `TZ1-picker-backend` — catalog picker lệch Backend

- **Nguyên nhân:** `getSupportedTimezoneOptions` lấy toàn bộ `moment.tz.names()` (597 ID, data `2026e`), trong khi mọi validator Backend (`NutritionBasicService.zone`, `NutritionTargetDayService.validZone`, `PushPreferenceService`, `AppointmentTimeResolver`, `WorkoutRecurrenceService`, `WorkoutScheduleBatchService`, `WorkoutScheduleChangeService`) dùng `ZoneId.getAvailableZoneIds().contains(timezone)` (+ `length <= 64` ở Nutrition/ScheduleChange). 6 ID `EST`, `MST`, `HST`, `ROC`, `GMT+0`, `GMT-0` có trong moment nhưng không có trong Java.
- **Sửa (không blacklist):**
  - `apps/mobile/scripts/timezone-catalog/BackendZoneIds.java` (chỉ Java standard library) in đúng tập `ZoneId.getAvailableZoneIds()` + `Runtime.version()`, vendor, phiên bản tzdb.
  - `apps/mobile/scripts/generate-backend-timezone-catalog.cjs` sinh `src/features/shared/backendTimezoneCatalog.ts`; `--check` đối chiếu và thoát `1` nếu lệch.
  - `isBackendSelectableTimezone` = thuộc catalog Backend **và** moment hiển thị được. Picker/extras chỉ đưa ra ID thỏa điều kiện này.
  - `isSupportedTimezone`, `formatTimezoneDisplay`, `resolveExistingRecordTimezone` giữ nguyên: ID lịch sử ngoài tập vẫn hiển thị nhãn thân thiện và không bị canonicalize/đổi, chỉ không được đề xuất làm lựa chọn mới.
- **Tập cuối đã kiểm:** Java `21.0.8+9-LTS` (Microsoft), tzdb `2025b`, 604 ID — trùng khít từng dòng với `C:\Users\ADMIN\.codex\tmp\timezone-meal-review-r3\java21-zone-ids.txt`. Giao với moment = **591** lựa chọn. Bị loại khỏi lựa chọn: 6 ID moment-only ở trên, cùng 13 ID Java-only `SystemV/*`; nhóm này Backend chấp nhận nhưng moment không có dữ liệu hiển thị, nên chỉ thu hẹp chứ không đưa ra ID bị từ chối. Alias hợp lệ (`Asia/Saigon`, `Pacific/Auckland`, `UTC`, `Etc/UTC`, `GMT`, `Etc/GMT+7`, `EST5EDT`…) và search thân thiện được giữ.
- **Giới hạn:** Backend Docker dùng `eclipse-temurin:21` (tzdb có thể khác `2025b`). Khi tích hợp/release, chạy `node scripts/generate-backend-timezone-catalog.cjs --check` bằng JRE đúng runtime triển khai. Không sửa Backend/OpenAPI/Flyway, không tạo endpoint.

### R3.2 P2 `TZ1-write-stale-handler` — callback Nutrition cũ vượt guard

- **Nguyên nhân:** `send`/handler kiểm `resolvedDayReady` từ closure của render cũ. Nếu giữ callback "Ghi món" khi scope A đã sẵn sàng rồi đổi actor hoặc ngày, closure vẫn thấy `true` và gọi `addItem` với ngày/context A. Ngoài ra, lock `submitting/busy` của op cũ có thể chặn hoặc bị mở khóa trong phiên mới. `request()` còn có `await tokenStorage.getAccessToken()` trước `fetch`, nên guard chỉ kiểm trước await là chưa đủ.
- **Sửa `NutritionScreen` và `NutritionStrategyScreen` (cùng pattern):**
  - Mỗi render tạo snapshot bất biến `opScope` gồm session symbol, actor, capability, (studentId), object `confirmedScope`, ngày và timezone. Session symbol mới khi actor/capability (và subject với Strategy) đổi, nên `A → B → A` cũng không khớp.
  - `liveScopeRef` cập nhật trong `useLayoutEffect` với scope đã commit + `ready` (+ `canPropose` với Trainer). `mountedRef` dùng cho unmount.
  - `send` so snapshot với scope sống **ngay trước dispatch**. Callback cũ thì im lặng (không đổi message/loading/context của phiên mới). Callback hiện tại nhưng chưa sẵn sàng thì giữ thông báo cũ. Với lệnh theo ngày, `command.day` phải khớp scope. `propose` cần `canPropose` sống (mất quyền làm callback cũ vô hiệu).
  - Token op riêng (`activeOpRef`): continuation chỉ cập nhật UI khi còn sở hữu op và scope còn sống; `finally` chỉ mở khóa nếu còn giữ token. Đổi session thì xóa lock/token, nên op cũ không giữ hay mở khóa lock mới.
  - **Await trước dispatch:** truyền `{ shouldContinue }` (cùng predicate sở hữu op + scope sống) qua tham số tùy chọn mới `MutationGuard` của `nutritionApi` tới cơ chế `shouldContinue` sẵn có trong `request()` (kiểm trước đọc token, trước `fetch`, trước retry 401). Caller không truyền guard thì options của request giữ nguyên.
- **Rà pattern liên quan:**
  - `FoodAnalysisScreen` đã remount theo actor/capability/status và có token `isCurrent`. Bổ sung `liveContextRef`: lệnh **mới** `handleGenerate`/`handlePreview`/`handleConfirm` từ render cũ (ngày đã đổi, preview đã bị vô hiệu) không phát. Retry intent đã lưu vẫn replay đúng body/key.
  - `TrainerNutritionScreen` chỉ đọc, không có mutation.
- **Replay:** retry giữ nguyên tham số payload (ngày/id + body/commandKey). Tham số thứ 3 là guard liveness theo lần dispatch (closure mới), không phải payload. Các test cũ so sánh retry trên `slice(0, -1)` và kiểm guard có mặt bằng `expect.objectContaining({ shouldContinue: expect.any(Function) })`. Harness reviewer ghi `args` rồi `structuredClone`/`deepEqual` cần bỏ tham số guard khi so payload.
- **Không tuyên bố hoàn tác server:** guard chỉ ngăn client phát hoặc tiếp tục request. Request đã tới server thì không được hoàn tác nhờ UI guard; outcome không rõ vẫn đi qua retry cùng key.

### R3.3 Kiểm chứng (thực chạy tuần tự trên checkout tiếp nhận, Node `v22.23.4`, Java `21.0.8+9-LTS`)

Chạy suite đơn lẻ trước; focused/typecheck/lint chạy sau khi phiên Claude P7-012 báo đã nhường lượt kiểm tra nặng.

| Kiểm tra | Lệnh (cwd `apps/mobile` trừ khi ghi khác) | Kết quả | Log |
|---|---|---|---|
| Regression R3 **trước sửa** | `NODE_OPTIONS=--max-old-space-size=1024 npx jest src/__tests__/timezoneMealReviewR3Regressions.test.tsx --runInBand --testTimeout=30000` | **FAIL 13/15**, đúng nguyên nhân: 4 catalog (ID bị Backend từ chối, extra `EST`, search `greenwich`), 6 Nutrition (handler cũ sau đổi actor/ngày/timezone vẫn `addItem`; thiếu guard transport; lock A chặn phiên B; retry cũ sau đổi ngày vẫn gửi), 3 Strategy (`chooseDayType` cũ sau đổi ngày/actor; `proposeStrategy` cũ sau mất quyền). 2 PASS là metadata catalog và retry Strategy. | `r3-pre-fix-fail.log` |
| FoodAnalysis R3 **trước sửa** | `… npx jest src/__tests__/foodAnalysisScreen.test.tsx -t "R3 retained" --runInBand` | **FAIL 1/1** (Confirm cũ gửi sau đổi ngày) | `r3-food-analysis-pre-fix-fail.log` |
| Regression R3 sau sửa | như trên | **15/15 PASS** | `r3-post-fix.log` |
| FoodAnalysis sau sửa | `… npx jest src/__tests__/foodAnalysisScreen.test.tsx --runInBand` | **63/63 PASS** | `r3-food-analysis-post-fix.log` |
| Nutrition suites trước khi cập nhật arity | 7 suite Nutrition/timezone | 9 FAIL, chỉ do `toHaveBeenCalledWith(day, body)` thiếu guard và retry `calls[1]` vs `calls[0]` có closure mới; đã cập nhật như mục R3.2 | `nutrition-suites-first.log`, `nutrition-suites-second.log` (24/24) |
| Typecheck | `NODE_OPTIONS=--max-old-space-size=1280 npx tsc --noEmit` | **PASS** (`TSC_EXIT=0`) | `typecheck.log` |
| Focused Jest (24 suite lịch sử + R3 + `nutritionApi` + `nutritionStrategyApi`) | `NODE_OPTIONS=--max-old-space-size=1280 npx jest <27 file> --runInBand --testTimeout=30000` | **27/27 suites, 291/291 tests PASS** (61.2 s) | `focused-jest.log` |
| Lint | `npm run lint` | **PASS**, 0 errors, đúng 5 warning cũ `no-require-imports` ở `CurrentGoalScreen.test.tsx` (2126/2169/2227/2277/2328) | `lint.log` |
| UX docs | (repo root) `node tools/validate-ux-docs.mjs` | **PASS** (18 required files) | `ux-docs.log` |
| Diff hygiene | (repo root) `git diff --check` | **PASS** (exit 0); file untracked mới không có trailing whitespace | `diff-check.log` |
| Catalog Backend | `node scripts/generate-backend-timezone-catalog.cjs --check` | **PASS**: khớp Java 21.0.8+9-LTS (Microsoft), tzdb 2025b, 604 ID | `catalog-check.log` |

- Evidence lịch sử `24 suites / 263 tests` (Anti) giữ nguyên ở mục 2; số mới ở trên chỉ áp dụng cho source cuối của checkout tiếp nhận.
- Race test dùng deferred promise và `notifyManager.setScheduler` đồng bộ, không sleep. `beforeEach` gọi `mockReset` cho API mock để không rò `mockImplementationOnce` giữa các test.
- Không chạy full Mobile, emulator/Metro, Backend, Spring, Docker hay stack QA. Device/Web runtime: **`DEFERRED_FOR_RAM`**.

### R3.4 SHA-256 sau R3 (lịch sử; source cuối xem R4.4) (55 file source/test/doc/script trong checkout tiếp nhận, không gồm chính handoff)

```text
5F46C17B45BEC0E7E247F83D33818B7C9C47FCEF6E33CADE57AC00F3AA6DA3C7  apps/mobile/scripts/generate-backend-timezone-catalog.cjs
0689B712D7D9BA6E50B9C5FA486E9B6680A41504BCE9127C8CAAC2F806B20374  apps/mobile/scripts/timezone-catalog/BackendZoneIds.java
1950F4723D544029F628FD27C33DEDC543574F992A9F47CB048C1A77EBC2B270  apps/mobile/src/__tests__/appointmentScreens.test.tsx
B7C17AC1CBFB8F6CD1895BA06F2FA3D46CB8C74805B926DEA546F7FE296F1AA0  apps/mobile/src/__tests__/foodAnalysisScreen.test.tsx
77A71D0257751986751525C3BC7958761E3B13BEDF2EE8EDBCC59E937FCE21DE  apps/mobile/src/__tests__/nutritionApi.test.ts
A7371D085FC4E853DD75DF006E7EE187B418CB1EFC7BDDA5695F13F02E6DBFFA  apps/mobile/src/__tests__/nutritionScreens.test.tsx
F6F47D534AB31372B7A43CD88D08DA02361C48B7E33B893A9E942B9470FA2C5F  apps/mobile/src/__tests__/nutritionStrategyScreen.test.tsx
C431A75974EBD99054862813F8309E85D3AEDD0E4D33F9B2EDB3DDE79A75E918  apps/mobile/src/__tests__/pushSettingsScreen.test.tsx
FBD2668CC403D1C85D7CE975AD091369D8663239B93B8E7E0FAF329526FC9B67  apps/mobile/src/__tests__/studentChangeProposalScreens.test.tsx
83B47404E485252F0222FAFC4060736E29707AB5096AB00B810251D25A75A41E  apps/mobile/src/__tests__/studentDirectScheduling.test.tsx
5713A0CAC5C0E02BC9D59669908F730108F9D0BBF26AC5EE11BE5E168FF619A0  apps/mobile/src/__tests__/timezoneAndMealUx.test.tsx
E8CAE500BA22A1A9475437CC752B94CD1FB90948BDB9BDB1E8AF8FA9C36F5919  apps/mobile/src/__tests__/timezoneMealCodexRegressions.test.tsx
AE0C5477F6A9DC330795D2D48AAEB6EA31CCC248CAE6FCCF35C403CF7F73DA18  apps/mobile/src/__tests__/timezoneMealReviewR2Regressions.test.tsx
D166ED17457A6A9CF1F55A3FC5FDDE7F223C9BB0BF19A34858C8B899E15B6581  apps/mobile/src/__tests__/timezoneMealReviewR3Regressions.test.tsx
5397309ADE26616005180019F9B9FE9AE05585742F86A7BEB328364917D104FD  apps/mobile/src/__tests__/trainerChangeProposalScreens.test.tsx
E394970B506FC05E4FE6AEF3318B6A47736A824802B6A9A6C455E5E44D9757C3  apps/mobile/src/__tests__/trainerCreateScheduleProposal.test.tsx
553FC60B2E8C76EF3209D736FB103DDE013F702D1E72374EABDEAF71F8574389  apps/mobile/src/__tests__/trainerWorkoutScheduleScreen.test.tsx
0FCA2C681990A39652B75EF71391CC5BD9B271CEB74E4FD912090E7833367457  apps/mobile/src/__tests__/workoutScheduleChange.test.tsx
C05B9922584376D1467472A5CD2B2334D640BABAD2127D977790489A9B20ED04  apps/mobile/src/__tests__/workoutScheduleProposalScreens.test.tsx
1111A974C73979296C4048129524DFB23D064F1B65634E0C99AF2AD19DE0660F  apps/mobile/src/__tests__/workoutScheduleScreen.test.tsx
24BFAD53F17BE38785BB534634B9E4912ABF0B9D45E415BE968F1554B014B4EA  apps/mobile/src/components/ui/MealTypeSelector.tsx
E2380AA85D90877506E4E12F0D682590B04D5731FFDD9F1F54EE35B0AD00A312  apps/mobile/src/components/ui/TimezoneContextPicker.tsx
4255FDFC217572C92B04B482A1A062DF73F19138D973CA62DBB4886A19AF065A  apps/mobile/src/features/appointments/AppointmentScreens.tsx
E6C81DE980B770C8D72DA8F7239EA06F46477E22B731B1094EAF3FAFB81B747C  apps/mobile/src/features/attention/AttentionReviewScreen.tsx
0A737CE3FB410410E81840216A9000071B330D936D125D8910EBB6E9AE2BA24A  apps/mobile/src/features/auth/HomeScreen.tsx
42CAB7BD3C9DE8CD63428D80A59FEC5DB216A12F360B192F418C09F0608C78AF  apps/mobile/src/features/chat/PushSettingsScreen.tsx
2E04BA87E7BCA7754322FF9479ED9BDCBFB67DFCF1329D2E5129C8BA650EC1B3  apps/mobile/src/features/nutrition/FoodAnalysisScreen.tsx
6E95035410D4B5F52BA9B700347ED1773836A637515C0E7E98970FDBCFBCF4E6  apps/mobile/src/features/nutrition/NutritionScreen.tsx
249E9B96D4754542BF5B1548ABC27F4461F7A9A3D8942126C93D56F1026DEC0B  apps/mobile/src/features/nutrition/NutritionStrategyScreen.tsx
29293AA979D4A24AFCEC57676636A26206D6647A015402E8CAF03154A3097885  apps/mobile/src/features/nutrition/TrainerNutritionScreen.tsx
F401F1B5BC7A6FADE2798AE1C4F5D448B284018A8C812C4A1AF4A87DE5BD9740  apps/mobile/src/features/nutrition/nutritionApi.ts
A0AB211B79CC0EFE8EF9D18066DC33131714B0A7D968DBA35BE2DBD0C6CF13D7  apps/mobile/src/features/shared/backendTimezoneCatalog.ts
9DC8F27121116C47DCA801A8A6FEEB5FB37E0F1B255E5F9E2B4DB59273C4A02B  apps/mobile/src/features/shared/timezoneUtils.ts
55AB8DEA51FF075AE8784F336F0B237185B36E98305290B8684984FE4FFA656B  apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx
2FDAAF98831E242E6F18700F042592C8594EF5B5175B2E2F5204CF99D2C59837  apps/mobile/src/features/workout-recurrence/RecurrenceCreateScreen.tsx
252F88A4E918DFE23F91B3A10C1FD89FD269F5EFE77D8C94992401623B0ABA58  apps/mobile/src/features/workout-recurrence/RecurrenceDetailScreen.tsx
DAE7B8FAAB24634DD99B45692CC4E57E0CA47C06C7E1AF4B22D9403198F8010B  apps/mobile/src/features/workout-recurrence/RecurrenceListScreen.tsx
25AE892B2577FB868964645A633A04983EC3F3AEB722B8006899E43D84B60F9D  apps/mobile/src/features/workout-recurrence/recurrenceForm.ts
E725BD474C37CF3CB083D131663E570BDE86945A6FC6EBE0BA90E08D844E8DF5  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen.tsx
9FE9250225E41B743547264721D0D0955A4050AC3BBEEFD6B9DE7AB337661492  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalsScreen.tsx
CD847FEB2D4008EAC49877D508E28E9D4CF72228845B8FB697A9E8546F29D4AF  apps/mobile/src/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen.tsx
61291B7B6E2D145F8D8579128D8C41829C04DA2E47037030D5232DC5E4031E89  apps/mobile/src/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen.tsx
B5EDB0AF0D550FE5E29A0EECB9A32E95CB8FE0E9F756A9C0B4AE41EE4BE20317  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen.tsx
66031648CAA84DD48EF5D08356DA4DE7AFF27903D759EAC9E3927B2427F9CF01  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalsScreen.tsx
69D6DED09433659D35AC3E6B4CBFFA59373EDA30737B1838E129F2C629CA9E2E  apps/mobile/src/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx
ED42E0CC53A61AF1FD79A575FBCFA4975762CFB534CA8AB6C84780F59AA5CB24  apps/mobile/src/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen.tsx
C4E1D80D2AC6A5FE318819C3945DD84C3EA0C23E0AF58CF236F716388001CD82  apps/mobile/src/features/workout-schedule-proposals/proposalPresentation.ts
4B9A164296A9712FADE6C249B1BCD8149D488F8DC07A6D8A4FE623662FBDE2C4  apps/mobile/src/features/workout-schedule/StudentConfirmBatchScreen.tsx
252979C8979218E152729DFFB045BC339A14F3C69AA2D2AD92387D4048E145B8  apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx
0BA8D311F26DE8081CE29B495B5640AE7B2B129C702D900F4031C78AEEEE9FBA  apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx
E08DB0706D290C749C8AFC8CAB18C9C4572597992AE8804AC07DE93F3225CC20  apps/mobile/src/features/workout-schedule/directBatchValidation.ts
548E7808AA20E19FB85B5B05E3CAE6C5812E65B9C368AAE8A7E09FE32C724201  apps/mobile/src/features/workout-schedule/scheduleChange.ts
20470ED5AE91D9B2E27725A2021BF5BD9B0C441E45168CE938D85D72E38B632E  apps/mobile/src/features/workout-schedule/scheduleDisplay.ts
978EB6CBC3250FFC94FEA610E1D51D1EF269122CD9200911B9461A353DCC5720  apps/mobile/src/services/aiProgressSummaryApi.ts
0164D649AF638673B169E9AB1A7D05CDA9153D0CC98DC297A295086E9A2C8B49  docs/06-ui-ux/component-specifications.md
```

16 file khác bản Anti: `generate-backend-timezone-catalog.cjs`, `BackendZoneIds.java`, `backendTimezoneCatalog.ts`, `timezoneUtils.ts`, `nutritionApi.ts`, `NutritionScreen.tsx`, `NutritionStrategyScreen.tsx`, `FoodAnalysisScreen.tsx`, `component-specifications.md`, cùng các test `timezoneMealReviewR3Regressions`, `foodAnalysisScreen`, `nutritionApi`, `nutritionScreens`, `nutritionStrategyScreen`, `timezoneMealCodexRegressions`, `timezoneMealReviewR2Regressions`.

---

## Lịch sử: bàn giao của Antigravity sau Review R2 (giữ nguyên để đối chiếu)

- **Checkout:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-009-011-timezone-meal-ux`
- **Nhánh:** `fix/p7-009-011-timezone-meal-ux` (baseline `main` @ `f8e564dca39a27acdc5f7192e31dbb399d471216`, PR #176)
- **Thống kê git hiện tại:** `50` tệp trong working tree (`43` modified `M`, `7` untracked `??`, `0` staged/committed).
- **Trạng thái hoàn thiện:** `MealTypeSelector.tsx` (P7-009) giữ nguyên bản đã đạt (`24BFAD53F17BE38785BB534634B9E4912ABF0B9D45E415BE968F1554B014B4EA`). Đã xử lý đủ 5 P2 của Review R2 (`TZ1-picker`, `TZ1-write`, `TZ1-TZ3-range`, `TZ2-binding`, `TZ4-offset`) sẵn sàng review lại.

## 0. Mô hình chung về Ngữ cảnh (Context) & Xác nhận (Confirmation) cho 5 P2

1. **Ngữ cảnh lịch & bản ghi mới vs. ngữ cảnh đã lưu (`TZ1-TZ3-range`):**
   - Tách rạch ròi 3 khái niệm:
     1. **Định danh ngày cụ thể (`targetDay`)**: `resolveStrategyGoalTimezone(goal, targetDay)` chỉ lấy timezone từ phiên bản Strategy `HALF_OPEN` **thực sự phủ ngày đó** (`effectiveFrom <= targetDay < effectiveUntil`, ưu tiên `versionNumber` cao nhất). Nếu không có phiên bản nào phủ `targetDay`, trả `null` (`unknown`), tuyệt đối không fallback sang phiên bản mới nhất (`ENDED` tháng 1–2 không áp sang tháng 11).
     2. **Cấu trúc mục tiêu (`CHANGE_TARGET`)**: tách riêng `resolveLatestStrategyVersionTimezone(goal)` cho `NutritionStrategyScreen` khi sửa cấu trúc mục tiêu.
     3. **Tác vụ tạo mới không có ngữ cảnh phủ**: mặc định `Asia/Ho_Chi_Minh` (`resolveNewRecordDefaultTimezone`). Bản ghi cũ giữ nguyên định danh IANA gốc (`resolveExistingRecordTimezone`).
2. **Cổng trạng thái tải & chặn ghi khi ngữ cảnh chưa giải quyết (`TZ1-write`):**
   - Thao tác ghi theo ngày (`addItem`, `addCatalogItem`, `confirm`, `correct`, `chooseDayType`, `chooseOverride`, `correctFrozenTarget`) chỉ hợp lệ khi `(actor, subject, day, timezone)` đã xác nhận đúng scope **và** truy vấn ngày cho đúng scope đó đã hoàn tất thành công (`!isPending && !isFetching && !isError && data.date === day && data.timezone === timezone` và khớp chủ thể).
   - Khi đang tải, `409 NUTRITION_TIMEZONE_CONFLICT`, lỗi hoặc đổi lựa chọn chưa tải xong `200 OK`, mọi button và handler ghi đều bị chặn; cache cũ của scope/ngày khác không mở khóa ghi.
3. **Danh mục & tìm kiếm thân thiện đầy đủ (`TZ1-picker`):**
   - Backend kiểm `timezone.length <= 64 && ZoneId.getAvailableZoneIds().contains(timezone)` và so khớp chuỗi exact (`timezone.equals(...)`), độc lập với quyền Strategy.
   - `TimezoneContextPicker` nạp toàn bộ danh mục TZDB sẵn có từ `moment-timezone` (`moment.tz.names()`, `length <= 64`, giữ nguyên alias như `Asia/Saigon`, `Pacific/Auckland`, `UTC`) với nhãn tiếng Việt thân thiện và ô tìm kiếm theo thành phố/quốc gia/khu vực (không hiển thị mã IANA thô, không yêu cầu biết IANA để tìm/chọn).
4. **Ràng buộc danh tính bất biến cho xác nhận Push (`TZ2-binding`):**
   - `PushSettingsScreen` remount `PushSettingsSession` theo `key={`${user?.id ?? ''}:${activeCapability ?? ''}:${status ?? ''}`}` và gắn mọi callback xác nhận/lưu với snapshot `(sessionToken, contextVersion, actor, role, category, timezone, quietHoursStart, quietHoursEnd)` + `activePushOpRef` duy nhất.
   - Xác nhận cấp riêng từng `category`: xác nhận `CHAT` không cho phép bật `WORKOUT`. Đổi category/context/actor/capability/unmount vô hiệu hóa toàn bộ callback cũ (kể cả `A → B → A` và đổi capability rồi quay lại); `success`/`error`/`finally` của operation cũ không mở khóa hoặc ghi đè operation mới. Giữ nguyên opt-out (`enabled: false`) và setting đã lưu, không thêm retry ngầm.
5. **Nhãn hiển thị timezone không dùng offset của đồng hồ hiện tại (`TZ4-offset`):**
   - `formatTimezoneDisplay(timezone, emptyLabel, at?)` bỏ offset theo `now()` cho nhãn chung/lịch sử khi không truyền instant `at`. Khi có instant mô tả sự kiện (`formatScheduleTime`, `formatProposalInstant`, `AttentionReviewScreen.localDate`), truyền `at = instant` để tính đúng offset theo mùa tại thời điểm đó (Auckland tháng 7 `+12:00`, tháng 12 `+13:00`; New York tháng 7 `-04:00`, tháng 12 `-05:00`; London tháng 7 `+01:00`, tháng 12 `+00:00`) mà không sửa ID/timestamp lịch sử.

## 1. Chi tiết xử lý từng P2 & Giới hạn Backend

| P2 ID | Trạng thái | Nguyên nhân gốc & Sửa đổi |
|---|---|---|
| **TZ1-picker** (`TimezoneContextPicker`, `timezoneUtils`) | **ĐÃ SỬA** | Bỏ giới hạn 9 zone ngắn; `getSupportedTimezoneOptions` nạp toàn bộ TZDB hợp lệ từ `moment-timezone` (kèm alias như `Asia/Saigon`, các vùng ngoài shortlist như `Pacific/Auckland`, `America/Argentina/Buenos_Aires`) với nhãn tiếng Việt không chứa `/`, kèm ô tìm kiếm thân thiện theo thành phố/quốc gia/khu vực (`testID="...-search-input"`). Hoạt động độc lập không cần quyền Strategy. |
| **TZ1-write** (`NutritionScreen`, `NutritionStrategyScreen`, `TrainerNutritionScreen`, `FoodAnalysisScreen`) | **ĐÃ SỬA** | Tách `confirmedScope` theo đúng `(actor, studentId, day, timezone)` và cổng `resolvedDayReady` / `resolvedTargetDayReady` đòi hỏi truy vấn ngày hoàn tất `200 OK`, `!isFetching`, `!isError`, khớp chủ thể/ngày/timezone. Chặn cả `disabled` lẫn handler trực tiếp sau `409` chưa phục hồi hoặc khi chưa load xong; đồng bộ kiểm tra ngày khớp bản nháp tại `FoodAnalysisScreen`. |
| **TZ1-TZ3-range** (`timezoneUtils`, `NutritionScreen`, `TrainerNutritionScreen`, `NutritionStrategyScreen`) | **ĐÃ SỬA** | `resolveStrategyGoalTimezone(goal, targetDay)` kiểm chặt `[effectiveFrom, effectiveUntil)` trên `HALF_OPEN` (ưu tiên `versionNumber` cao nhất) và trả `null` khi không có version phủ ngày — không fallback về version mới nhất. Tách riêng `resolveLatestStrategyVersionTimezone` cho `CHANGE_TARGET`. Ngày Basic/Strategy mới tháng 11 không kế thừa timezone từ Strategy kết thúc tháng 2 và mặc định `Asia/Ho_Chi_Minh`. |
| **TZ2-binding** (`PushSettingsScreen`) | **ĐÃ SỬA** | Gắn `confirmations` riêng từng `category`, kiểm `sessionToken`, `contextVersion`, `actor`, `role`, `timezone`, `quietHoursStart/End` trên mọi callback, remount khi đổi `actor`/`capability`/`status`, chặn stale async `pushApi.set` ghi đè operation mới qua `activePushOpRef`. Giữ opt-out `enabled: false` cho setting đã lưu. |
| **TZ4-offset** (`timezoneUtils`, `scheduleDisplay`, `proposalPresentation`, `AttentionReviewScreen`) | **ĐÃ SỬA** | `formatTimezoneDisplay` không dùng `moment.tz(tz).format('Z')` theo `now()` cho nhãn chung; chỉ gắn `(UTC±HH:MM)` khi caller truyền instant `at` hợp lệ (`formatScheduleTime`, `formatProposalInstant`, `AttentionReviewScreen.localDate`). Giữ nguyên ID và timestamp lịch sử. |

- **Giới hạn Backend giữ nguyên (không đổi shared contract):** `GET /api/v1/nutrition/days/{day}?timezone=...` và `GET /api/v1/students/{studentId}/nutrition/days/{day}?timezone=...` (`NutritionBasicController.java:78-90`) vẫn yêu cầu `timezone` từ client; `NutritionBasicService.java:338-340` và `NutritionBasicErrorAdvice.java:27-28` ném `409 NUTRITION_TIMEZONE_CONFLICT` với message chung không trả múi giờ đã lưu; `NutritionStrategyService.java:110-119` đòi quyền `NUTRITION_GOAL VIEW` và Coaching `RelationshipDetail` không mang `student.timezone`. Vì vậy, với ngày log rời rạc không gắn Strategy (hoặc Trainer thiếu `NUTRITION_GOAL VIEW`), **vẫn cần người dùng chọn context thủ công qua `TimezoneContextPicker`**, không phải tự khám phá từ Backend.

## 2. Chứng cứ kiểm chứng

- **Evidence FAIL trước khi sửa (R2):** [timezoneMealReviewR2Regressions.test.tsx](file:///C:/VScode/CodePTIT/Đồ%20Án%20Tốt%20Nghiệp/p7-009-011-timezone-meal-ux/apps/mobile/src/__tests__/timezoneMealReviewR2Regressions.test.tsx) fail **`5/5`** case (`P2-1` thiếu `Pacific/Auckland`, `P2-2` nút `Ghi món` không bị disable sau `409`, `P2-3` trả `America/New_York` thay `null`, `P2-4` `pushApi.set` gọi 2 lần `CHAT`+`WORKOUT` chỉ sau 1 xác nhận, `P2-5` `formatTimezoneDisplay('Pacific/Auckland')` in `UTC+13:00`). Log lưu ngoài repo tại `C:\Users\ADMIN\.gemini\antigravity\brain\54641747-5b2f-4046-b943-b7a30f583931\scratch\pre-fix-r2-regressions-fail.log`. Các log FAIL gốc của Codex tại `C:\Users\ADMIN\.codex\tmp\timezone-meal-independent\*` và `C:\Users\ADMIN\.codex\tmp\timezone-meal-review-r2\*` được bảo toàn nguyên vẹn.
- **Kiểm tra tuần tự sau lần sửa code cuối cùng:**
  - `tsc --noEmit`: **PASS** (`TSC_EXIT=0`).
  - Focused Jest (`24` suites gồm `timezoneMealReviewR2Regressions.test.tsx`, `timezoneMealCodexRegressions.test.tsx`, `timezoneAndMealUx.test.tsx`, `nutritionScreens.test.tsx`, `foodAnalysisScreen.test.tsx`, `nutritionStrategyScreen.test.tsx`, `attentionReviewScreen.test.tsx`, `pushSettingsScreen.test.tsx`, `appointmentScreens.test.tsx`, `studentDirectScheduling.test.tsx`, `trainerCreateScheduleProposal.test.tsx`, `workoutScheduleChange.test.tsx`, `trainerChangeProposalScreens.test.tsx`, `studentChangeProposalScreens.test.tsx`, `trainerWorkoutScheduleScreen.test.tsx`, `workoutScheduleProposalScreens.test.tsx`, `workoutScheduleScreen.test.tsx`, `workoutRecurrenceScreens.test.tsx`, `workoutRecurrence.test.ts`, `dateInputs.test.tsx`, `accessibilityAndTokens.test.tsx`, `aiProgressSummaryApi.test.ts`, `AiWorkoutProposalModal.test.tsx`, `ProgressSummaryScreen.test.tsx`): **24 passed, 24 total; 263 passed, 263 total**, 0 failed, 0 skipped (`47.405 s`).
  - ESLint (`npm run lint` / `expo lint`): **PASS** (`LINT_EXIT=0`, `0 errors`; giữ đúng 5 warning `no-require-imports` có sẵn tại `CurrentGoalScreen.test.tsx`).
  - UX Docs (`node tools/validate-ux-docs.mjs`): **PASS** (`UX documentation validation passed (18 required files).`).
  - Diff hygiene (`git diff --check`): **PASS** (`DIFF_CHECK_EXIT=0`).
  - Device/Web runtime QA & Backend/Docker: **`DEFERRED_FOR_RAM`** (không khởi chạy).

## 3. Danh sách & SHA-256 (snapshot checkout Anti trước R3 — lịch sử, không phải source cuối)

```text
1950F4723D544029F628FD27C33DEDC543574F992A9F47CB048C1A77EBC2B270  apps/mobile/src/__tests__/appointmentScreens.test.tsx
748363BBAC58886C460A8A1FFCABA9ECEEB69593D8FA85A18E7E0AE91129D582  apps/mobile/src/__tests__/foodAnalysisScreen.test.tsx
2185C6C57FBE557716EA228553BB0E258BF06E43B7662825D5764A984DF8065A  apps/mobile/src/__tests__/nutritionScreens.test.tsx
C63441A90E55F4D1B8865E47F2F855D3129CABFD86AD6F21B42B99325256112E  apps/mobile/src/__tests__/nutritionStrategyScreen.test.tsx
C431A75974EBD99054862813F8309E85D3AEDD0E4D33F9B2EDB3DDE79A75E918  apps/mobile/src/__tests__/pushSettingsScreen.test.tsx
FBD2668CC403D1C85D7CE975AD091369D8663239B93B8E7E0FAF329526FC9B67  apps/mobile/src/__tests__/studentChangeProposalScreens.test.tsx
83B47404E485252F0222FAFC4060736E29707AB5096AB00B810251D25A75A41E  apps/mobile/src/__tests__/studentDirectScheduling.test.tsx
5397309ADE26616005180019F9B9FE9AE05585742F86A7BEB328364917D104FD  apps/mobile/src/__tests__/trainerChangeProposalScreens.test.tsx
E394970B506FC05E4FE6AEF3318B6A47736A824802B6A9A6C455E5E44D9757C3  apps/mobile/src/__tests__/trainerCreateScheduleProposal.test.tsx
553FC60B2E8C76EF3209D736FB103DDE013F702D1E72374EABDEAF71F8574389  apps/mobile/src/__tests__/trainerWorkoutScheduleScreen.test.tsx
0FCA2C681990A39652B75EF71391CC5BD9B271CEB74E4FD912090E7833367457  apps/mobile/src/__tests__/workoutScheduleChange.test.tsx
C05B9922584376D1467472A5CD2B2334D640BABAD2127D977790489A9B20ED04  apps/mobile/src/__tests__/workoutScheduleProposalScreens.test.tsx
1111A974C73979296C4048129524DFB23D064F1B65634E0C99AF2AD19DE0660F  apps/mobile/src/__tests__/workoutScheduleScreen.test.tsx
4255FDFC217572C92B04B482A1A062DF73F19138D973CA62DBB4886A19AF065A  apps/mobile/src/features/appointments/AppointmentScreens.tsx
E6C81DE980B770C8D72DA8F7239EA06F46477E22B731B1094EAF3FAFB81B747C  apps/mobile/src/features/attention/AttentionReviewScreen.tsx
0A737CE3FB410410E81840216A9000071B330D936D125D8910EBB6E9AE2BA24A  apps/mobile/src/features/auth/HomeScreen.tsx
42CAB7BD3C9DE8CD63428D80A59FEC5DB216A12F360B192F418C09F0608C78AF  apps/mobile/src/features/chat/PushSettingsScreen.tsx
8F1A30788A3A61AA6EBC9C91617B9F9552F930902CC305B724D1A09B5DC4483C  apps/mobile/src/features/nutrition/FoodAnalysisScreen.tsx
C4D2BB5BA7909C39827E7FBB3BB68B6B7D86F40AAF4083F03FA091165FDFE863  apps/mobile/src/features/nutrition/NutritionScreen.tsx
798067F2A0EBB97B306D186A17D08C54457F870FC2E8EDD3D77E578BFA5AF01B  apps/mobile/src/features/nutrition/NutritionStrategyScreen.tsx
29293AA979D4A24AFCEC57676636A26206D6647A015402E8CAF03154A3097885  apps/mobile/src/features/nutrition/TrainerNutritionScreen.tsx
55AB8DEA51FF075AE8784F336F0B237185B36E98305290B8684984FE4FFA656B  apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx
2FDAAF98831E242E6F18700F042592C8594EF5B5175B2E2F5204CF99D2C59837  apps/mobile/src/features/workout-recurrence/RecurrenceCreateScreen.tsx
252F88A4E918DFE23F91B3A10C1FD89FD269F5EFE77D8C94992401623B0ABA58  apps/mobile/src/features/workout-recurrence/RecurrenceDetailScreen.tsx
DAE7B8FAAB24634DD99B45692CC4E57E0CA47C06C7E1AF4B22D9403198F8010B  apps/mobile/src/features/workout-recurrence/RecurrenceListScreen.tsx
25AE892B2577FB868964645A633A04983EC3F3AEB722B8006899E43D84B60F9D  apps/mobile/src/features/workout-recurrence/recurrenceForm.ts
E725BD474C37CF3CB083D131663E570BDE86945A6FC6EBE0BA90E08D844E8DF5  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen.tsx
9FE9250225E41B743547264721D0D0955A4050AC3BBEEFD6B9DE7AB337661492  apps/mobile/src/features/workout-schedule-change-proposals/StudentChangeProposalsScreen.tsx
CD847FEB2D4008EAC49877D508E28E9D4CF72228845B8FB697A9E8546F29D4AF  apps/mobile/src/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen.tsx
61291B7B6E2D145F8D8579128D8C41829C04DA2E47037030D5232DC5E4031E89  apps/mobile/src/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen.tsx
B5EDB0AF0D550FE5E29A0EECB9A32E95CB8FE0E9F756A9C0B4AE41EE4BE20317  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen.tsx
66031648CAA84DD48EF5D08356DA4DE7AFF27903D759EAC9E3927B2427F9CF01  apps/mobile/src/features/workout-schedule-proposals/StudentScheduleProposalsScreen.tsx
69D6DED09433659D35AC3E6B4CBFFA59373EDA30737B1838E129F2C629CA9E2E  apps/mobile/src/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx
ED42E0CC53A61AF1FD79A575FBCFA4975762CFB534CA8AB6C84780F59AA5CB24  apps/mobile/src/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen.tsx
C4E1D80D2AC6A5FE318819C3945DD84C3EA0C23E0AF58CF236F716388001CD82  apps/mobile/src/features/workout-schedule-proposals/proposalPresentation.ts
4B9A164296A9712FADE6C249B1BCD8149D488F8DC07A6D8A4FE623662FBDE2C4  apps/mobile/src/features/workout-schedule/StudentConfirmBatchScreen.tsx
252979C8979218E152729DFFB045BC339A14F3C69AA2D2AD92387D4048E145B8  apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx
0BA8D311F26DE8081CE29B495B5640AE7B2B129C702D900F4031C78AEEEE9FBA  apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx
E08DB0706D290C749C8AFC8CAB18C9C4572597992AE8804AC07DE93F3225CC20  apps/mobile/src/features/workout-schedule/directBatchValidation.ts
548E7808AA20E19FB85B5B05E3CAE6C5812E65B9C368AAE8A7E09FE32C724201  apps/mobile/src/features/workout-schedule/scheduleChange.ts
20470ED5AE91D9B2E27725A2021BF5BD9B0C441E45168CE938D85D72E38B632E  apps/mobile/src/features/workout-schedule/scheduleDisplay.ts
978EB6CBC3250FFC94FEA610E1D51D1EF269122CD9200911B9461A353DCC5720  apps/mobile/src/services/aiProgressSummaryApi.ts
E177F012515B00837C4A25208ED704A1F8FBA15025AED8E89AD2F1199C918E74  docs/06-ui-ux/component-specifications.md
5713A0CAC5C0E02BC9D59669908F730108F9D0BBF26AC5EE11BE5E168FF619A0  apps/mobile/src/__tests__/timezoneAndMealUx.test.tsx
08ECD86431BDB194A137C2B96126735D76145C6FA8B3F37B1ED8AB5F1634D468  apps/mobile/src/__tests__/timezoneMealCodexRegressions.test.tsx
374BE6F5F7B28C6F0CC24F8C9B0CCF50B86FF7E999D363DF84EB33883CFE10A6  apps/mobile/src/__tests__/timezoneMealReviewR2Regressions.test.tsx
24BFAD53F17BE38785BB534634B9E4912ABF0B9D45E415BE968F1554B014B4EA  apps/mobile/src/components/ui/MealTypeSelector.tsx
E2380AA85D90877506E4E12F0D682590B04D5731FFDD9F1F54EE35B0AD00A312  apps/mobile/src/components/ui/TimezoneContextPicker.tsx
C3EB1233B54527C6C9A22DE3FEEDF8F8C23931990567224075442AE8C62EB2CA  apps/mobile/src/features/shared/timezoneUtils.ts
```


## R5 independent review and integration — 2026-11-27

- R5 finding **FIXED**; no remaining blocking finding in reviewed scope. The two production files change only the approved AbortError message/comments versus hash-verified R4. Guard A/B/C, dispatch conditions, operation ownership, payloads, keys and replay behavior remain unchanged. Prior R4 FAIL evidence is preserved outside Git.
- Independent reproduction (actual screens, nutritionApi and apiClient; storage/network mocks): **6/6 PASS**. In both Nutrition and Strategy: (1) one POST followed by transport AbortError yields uncertain-outcome copy and exact-key/body retry; (2) token read pending + same-scope GET 409 yields zero POST and no deadlock, with exact intent retry after readiness recovery; (3) a previously sent uncertain intent whose replay is stopped before fetch remains uncertain, does not resend automatically, and later replays the original URL/body/key. No assertion requires same-scope UI to remain unchanged when releasing the current operation lock.
- Source: Claude checkout remains read-only at `f8e564dca39a27acdc5f7192e31dbb399d471216`; verified 56 manifest hashes plus handoff SHA-256 `42de0c2e9a3ee0830f717fb290bbeb3da4589de95b778c8d72e3fbada07247d8`. Catalog/generator, meal selector, Push, version range and historical offset retain their previously accepted hashes; no catalog or whole legacy harness rerun.
- Remote main verified at `5fbf931c3b83b51ed6ba6342f5d40263e46d96bf`; PR #179 independently confirmed MERGED. No action on PR #179 or its checkout. Integration branch: `codex/fix-p7-009-011-timezone-meal-ux`; separate checkout `D:\codex-review\timezone-meal-integration-r5`.
- Applied only the tracked per-path diff from the Claude baseline, then the manifest's untracked files. Patch applied cleanly; no logic/contract conflict or manual product edit. All 57 incoming paths matched reviewed source bytes before this handoff extension. Two path copies restored only source line endings after git apply (`PushSettingsScreen.tsx`, `TrainerNutritionScreen.tsx`). All 31 P7-016 paths were compared against main Git blobs and preserved; no overlapping ownership paths. The 56 hashes in R5.4 remain the integration source/test/doc/script hashes; this handoff is not self-hashed.
- Reused own review dependencies for the narrow reproduction. Main's lockfile differs from the old `f8e564d` review lockfile (font/QR integration), so a separate clean npm ci was necessary in the fresh main integration checkout; no npm ci through Claude/Anti junctions, and no reinstall in the old review checkout.
- Focused main+patch test, typecheck/targeted lint, UX docs and staged-check results are appended before checkpoint. Full Mobile/Backend are assigned to new-head CI. No local Backend/Docker/Metro/browser/emulator or runtime/DB activity.
- Limits remain: manual context selection when Backend does not reveal the historical timezone; SystemV IDs not supported by moment; catalog provenance is a specific JDK/tzdb, not a guarantee for every deployed JRE; device/Web/visual QA **DEFERRED_FOR_RAM**. No release completion claim. P7-016 and its follow-ups remain separate.
- Independent evidence and timeline: `D:\codex-review\timezone-meal-r5-evidence`; original FAIL evidence remains in `timezone-meal-r4-rereview` and earlier evidence directories. Do not interpret historical R4 "server never received/not sent" commentary as R5 behavior: R5 always states uncertain outcome for AbortError.


### Final local integration verification

- Clean `npm ci --no-audit --no-fund` in the independent main checkout: exit 0; heap 384 MB; main package/lock unchanged.
- `NODE_OPTIONS=--max-old-space-size=1024 npm test -- --runInBand --testTimeout=30000 timezoneMealReviewR4Regressions timezoneMealReviewR3Regressions timezoneMealReviewR2Regressions timezoneMealCodexRegressions timezoneAndMealUx nutritionScreens nutritionStrategyScreen`: **7 suites, 63/63 PASS**, exit 0, 29.676 s, on main `5fbf931` + reviewed timezone patch. Includes all 13 R5 regression cases. No rerun on the old baseline and no full local Mobile suite.
- `npm run typecheck`: exit 0 with heap 1024 MB.
- `npx --no-install eslint` on the two Nutrition screens and R4/R5 regression file: exit 0, no warnings. The old-baseline five-warning full-lint result is historical; current-main full lint is assigned to new-head CI (main already has 12 classified baseline warnings outside this patch).
- No extra product changes followed passing tests. Root UX docs, diff/staged checks run after this handoff edit; logs retained in independent evidence.
- Local heavy-test slot was handed back before checkpoint/CI operations. Source Claude/Anti/QA/P7-016 and runtime/DB were not modified. PR/merge/CI outcomes are recorded in the PR body, avoiding a status-only documentation commit.
