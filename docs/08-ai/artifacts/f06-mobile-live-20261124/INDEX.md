# F06 QA evidence publication

This checkpoint publishes selected original captures, sanitized runtime/HTTP/DB
JSON and regression logs. All are synthetic fixture data; not approved nutrition
facts or model-quality evidence. Original FAIL captures/logs are retained.
Login capture and repetitive intermediate/keyboard-dismiss captures are excluded
from Git but remain unchanged locally. The handoff references the full local
capture sequence; the selected published paths below substantiate each finding
and normal-flow result. Local temp scripts/config/logs (including private account
setup) are not publication artifacts.

- P7-019 FAIL: captures 20/26/28/29, original DB failure JSON and before log.
- P7-019 fix: 65/70/74/78/83/85/86/90 and p7019 manifest/logs.
- P7-021 FAIL: 81/82 and p7021 before log (9 failed/3 passed).
- P7-021 fix: 118/119/121/125/126/129/131/132/136 and p7021 DB/manifest/logs.
- Independent Anti report: review-checkpoint.json (owner-provided result).

Original `.log` files are ignored and stay local. Byte-identical `.log.txt`
exports below publish the reviewed logs without force-adding ignored files.
Historical manifests retain their original local log names. New P7-022 log exports normalize trailing whitespace only for diff checks; unchanged raw originals are preserved locally outside Git at C:/Users/ADMIN/.codex/tmp/f06-p7022-original-logs-20261125. FAIL results and content are retained.

## Published checkpoint files

