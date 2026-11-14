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
$trainerId = "1cb982ef-b7e6-493c-86f9-fe4994f8ad93"
$trainerEmail = "smoke.trainer@example.com"

$t = GenerateToken $trainerId $trainerEmail @("ROLE_TRAINER", "TRAINER")
$headers = @{
    "Authorization" = "Bearer $t"
    "Content-Type" = "application/json"
}

$body = @{
    studentId = $studentId
    fromDate = "2026-10-01"
    toDate = "2026-10-25"
    timezone = "Asia/Ho_Chi_Minh"
} | ConvertTo-Json

$res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $body
$runId = $res.runId

Write-Output "RunId: $runId"
Write-Output "Workout Summary: $($res.workoutProgressSummary | ConvertTo-Json -Compress)"
Write-Output "Measurement Summary: $($res.measurementProgressSummary)"
Write-Output "Limitations: $($res.limitations -join '; ')"
Set-Content -Path "scratch/trainer_restricted_run_id.txt" -Value $runId

# Open on emulator
Write-Output "Opening deep link on emulator..."
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$runId"
Start-Sleep -Seconds 5

$evidencePath = "docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_trainer_restricted_measurement.png"
adb shell screencap -p /sdcard/screen_trainer_restricted.png
adb pull /sdcard/screen_trainer_restricted.png $evidencePath

adb shell uiautomator dump /sdcard/screen_trainer_restricted.xml
adb pull /sdcard/screen_trainer_restricted.xml scratch/screen_trainer_restricted.xml

$dump = Get-Content scratch/screen_trainer_restricted.xml -Raw
$hasNotice = ($dump -match "measurement-restricted-notice" -or $dump -match "chính sách chia sẻ" -or $dump -match "ch\?nh s\?ch chia s?")
Write-Output "UI has restricted notice: $hasNotice"
Write-Output "Screenshot saved: $evidencePath ($((Get-Item $evidencePath).Length) bytes)"
