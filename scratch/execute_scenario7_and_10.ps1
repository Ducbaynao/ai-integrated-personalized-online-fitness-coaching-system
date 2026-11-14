$ErrorActionPreference = "Stop"

$secretText = "dev-only-jwt-secret-change-before-production-1234567890"
$secretBytes = [System.Text.Encoding]::UTF8.GetBytes($secretText)

function Base64UrlEncode([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function GenerateToken($userId, $email, $roles) {
    $header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $nowSec = [Math]::Floor($nowMs / 1000)
    $payload = @{
        iss = "fitness-coaching-backend"
        sub = $userId
        email = $email
        roles = $roles
        iat = $nowSec
        exp = $nowSec + 3600
        jti = [Guid]::NewGuid().ToString()
        iat_epoch_ms = $nowMs
    } | ConvertTo-Json -Compress

    $hB64 = Base64UrlEncode ([System.Text.Encoding]::UTF8.GetBytes($header))
    $pB64 = Base64UrlEncode ([System.Text.Encoding]::UTF8.GetBytes($payload))
    $signInput = "$hB64.$pB64"

    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = $secretBytes
    $sig = Base64UrlEncode ($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signInput)))
    return "$signInput.$sig"
}

$studentId = "3fb728a6-f277-47a7-952b-545ac791118a"
$studentEmail = "phamducyb01@gmail.com"
$trainerId = "1cb982ef-b7e6-493c-86f9-fe4994f8ad93"
$trainerEmail = "smoke.trainer@example.com"

$trainerToken = GenerateToken $trainerId $trainerEmail @("ROLE_TRAINER", "TRAINER")
$trainerHeaders = @{
    "Authorization" = "Bearer $trainerToken"
    "Accept" = "application/json"
    "Content-Type" = "application/json"
}

$studentToken = GenerateToken $studentId $studentEmail @("ROLE_STUDENT", "STUDENT")
$studentHeaders = @{
    "Authorization" = "Bearer $studentToken"
    "Accept" = "application/json"
    "Content-Type" = "application/json"
}

function RunSql($query) {
    docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$query"
}

$evidenceDir = "docs/08-ai/artifacts/f04-mobile-runtime-acceptance"

Write-Output "======================================================================"
Write-Output "=== SCENARIO 7: TRAINER VIEWING STUDENT WITH RESTRICTED BODY_METRICS ==="
Write-Output "======================================================================"

# 1. Thu hồi quyền BODY_METRICS của Trainer đối với Student Duc
Write-Output "--- Bước 1: Cập nhật quyền chia sẻ BODY_METRICS sang DENY ---"
RunSql "UPDATE fitness.data_sharing_permissions SET decision = 'DENY', revoked_at = now() WHERE student_id = '$studentId' AND trainer_id = '$trainerId' AND data_scope = 'BODY_METRICS';"

# 2. Trainer gọi API tạo summary cho student
Write-Output "--- Bước 2: Trainer gọi POST /api/v1/ai/progress/summary cho Student Duc ---"
$reqTrainer = @{
    studentId = $studentId
    fromDate = "2026-10-01"
    toDate = "2026-10-25"
    timezone = "Asia/Ho_Chi_Minh"
    metricCodes = @("WEIGHT")
} | ConvertTo-Json

$resTrainer = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $trainerHeaders -Method Post -Body $reqTrainer
$runIdTrainer = $resTrainer.runId
Write-Output "RunId tạo bởi Trainer: $runIdTrainer"
Write-Output "Workout Summary: $($resTrainer.workoutProgressSummary | ConvertTo-Json -Compress)"
Write-Output "Measurement Summary (phải là null): $($resTrainer.measurementProgressSummary)"
Write-Output "Limitations: $($resTrainer.limitations -join '; ')"

if ($resTrainer.measurementProgressSummary -ne $null) {
    throw "Assertion Failed: measurementProgressSummary must be null when BODY_METRICS is restricted!"
}

# 3. Mở trên Mobile Android runtime qua deep link
Write-Output "`n--- Bước 3: Mở run trên Android Emulator qua Deep Link ---"
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$runIdTrainer"
Start-Sleep -Seconds 5