- `device-preparation.json`
- `http-evidence.json`
- `metro-connection.json`
- `p7019-jest-after.log.txt`
- `p7019-jest-before.log.txt`
- `p7019-lint.log.txt`
- `p7019-typecheck.log.txt`
- `p7019-verification.json`
- `p7021-jest-after.log.txt`
- `p7021-jest-before.log.txt`
- `p7021-lint.log.txt`
- `p7021-typecheck.log.txt`
- `p7021-verification.json`
- `p7022-p1-delete-reject-jest-after.log.txt`
- `p7022-p1-delete-reject-jest-before.log.txt`
- `p7022-p1-delete-reject-lint.log.txt`
- `p7022-p1-delete-reject-typecheck.log.txt`
- `p7022-p1-native-storage-jest-after.log.txt`
- Local-only original `p7022-p1-native-storage-jest-before.log.txt` (fixture credential excerpts; not published).
- `p7022-p1-native-storage-lint.log.txt`
- `p7022-p1-native-storage-typecheck.log.txt`
- `p7022-p1-auth-and-f06-jest-after.log.txt`
- `p7022-p1-lint.log.txt`
- Local-only original `p7022-p1-session-race-jest-before.log.txt` (fixture credential excerpts; not published).
- `p7022-p1-typecheck.log.txt`
- `p7022-p7023-audit-jest-before.log.txt`
- `p7022-p7023-jest-after.log.txt`
- `p7022-p7023-jest-before.log.txt`
- `p7022-p7023-lint.log.txt`
- `p7022-p7023-typecheck.log.txt`
- `p7022-p7023-verification.json`
- `pixel7/p7022-failure-evidence.json`
- `pixel7/confirm-drop-after-commit-proxy.json`
- `pixel7/retest-p7022-p7023-db.json`
- `pixel7/followup-before-confirm-db.json`
- `pixel7/followup-fixture.json`
- `pixel7/followup-fixture-corrected.json`
- `pixel7/followup-offline-preview-db.json`
- `pixel7/followup-offline-confirm-db.json`
- `pixel7/followup-after-partial-confirm-db.json`
- Selected latest retest images: `pixel7/257-retest-p7022-401-banner.png`, `pixel7/259-retest-continue.png`, `pixel7/262b-round2-bottom.png`. The full 167–263 sequence stays local.
- `pixel7/01-expo-open-error.png`
- `pixel7/02-expo-error-log.png`
- `pixel7/02-expo-error-log.xml`
- `pixel7/08-f06-entry.png`
- `pixel7/08-f06-entry.xml`
- `pixel7/106-p7021-food.png`
- `pixel7/106-p7021-food.xml`
- `pixel7/111-p7021-save125.png`
- `pixel7/111-p7021-save125.xml`
- `pixel7/114-p7021-preview125.png`
- `pixel7/114-p7021-preview125.xml`
- `pixel7/115-p7021-preview125-view.png`
- `pixel7/115-p7021-preview125-view.xml`
- `pixel7/118-p7021-dirty200.png`
- `pixel7/118-p7021-dirty200.xml`
- `pixel7/119-p7021-dirty-bottom.png`
- `pixel7/119-p7021-dirty-bottom.xml`
- `pixel7/121-p7021-cancel.png`
- `pixel7/121-p7021-cancel.xml`
- `pixel7/122-p7021-cancel-new-preview.png`
- `pixel7/122-p7021-cancel-new-preview.xml`
- `pixel7/125-p7021-basis-dirty.png`
- `pixel7/125-p7021-basis-dirty.xml`
- `pixel7/126-p7021-basis-revert.png`
- `pixel7/126-p7021-basis-revert.xml`
- `pixel7/129-p7021-saved2.png`
- `pixel7/129-p7021-saved2.xml`
- `pixel7/131-p7021-preview2-view.png`
- `pixel7/131-p7021-preview2-view.xml`
- `pixel7/132-p7021-receipt.png`
- `pixel7/132-p7021-receipt.xml`
- `pixel7/136-p7021-nutrition-context.png`
- `pixel7/136-p7021-nutrition-context.xml`
- `pixel7/20-serving-basis.png`
- `pixel7/20-serving-basis.xml`
- `pixel7/26-dot-quantity.png`
- `pixel7/26-dot-quantity.xml`
- `pixel7/28-dot-save.png`
- `pixel7/28-dot-save.xml`
- `pixel7/29-dot-save-error.png`
- `pixel7/29-dot-save-error.xml`
- `pixel7/65-comma-after-fix.png`
- `pixel7/65-comma-after-fix.xml`
- `pixel7/70-comma-message.png`
- `pixel7/70-comma-message.xml`
- `pixel7/74-correct-saved.png`
- `pixel7/74-correct-saved.xml`
- `pixel7/78-preview-values.png`
- `pixel7/78-preview-values.xml`
- `pixel7/81-quantity150.png`
- `pixel7/81-quantity150.xml`
- `pixel7/82-save150-dismiss.png`
- `pixel7/82-save150-dismiss.xml`
- `pixel7/83-save150.png`
- `pixel7/83-save150.xml`
- `pixel7/85-preview150-values.png`
- `pixel7/85-preview150-values.xml`
- `pixel7/86-confirm-receipt.png`
- `pixel7/86-confirm-receipt.xml`
- `pixel7/90-nutrition-refetched.png`
- `pixel7/90-nutrition-refetched.xml`
- `pixel7/91-process-death-recents.png`
- `pixel7/91-process-death-recents.xml`
- `pixel7/92-resumed-after-kill-attempt.png`
- `pixel7/92-resumed-after-kill-attempt.xml`
- `pixel7/after-fix-comma-db.json`
- `pixel7/after-fix-confirm-db.json`
- `pixel7/after-fix-correct-db.json`
- `pixel7/after-fix-preview125-db.json`
- `pixel7/after-fix-receipts-db.json`
- `pixel7/connection-failure.json`
- `pixel7/device-db-after-correct-failure.json`
- `pixel7/ipv4-recheck.json`
- `pixel7/manual-open-gate.json`
- `pixel7/observed-form-values.json`
- `pixel7/p7021-baseline-db.json`
- `pixel7/p7021-confirm-db.json`
- `pixel7/p7021-dirty-db.json`
- `pixel7/p7021-final-counts-db.json`
- `pixel7/visible-restart.json`
- `review-checkpoint.json`
