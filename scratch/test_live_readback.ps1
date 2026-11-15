$secretText = "dev-only-jwt-secret-change-before-production-1234567890"
$secretBytes = [System.Text.Encoding]::UTF8.GetBytes($secretText)

function Base64UrlEncode([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
$nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$nowSec = [Math]::Floor($nowMs / 1000)
$payload = @{
    iss = "fitness-coaching-backend"
    sub = "5c6f49c6-f4f6-4941-b217-2574b3830395"
    email = "smoke.student@example.com"
    roles = @("ROLE_STUDENT", "STUDENT")
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
$jwt = "$signInput.$sig"

$headers = @{
    "Authorization" = "Bearer $jwt"
    "Accept" = "application/json"
}

try {
    $res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/progress/summary/acec8689-2549-4868-97df-67e137e9be82" -Headers $headers -Method Get
    Write-Output "isStale: $($res.isStale), needsRefresh: $($res.needsRefresh), staleReason: $($res.staleReason)"
    Write-Output "Overall Summary: $($res.overallSummary)"
    $res | ConvertTo-Json -Depth 5 | Out-File "scratch/post_mutation_readback.json" -Encoding utf8
} catch {
    Write-Output "Error: $_"
}