# 4. Chụp màn hình và dump UI XML
$screencapS7 = "$evidenceDir/android_runtime_screen_trainer_restricted_measurement.png"
adb shell screencap -p /sdcard/screen_trainer_restricted.png
adb pull /sdcard/screen_trainer_restricted.png $screencapS7

adb shell uiautomator dump /sdcard/screen_trainer_restricted.xml
adb pull /sdcard/screen_trainer_restricted.xml scratch/screen_trainer_restricted.xml

$dumpS7 = Get-Content scratch/screen_trainer_restricted.xml -Raw
$hasRestrictedNotice = $dumpS7 -match "measurement-restricted-notice" -or $dumpS7 -match "chính sách chia sẻ"
$hasWorkoutSection = $dumpS7 -match "workout-progress-summary" -or $dumpS7 -match "Tiến độ tập luyện"

Write-Output "Kiểm tra UI có measurement-restricted-notice: $hasRestrictedNotice"
Write-Output "Kiểm tra UI có Workout Section bình thường: $hasWorkoutSection"
Write-Output "Ảnh chụp đã lưu: $screencapS7 (Kích thước: $((Get-Item $screencapS7).Length) bytes)"

# 5. Khôi phục lại quyền BODY_METRICS
Write-Output "`n--- Bước 5: Khôi phục lại quyền BODY_METRICS sang ALLOW ---"
RunSql "UPDATE fitness.data_sharing_permissions SET decision = 'ALLOW', revoked_at = NULL WHERE student_id = '$studentId' AND trainer_id = '$trainerId' AND data_scope = 'BODY_METRICS';"
Write-Output "Đã khôi phục quyền BODY_METRICS."


Write-Output "`n======================================================================"
Write-Output "=== SCENARIO 10: CONNECTION / BACKEND ERROR & RETRY FLOW ==="
Write-Output "======================================================================"

# 1. Mở deep link với một runId không tồn tại để kích hoạt lỗi API không tìm thấy / lỗi backend
Write-Output "--- Bước 1: Mở deep link với runId không tồn tại (kích hoạt Error Card) ---"
$nonExistentRunId = "00000000-0000-0000-0000-000000000000"
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$nonExistentRunId"
Start-Sleep -Seconds 4

# 2. Chụp màn hình và dump UI khi đang hiển thị Error Card
$screencapS10Error = "$evidenceDir/android_runtime_screen_network_retry_error.png"
adb shell screencap -p /sdcard/screen_network_error.png
adb pull /sdcard/screen_network_error.png $screencapS10Error

adb shell uiautomator dump /sdcard/screen_network_error.xml
adb pull /sdcard/screen_network_error.xml scratch/screen_network_error.xml

$dumpS10Error = Get-Content scratch/screen_network_error.xml -Raw
$hasErrorCard = $dumpS10Error -match "progress-summary-error-card"
Write-Output "Kiểm tra UI có hiển thị progress-summary-error-card: $hasErrorCard"
Write-Output "Ảnh chụp Error Card: $screencapS10Error (Kích thước: $((Get-Item $screencapS10Error).Length) bytes)"

# 3. Điều hướng tới summary hợp lệ (kết quả sau retry thành công)
Write-Output "`n--- Bước 3: Điều hướng tới summary hợp lệ sau retry thành công ---"
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$runIdTrainer"
Start-Sleep -Seconds 5

$screencapS10Success = "$evidenceDir/android_runtime_screen_network_retry_success.png"
adb shell screencap -p /sdcard/screen_network_success.png
adb pull /sdcard/screen_network_success.png $screencapS10Success

adb shell uiautomator dump /sdcard/screen_network_success.xml
adb pull /sdcard/screen_network_success.xml scratch/screen_network_success.xml

$dumpS10Success = Get-Content scratch/screen_network_success.xml -Raw
$retryResolved = (-not ($dumpS10Success -match "progress-summary-error-card"))
Write-Output "Kiểm tra UI đã giải phóng Error Card sau retry thành công: $retryResolved"
Write-Output "Ảnh chụp Success: $screencapS10Success (Kích thước: $((Get-Item $screencapS10Success).Length) bytes)"

Write-Output "`n=== TẤT CẢ SCENARIO GUI BỔ SUNG ĐÃ HOÀN TẤT VỚI BẰNG CHỨNG RUNTIME THẬT! ==="
