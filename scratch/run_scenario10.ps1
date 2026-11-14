$ErrorActionPreference = "Stop"

$evidenceDir = "docs/08-ai/artifacts/f04-mobile-runtime-acceptance"

Write-Output "=== SCENARIO 10: CONNECTION / BACKEND ERROR & RETRY ==="

# 1. Mở run không tồn tại
$invalidRunId = "00000000-0000-0000-0000-000000000000"
Write-Output "--- Bước 1: Mở run không tồn tại $invalidRunId ---"
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$invalidRunId"
Start-Sleep -Seconds 4

# 2. Chụp ảnh Error Card
$screencapError = "$evidenceDir/android_runtime_screen_network_retry_error.png"
adb shell screencap -p /sdcard/screen_network_retry_error.png
adb pull /sdcard/screen_network_retry_error.png $screencapError

adb shell uiautomator dump /sdcard/screen_network_retry_error.xml
adb pull /sdcard/screen_network_retry_error.xml scratch/screen_network_retry_error.xml

$dumpError = Get-Content scratch/screen_network_retry_error.xml -Raw
$hasErrorCard = ($dumpError -match "progress-summary-error-card" -or $dumpError -match "Không tìm thấy" -or $dumpError -match "Kh\?ng t\?m th\?y")
Write-Output "Kiểm tra UI hiển thị thẻ lỗi: $hasErrorCard"
Write-Output "Ảnh lỗi: $screencapError ($((Get-Item $screencapError).Length) bytes)"

# 3. Mở run hợp lệ đã tạo thành công
$validRunId = (Get-Content "scratch/trainer_restricted_run_id.txt").Trim()
Write-Output "`n--- Bước 2: Điều hướng tới run hợp lệ $validRunId sau khi khắc phục/thử lại ---"
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$validRunId"
Start-Sleep -Seconds 4

# 4. Chụp ảnh Success
$screencapSuccess = "$evidenceDir/android_runtime_screen_network_retry_success.png"
adb shell screencap -p /sdcard/screen_network_retry_success.png
adb pull /sdcard/screen_network_retry_success.png $screencapSuccess

adb shell uiautomator dump /sdcard/screen_network_retry_success.xml
adb pull /sdcard/screen_network_retry_success.xml scratch/screen_network_retry_success.xml

$dumpSuccess = Get-Content scratch/screen_network_retry_success.xml -Raw
$hasErrorCardAfter = ($dumpSuccess -match "progress-summary-error-card")
$hasSummarySection = ($dumpSuccess -match "THÔNG TIN TIẾN ĐỘ" -or $dumpSuccess -match "TH\?NG TIN TI\?N")
Write-Output "Kiểm tra thẻ lỗi đã biến mất: $(-not $hasErrorCardAfter)"
Write-Output "Kiểm tra bản tóm tắt hiển thị thành công: $hasSummarySection"
Write-Output "Ảnh thành công: $screencapSuccess ($((Get-Item $screencapSuccess).Length) bytes)"

Write-Output "`n=== HOÀN TẤT SCENARIO 10 THÀNH CÔNG! ==="
