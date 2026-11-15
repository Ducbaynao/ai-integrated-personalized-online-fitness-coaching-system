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
if (-not (Test-Path $evidenceDir)) {
    New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null
}

$logFile = "$evidenceDir/freshness_audit_trail.log"
Set-Content -Path $logFile -Value "=== F04 LIVE DATABASE MUTATION & FRESHNESS AUDIT TRAIL ===" -Encoding utf8
Add-Content -Path $logFile -Value "Execution Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')"
Add-Content -Path $logFile -Value "Target Student ID: $studentId"
Add-Content -Path $logFile -Value "Baseline Branch: fix/p2-m4-f04-verification-evidence"
Add-Content -Path $logFile -Value "--------------------------------------------------------`n"

function CleanJson($obj) {
    return ($obj | ConvertTo-Json -Depth 10)
}

function RunSql($query) {
    docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$query"
}

# 1. Bước a: Tạo Initial Summary
Write-Output "--- Bước a: Tạo Initial Summary ---"
$reqA = @{
    fromDate = "2026-10-01"
    toDate = "2026-10-31"
    timezone = "UTC"
    metricCodes = @("WEIGHT")
} | ConvertTo-Json

$resA = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $reqA
$runIdA = $resA.runId
Write-Output "RunId A created: $runIdA, isStale: $($resA.isStale), needsRefresh: $($resA.needsRefresh)"

if ($resA.isStale -ne $false -or $resA.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước a: isStale should be false, needsRefresh should be false"
}
Set-Content -Path "$evidenceDir/step_a_initial_summary.json" -Value (CleanJson $resA) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC a] Tạo Summary ban đầu: runId=$runIdA, window=[2026-10-01 to 2026-10-31], isStale=$($resA.isStale), needsRefresh=$($resA.needsRefresh) => ASSERTION PASS"

# 2. Bước b: Readback khi nguồn chưa đổi
Write-Output "--- Bước b: Readback khi nguồn chưa đổi ---"
$resB = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdA" -Headers $headers -Method Get
Write-Output "Readback A: isStale: $($resB.isStale), needsRefresh: $($resB.needsRefresh)"

if ($resB.isStale -ne $false -or $resB.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước b: readback isStale should be false"
}
Set-Content -Path "$evidenceDir/step_b_readback_fresh.json" -Value (CleanJson $resB) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC b] Readback khi nguồn chưa đổi: runId=$runIdA, isStale=$($resB.isStale), needsRefresh=$($resB.needsRefresh) => ASSERTION PASS"

# 3. Bước c: Thêm observation ngoài kỳ (2026-11-15) -> Readback vẫn fresh
Write-Output "--- Bước c: Thêm observation ngoài kỳ (2026-11-15) ---"
$fixtureOutId = "f0400001-0000-0000-0000-000000000001"
$sqlInsertOut = "INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, validation_status) VALUES ('$fixtureOutId', '$studentId', 1, 74.0, 1, '2026-11-15 09:00:00+00', 2, 'VALID') ON CONFLICT (id) DO UPDATE SET validation_status = 'VALID';"
RunSql $sqlInsertOut

$resC = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdA" -Headers $headers -Method Get
Write-Output "Readback after out-of-window mutation: isStale: $($resC.isStale), needsRefresh: $($resC.needsRefresh)"

if ($resC.isStale -ne $false -or $resC.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước c: out-of-window mutation should NOT cause stale flag"
}
Set-Content -Path "$evidenceDir/step_c_readback_after_out_of_window_mutation.json" -Value (CleanJson $resC) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC c] Thêm observation ngoài kỳ (fixture=$fixtureOutId, measured_at=2026-11-15): readback runId=$runIdA, isStale=$($resC.isStale), needsRefresh=$($resC.needsRefresh) => ASSERTION PASS"

# 4. Bước d: Thêm observation trong kỳ (2026-10-20) -> Readback chuyển sang stale
Write-Output "--- Bước d: Thêm observation trong kỳ (2026-10-20) ---"
$fixtureInId = "f0400002-0000-0000-0000-000000000002"
$sqlInsertIn = "INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, validation_status) VALUES ('$fixtureInId', '$studentId', 1, 73.0, 1, '2026-10-20 09:00:00+00', 2, 'VALID') ON CONFLICT (id) DO UPDATE SET validation_status = 'VALID';"
RunSql $sqlInsertIn

