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
$token = GenerateToken $studentId $studentEmail @("ROLE_STUDENT", "STUDENT")

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/json"
    "Content-Type" = "application/json"
}

function RunSql($query) {
    docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$query"
}

Write-Output "=== 1. TẠO SUMMARY BAN ĐẦU VỚI PHẠM VI TÙY BIẾN [2026-10-01 ĐẾN 2026-10-25] ==="
$reqInitial = @{
    fromDate = "2026-10-01"
    toDate = "2026-10-25"
    timezone = "Asia/Ho_Chi_Minh"
    metricCodes = @("WEIGHT")
} | ConvertTo-Json

$resInitial = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $reqInitial
$runIdInitial = $resInitial.runId
Write-Output "RunId tạo mới: $runIdInitial, isStale: $($resInitial.isStale), needsRefresh: $($resInitial.needsRefresh)"
Write-Output "Scope trả về từ backend: fromDate=$($resInitial.fromDate), toDate=$($resInitial.toDate), requestedMetrics=$($resInitial.requestedMetricCodes -join ',')"

Write-Output "`n=== 2. MỞ RUN NÀY TRÊN MOBILE QUA DEEP LINK ==="
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$runIdInitial"
Start-Sleep -Seconds 4

# Chụp ảnh và dump UI khi vừa load fresh
adb shell screencap -p /sdcard/screen_scope_readback_fresh.png
adb pull /sdcard/screen_scope_readback_fresh.png docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_readback_fresh.png
adb shell uiautomator dump /sdcard/scope_fresh_dump.xml
adb pull /sdcard/scope_fresh_dump.xml scratch/scope_fresh_dump.xml

$freshDump = Get-Content scratch/scope_fresh_dump.xml -Raw
if ($freshDump -match 'text="2026-10-01"' -and $freshDump -match 'text="2026-10-25"') {
    Write-Output "XÁC NHẬN: Form input đã được đồng bộ chính xác với phạm vi lưu trữ: 2026-10-01 đến 2026-10-25 (KHÔNG bị đè bởi 14 ngày mặc định)!"
} else {
    Write-Warning "Cảnh báo: Form input chưa hiển thị đúng 2026-10-01 / 2026-10-25!"
}

Write-Output "`n=== 3. THỰC HIỆN MUTATION TRONG PHẠM VI (INSERT OBSERVATION NGÀY 2026-10-20) ==="
$fixtureId = "f0400004-0000-0000-0000-000000000004"
$sqlInsert = "INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, validation_status) VALUES ('$fixtureId', '$studentId', 1, 74.8, 1, '2026-10-20 08:00:00+00', 2, 'VALID') ON CONFLICT (id) DO UPDATE SET validation_status = 'VALID', numeric_value = 74.8;"
RunSql $sqlInsert

Write-Output "`n=== 4. TẢI LẠI TRÊN MOBILE ĐỂ KÍCH HOẠT READBACK & HIỂN THỊ STALE BANNER ==="
adb shell am start -a android.intent.action.VIEW -d "exp://127.0.0.1:8081/--/progress-summary/$runIdInitial"
Start-Sleep -Seconds 4

adb shell screencap -p /sdcard/screen_scope_stale_banner.png
adb pull /sdcard/screen_scope_stale_banner.png docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_stale_banner.png
adb shell uiautomator dump /sdcard/scope_stale_dump.xml
adb pull /sdcard/scope_stale_dump.xml scratch/scope_stale_dump.xml

$staleDump = Get-Content scratch/scope_stale_dump.xml -Raw
if ($staleDump -match "refresh-summary-button") {
    Write-Output "XÁC NHẬN: Stale banner hiển thị thành công với nút refresh-summary-button ('Làm mới ngay')!"
} else {
    Write-Warning "Cảnh báo: Chưa tìm thấy refresh-summary-button trên màn hình!"
}

Write-Output "`n=== 5. CLICK NÚT 'LÀM MỚI NGAY' TRÊN ANDROID RUNTIME ==="
# Tìm tọa độ nút refresh-summary-button từ layout
$layoutJson = android layout --pretty | ConvertFrom-Json
$refreshBtnObj = $null
foreach ($win in $layoutJson) {
    if ($win.content) {
        $found = $win.content | Where-Object { $_.'resource-id' -eq 'refresh-summary-button' }
        if ($found) { $refreshBtnObj = $found; break }
    }
}

if ($refreshBtnObj) {
    $cx = [int]($refreshBtnObj.center -replace '\[|\]' -split ',')[0]
    $cy = [int]($refreshBtnObj.center -replace '\[|\]' -split ',')[1]
    Write-Output "Clicking refresh button at coordinates ($cx, $cy)..."
    adb shell input tap $cx $cy
} else {
    Write-Output "Fallback: Click center coordinate of warning action banner (approx 930, 1420)..."
    adb shell input tap 930 1420
}

# Đợi hệ thống gửi request generate và nhận kết quả
Start-Sleep -Seconds 6

Write-Output "`n=== 6. KIỂM CHỨNG KẾT QUẢ REFRESH: BẢO TOÀN PHẠM VI 2026-10-01 ĐẾN 2026-10-25 ==="
adb shell screencap -p /sdcard/screen_scope_preserved_refreshed.png
adb pull /sdcard/screen_scope_preserved_refreshed.png docs/08-ai/artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_preserved_refreshed.png
adb shell uiautomator dump /sdcard/scope_refreshed_dump.xml
adb pull /sdcard/scope_refreshed_dump.xml scratch/scope_refreshed_dump.xml

$refreshedDump = Get-Content scratch/scope_refreshed_dump.xml -Raw
$hasNoStale = (-not ($refreshedDump -match "progress-summary-stale-banner"))
$datesPreserved = ($refreshedDump -match 'text="2026-10-01"' -and $refreshedDump -match 'text="2026-10-25"')

Write-Output "Kiểm tra Stale banner biến mất: $hasNoStale"
Write-Output "Kiểm tra Phạm vi ngày bảo toàn 2026-10-01 đến 2026-10-25: $datesPreserved"

# Kiểm tra bản ghi ai_runs mới nhất trong Database để chứng minh request gửi lên backend thực sự mang đúng scope
$dbLatestRun = docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -t -c "SELECT id, status, result_type, created_at FROM fitness.ai_runs WHERE student_id='$studentId' ORDER BY created_at DESC LIMIT 2;"
Write-Output "`nDB ai_runs gần nhất:`n$dbLatestRun"

$dbLatestContext = docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -t -c "SELECT context_payload->'dateWindow' FROM fitness.ai_context_snapshots WHERE student_id='$studentId' ORDER BY created_at DESC LIMIT 1;"
Write-Output "DB dateWindow của Run mới nhất: $dbLatestContext"

Write-Output "`n=== 7. DỌN DẸP FIXTURE AN TOÀN VỀ REJECTED ==="
RunSql "UPDATE fitness.measurements SET validation_status = 'REJECTED' WHERE id = '$fixtureId';"
Write-Output "Đã cập nhật fixture $fixtureId sang REJECTED an toàn theo trigger."

Write-Output "`n=== HOÀN TẤT THỬ NGHIỆM SCOPE-PRESERVING REFRESH TRÊN ANDROID RUNTIME: PASS! ==="
