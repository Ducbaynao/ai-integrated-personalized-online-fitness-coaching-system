# F03 runtime evidence repair — 15/11/2026

Branch `feat/p2-m4-f03-draft-edit-share`, base commit `6716f563db3e33ae126c926d62880eba209eba47` plus the uncommitted working tree. Runtime verification used the existing local Android emulator, Expo/Metro at port 8081 and Spring Boot dev at port 8080 with simulated F03 generation.

## Capture provenance

The original six `.png` files started with `FF FE FD FF` and could not be decoded as PNG. Each was replaced with a fresh emulator capture. Four additional screenshots document Trainer sharing and the Student read-only action area. Capture used `adb shell screencap -p /sdcard/<file>.png` followed by `adb pull`. The files were opened individually for visual inspection. [Manifest](manifest.json) records dimensions, byte counts, SHA-256 and PNG signature/chunk CRC/zlib validation.

## Scenarios observed in this pass

| Evidence | Method and result |
|---|---|
| [01 initial modal](gui_01_modal_initial.png) | SELF_DIRECTED Student opened F03 from the scheduled-workout screen. |
| [02 proposal v1](gui_02_proposal_v1.png) | Student generated a load-adjustment proposal through the UI, with baseline 20 KG and proposed 17.5 KG. |
| [03 edit form](gui_03_edit_mode.png) | Student opened the actual proposal edit form. |
| [04 edited fields](gui_04_student_form_edited.png) | Student entered title `Student GUI verified v2` and target 15 KG. |
| [05 saved diff](gui_05_student_diff_v2.png) | Save produced v2. After the rendering fix, the diff displayed `17.5 → 15` and `-2.5 → -5`. |
| [07 Trainer share v2](gui_07_trainer_shared_v2.png) | Trainer generated a separate template-scope proposal, edited it to title `Trainer_shared_v2`, target 14 KG, and clicked Share in the UI. Database shared revision became 2. |
| [08 private v3](gui_08_trainer_private_v3.png) | Trainer edited the same proposal to title `Trainer_private_v3`, target 13 KG. GUI warned that the Student still had snapshot v2. Database live revision was 3 and shared revision remained 2. |
| [Student isolation assertion](student-v2-after-private-v3.json) | Authenticated Student HTTP GET, while Trainer draft was v3, returned revision 2 and title `Trainer_shared_v2`. This intermediate isolation assertion was executed over HTTP. |
| [09 reshare v3](gui_09_trainer_reshared_v3.png) | Trainer clicked Reshare in the GUI. Database shared revision became 3. |
| [06 Student shared v3](gui_06_student_hc_shared_v3.png) | After login through the Student UI, the recommendation deep link displayed shared v3, title `Trainer_private_v3`, target 13 KG and the read-only banner. |
| [10 read-only actions](gui_10_student_read_only_actions.png) | Scrolled to the end of the Student proposal. Edit, Share, Accept and Reject were absent. [UI hierarchy](student-shared-v3-ui.xml) preserves the observed controls. |

Trainer proposal ID `f1beb691-cfc1-46f6-9524-633a195cc373` remained PENDING after this pass. The pass created/edited/shared local fixture proposals and did not Accept them or publish a new Workout Plan version. The Student proposal and Trainer template proposal are separate fixtures.

## Defect found and repaired

The GUI initially displayed `null → null` for real Backend diff fields. Backend returns `originalValue/customizedValue`, while Mobile rendered `before/after`. The existing edit test was changed to use the Backend response shape and assert displayed values. It failed before the fix and passed after the fix. Mobile now reads the Backend fields, preserves explicit null and supports the older response shape. Diff header/rows wrap within the card. The prescription list now has a stable memoized dependency and the unused test import was removed to resolve lint warnings.

## Executed checks

- Three targeted Mobile suites passed 89/89 tests, covering F03 component/API behavior and F04 progress-summary behavior.
- Mobile `tsc --noEmit` passed. Expo lint passed with zero warnings after the cleanup.
- Existing Surefire XML reports were inspected and record 105 F03, 58 F02 and 47 Workout Plan tests with zero failures/errors. Backend was not re-run during this evidence repair.
- The previously reported 13-step `scratch/run_full_live_test.py` run is HTTP/API and database evidence. It was not re-run in this pass and does not establish GUI actions.

## Remaining verification

F04 A → B → A means Student A → Student B → Student A while prior responses/rejections arrive late. Its component tests were executed and passed. The equivalent live Android GUI scenario with deliberately reordered network responses remains NOT RUN. Exercise Catalog Picker selection is a separate scenario. No Maestro/Appium requirement is introduced by this evidence record.

Specialist review D06/OD-3, real-provider quality/latency/cost measurements, F04 checkpoint and milestone sign-off remain open. Local authentication helper scripts are outside the proposed PR file set. Credentials and session tokens are excluded from this evidence package.