$resD = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdA" -Headers $headers -Method Get
Write-Output "Readback after in-window mutation: isStale: $($resD.isStale), needsRefresh: $($resD.needsRefresh), staleReason: $($resD.staleReason)"

if ($resD.isStale -ne $true -or $resD.needsRefresh -ne $true) {
    throw "Assertion Failed at Bước d: in-window mutation MUST trigger isStale=true, needsRefresh=true"
}
Set-Content -Path "$evidenceDir/step_d_readback_after_in_window_mutation_stale.json" -Value (CleanJson $resD) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC d] Thêm observation trong kỳ (fixture=$fixtureInId, measured_at=2026-10-20): readback runId=$runIdA, isStale=$($resD.isStale), needsRefresh=$($resD.needsRefresh) => ASSERTION PASS (PHÁT HIỆN ĐỘT BIẾN CHÍNH XÁC)"

# 5. Bước e: Cập nhật observation trong kỳ thành REJECTED -> Readback khôi phục fresh
Write-Output "--- Bước e: Cập nhật observation thành REJECTED ---"
$sqlUpdateReject = "UPDATE fitness.measurements SET validation_status = 'REJECTED' WHERE id = '$fixtureInId';"
RunSql $sqlUpdateReject

$resE = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdA" -Headers $headers -Method Get
Write-Output "Readback after rejection: isStale: $($resE.isStale), needsRefresh: $($resE.needsRefresh)"

if ($resE.isStale -ne $false -or $resE.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước e: rejection should restore isStale=false"
}
Set-Content -Path "$evidenceDir/step_e_readback_after_rejection_restored_fresh.json" -Value (CleanJson $resE) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC e] Cập nhật observation thành REJECTED (fixture=$fixtureInId): readback runId=$runIdA, isStale=$($resE.isStale), needsRefresh=$($resE.needsRefresh) => ASSERTION PASS (KHÔI PHỤC FRESH THÀNH CÔNG)"

# 6. Bước f: Tái kích hoạt mutation trong kỳ và tạo summary mới (Refresh)
Write-Output "--- Bước f: Refresh tạo summary mới ---"
$sqlUpdateValidAgain = "UPDATE fitness.measurements SET validation_status = 'VALID' WHERE id = '$fixtureInId';"
RunSql $sqlUpdateValidAgain

$resD2 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdA" -Headers $headers -Method Get
if ($resD2.isStale -ne $true) {
    throw "Assertion Failed at Bước f precondition: runIdA must be stale before refresh"
}

# Tạo summary mới qua POST (Refresh)
$resF = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary" -Headers $headers -Method Post -Body $reqA
$runIdB = $resF.runId
Write-Output "RunId B refreshed: $runIdB, isStale: $($resF.isStale), needsRefresh: $($resF.needsRefresh)"

if ($resF.runId -eq $runIdA) {
    throw "Assertion Failed at Bước f: Refreshed summary must have a distinct new runId"
}
if ($resF.isStale -ne $false -or $resF.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước f: Refreshed summary must be fresh"
}

# Đọc lại runIdB khẳng định fresh
$resF_readback = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/$runIdB" -Headers $headers -Method Get
if ($resF_readback.isStale -ne $false -or $resF_readback.needsRefresh -ne $false) {
    throw "Assertion Failed at Bước f: Readback of refreshed summary must be fresh"
}

Set-Content -Path "$evidenceDir/step_f_refreshed_summary_fresh.json" -Value (CleanJson $resF) -Encoding utf8
Add-Content -Path $logFile -Value "[BƯỚC f] Refresh tạo summary mới: runIdA=$runIdA (stale) => runIdB=$runIdB (tươi mới hoàn toàn), isStale=$($resF.isStale), needsRefresh=$($resF.needsRefresh) => ASSERTION PASS"

# 7. Dọn dẹp an toàn các fixture test về REJECTED
Write-Output "--- Bước 7: Dọn dẹp an toàn fixture test ---"
RunSql "UPDATE fitness.measurements SET validation_status = 'REJECTED' WHERE id IN ('$fixtureOutId', '$fixtureInId');"
Add-Content -Path $logFile -Value "[CLEANUP] Đã cập nhật toàn bộ fixture ($fixtureOutId, $fixtureInId) sang trạng thái REJECTED theo đúng trigger bảo vệ observation.`n"

Write-Output "`n=== TẤT CẢ 6 BƯỚC KIỂM CHỨNG LIVE DATABASE MUTATION & FRESHNESS ĐÃ PASS 100% ==="
