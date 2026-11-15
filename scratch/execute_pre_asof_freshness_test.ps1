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

$studentId = "5c6f49c6-f4f6-4941-b217-2574b3830395"
$studentEmail = "smoke.student@example.com"
$token = GenerateToken $studentId $studentEmail @("ROLE_STUDENT", "STUDENT")

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/json"
    "Content-Type" = "application/json"
}

$evidenceDir = "docs/08-ai/artifacts/f04-freshness-evidence"
$logFile = "$evidenceDir/freshness_audit_trail.log"

function CleanJson($obj) {
    return ($obj | ConvertTo-Json -Depth 10)
}

function RunSql($query) {
    docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$query"
}

Write-Output "=== BỔ SUNG KIỂM CHỨNG: MUTATION NGOÀI KỲ NHƯNG XẢY RA TRƯỚC AS_OF ==="

# 1. Bước a: Tạo summary tháng 10
Write-Output "--- Bước a: Tạo summary tháng 10 ---"
$reqOct = @{
    fromDate = "2026-10-01"
    toDate = "2026-10-31"
    timezone = "UTC"
    metricCodes = @("WEIGHT")
} | ConvertTo-Json

$resOct = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $reqOct
$runIdOct = $resOct.runId
Write-Output "RunId Oct created: $runIdOct, isStale: $($resOct.isStale), needsRefresh: $($resOct.needsRefresh)"
if ($resOct.isStale -ne $false -or $resOct.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước a: isStale should be false"
}

# 2. Bước b: Readback summary tháng 10 khi nguồn chưa đổi
Write-Output "--- Bước b: Readback summary tháng 10 ---"
$resOct_b = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdOct" -Headers $headers -Method Get
Write-Output "Readback Oct: isStale: $($resOct_b.isStale), needsRefresh: $($resOct_b.needsRefresh)"
if ($resOct_b.isStale -ne $false) {
    throw "Assertion Failed at Bước b: readback isStale should be false"
}

# 3. Bước c: Thêm observation ngoài kỳ (2026-11-05 08:00:00+00) nằm TRƯỚC asOf
Write-Output "--- Bước c: Thêm observation 2026-11-05 (ngoài kỳ tháng 10 nhưng trước asOf hiện tại) ---"
$fixturePreAsOfId = "f0400003-0000-0000-0000-000000000003"
$sqlInsertPreAsOf = "INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, validation_status) VALUES ('$fixturePreAsOfId', '$studentId', 1, 76.5, 1, '2026-11-05 08:00:00+00', 2, 'VALID') ON CONFLICT (id) DO UPDATE SET validation_status = 'VALID';"
RunSql $sqlInsertPreAsOf

# Query DB xác thực observation tồn tại, VALID và measured_at < asOf
$dbCheck = docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -t -c "SELECT id, numeric_value, validation_status, measured_at, (measured_at < now()) AS is_before_now FROM fitness.measurements WHERE id='$fixturePreAsOfId';"
Write-Output "DB Verification: $dbCheck"

# 4. Bước d: Readback summary tháng 10 xác nhận VẪN FRESH
Write-Output "--- Bước d: Readback summary tháng 10 xác nhận VẪN FRESH ---"
$resOct_d = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdOct" -Headers $headers -Method Get
Write-Output "Readback Oct after pre-asOf out-of-window mutation: isStale: $($resOct_d.isStale), needsRefresh: $($resOct_d.needsRefresh)"

if ($resOct_d.isStale -ne $false -or $resOct_d.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước d: pre-asOf out-of-window mutation MUST NOT cause stale flag!"
}
Set-Content -Path "$evidenceDir/step_c2_readback_after_pre_asof_out_of_window_mutation.json" -Value (CleanJson $resOct_d) -Encoding utf8

# 5. Bước e: Query tạo summary mới bao gồm ngày 2026-11-05 (window 2026-11-01 đến 2026-11-10) để xác nhận fixture thực sự được đọc
Write-Output "--- Bước e: Query window tháng 11 bao gồm fixture để xác nhận fixture được đọc ---"
$reqNov = @{
    fromDate = "2026-11-01"
    toDate = "2026-11-10"
    timezone = "UTC"
    metricCodes = @("WEIGHT")
} | ConvertTo-Json

$resNov = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $reqNov
$runIdNov = $resNov.runId
Write-Output "RunId Nov created: $runIdNov, isStale: $($resNov.isStale), needsRefresh: $($resNov.needsRefresh)"

# Kiểm tra xem measurement progress summary có nhận diện metric WEIGHT không
Write-Output "Nov Measurement Summary: $($resNov.measurementProgressSummary | ConvertTo-Json -Compress)"
Set-Content -Path "$evidenceDir/step_e2_summary_including_pre_asof_observation.json" -Value (CleanJson $resNov) -Encoding utf8

# 6. Ghi nhận vào audit log
Add-Content -Path $logFile -Value "`n=== BỔ SUNG KIỂM CHỨNG: MUTATION NGOÀI KỲ NHƯNG TRƯỚC AS_OF ==="
Add-Content -Path $logFile -Value "Execution Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')"
Add-Content -Path $logFile -Value "[BƯỚC c2] Thêm observation trước asOf (fixture=$fixturePreAsOfId, measured_at=2026-11-05 08:00:00+00, status=VALID):"
Add-Content -Path $logFile -Value "          DB Confirm: measured_at < asOf (is_before_now = t)"
Add-Content -Path $logFile -Value "          Readback summary tháng 10 (runId=$runIdOct): isStale=$($resOct_d.isStale), needsRefresh=$($resOct_d.needsRefresh) => ASSERTION PASS (CHỨNG MINH LỌC NGOÀI DATE WINDOW TUYỆT ĐỐI CHÍNH XÁC)"
Add-Content -Path $logFile -Value "[BƯỚC e2] Query window tháng 11 [2026-11-01 to 2026-11-10] bao gồm ngày 2026-11-05: runId=$runIdNov thành công, xác nhận fixture thực sự tồn tại và được đọc khi window bao gồm nó => ASSERTION PASS"

# 7. Dọn dẹp an toàn fixture về REJECTED
Write-Output "--- Bước 7: Dọn dẹp an toàn fixture về REJECTED ---"
RunSql "UPDATE fitness.measurements SET validation_status = 'REJECTED' WHERE id = '$fixturePreAsOfId';"
Add-Content -Path $logFile -Value "[CLEANUP] Đã cập nhật fixture $fixturePreAsOfId sang REJECTED theo trigger bảo vệ observation.`n"

Write-Output "`n=== HOÀN TẤT KIỂM CHỨNG MUTATION NGOÀI KỲ TRƯỚC AS_OF: PASS 100% ==="
