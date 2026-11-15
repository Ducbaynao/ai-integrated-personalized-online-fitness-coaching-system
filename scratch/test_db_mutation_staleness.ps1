$secretBytes = [System.Text.Encoding]::UTF8.GetBytes("01234567890123456789012345678901")

function Base64UrlEncode([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$payload = @{
    sub = "5c6f49c6-f4f6-4941-b217-2574b3830395"
    email = "smoke.student@example.com"
    roles = @("STUDENT")
    iat = $now
    exp = $now + 3600
} | ConvertTo-Json -Compress

$hB64 = Base64UrlEncode ([System.Text.Encoding]::UTF8.GetBytes($header))
$pB64 = Base64UrlEncode ([System.Text.Encoding]::UTF8.GetBytes($payload))
$signInput = "$hB64.$pB64"

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = $secretBytes
$sig = Base64UrlEncode ($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signInput)))
$jwt = "$signInput.$sig"

$headers = @{
    "Authorization" = "Bearer $jwt"
    "Accept" = "application/json"
}

Write-Output "=== 1. PRE-MUTATION READBACK ==="
$preRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/acec8689-2549-4868-97df-67e137e9be82" -Headers $headers -Method Get
Write-Output "Pre-mutation isStale: $($preRes.isStale), needsRefresh: $($preRes.needsRefresh), staleReason: $($preRes.staleReason)"

Write-Output "=== 2. INSERTING NEW MEASUREMENT IN DB ==="
$insertSql = "INSERT INTO fitness.measurements (id, student_id, metric_definition_id, numeric_value, unit_id, measured_at, source_id, validation_status) VALUES ('e1111111-2222-3333-4444-555555555555', '5c6f49c6-f4f6-4941-b217-2574b3830395', 1, 72.5, 1, '2026-11-10 08:00:00+00', 2, 'VALID');"
docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$insertSql"

Write-Output "=== 3. POST-MUTATION READBACK ==="
$postRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/acec8689-2549-4868-97df-67e137e9be82" -Headers $headers -Method Get
Write-Output "Post-mutation isStale: $($postRes.isStale), needsRefresh: $($postRes.needsRefresh), staleReason: $($postRes.staleReason)"

Write-Output "=== 4. CLEANUP TEST MEASUREMENT ==="
$cleanSql = "DELETE FROM fitness.measurements WHERE id='e1111111-2222-3333-4444-555555555555';"
docker exec -e PGPASSWORD=123456 digital-fitness-postgres psql -U fitness_app -d digital_fitness -c "$cleanSql"

Write-Output "=== 5. POST-CLEANUP READBACK ==="
$cleanupRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/acec8689-2549-4868-97df-67e137e9be82" -Headers $headers -Method Get
Write-Output "Post-cleanup isStale: $($cleanupRes.isStale), needsRefresh: $($cleanupRes.needsRefresh), staleReason: $($cleanupRes.staleReason)"
