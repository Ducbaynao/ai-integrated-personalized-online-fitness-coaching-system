# Verification script for Real Operating System Process Restart Durability
[CmdletBinding()]
param(
    [int]$Port = 8089,
    [string]$DbUrl = "jdbc:postgresql://localhost:5433/digital_fitness_restore_test",
    [string]$DbUser = "fitness_app",
    [string]$DbPass = "123456",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$S3Bucket = "fitness-media"
)

$ErrorActionPreference = "Stop"
$curlCommand = if ($env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }
$jarPath = Join-Path $PSScriptRoot "..\services\backend\target\backend-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path $jarPath)) {
    throw "Jar file not found at $jarPath. Build with 'mvnw package -DskipTests' first."
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Real OS Process Restart Durability Drill" -ForegroundColor Cyan
Write-Host "  Port: $Port | Target DB: $DbUrl" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$processArgs = @(
    "-jar", $jarPath,
    "--spring.profiles.active=dev",
    "--server.port=$Port",
    "--spring.datasource.url=$DbUrl",
    "--spring.datasource.username=$DbUser",
    "--spring.datasource.password=$DbPass",
    "--spring.flyway.enabled=false",
    "--spring.jpa.hibernate.ddl-auto=validate",
    "--management.health.mail.enabled=false",
    "--chat.attachments.s3.endpoint=$S3Endpoint",
    "--chat.attachments.s3.bucket=$S3Bucket",
    "--chat.attachments.s3.access-key=test",
    "--chat.attachments.s3.secret-key=test",
    "--chat.attachments.clamd.host=localhost",
    "--chat.attachments.clamd.port=3310",
    "--ai.service.mode=simulated",
    "--ai.embedding.provider=simulated",
    "--app.scheduling.enabled=false"
)

function Wait-BackendReady {
    param([int]$TargetPort, [int]$TimeoutSeconds = 60)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:$TargetPort/actuator/health" -Method Get -TimeoutSec 2 -ErrorAction Stop
            if ($resp.status -eq "UP") {
                return $true
            }
        } catch {
        }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

$proc1 = $null
$proc2 = $null
$tempPhotoFile = $null
$downloadTemp = $null
$proc1Out = [System.IO.Path]::GetTempFileName()
$proc1Err = [System.IO.Path]::GetTempFileName()
$proc2Out = [System.IO.Path]::GetTempFileName()
$proc2Err = [System.IO.Path]::GetTempFileName()

# Ensure test user password hash is set in the drill database (Password123!)
$initSql = @'
UPDATE fitness.users SET password_hash = '$2a$10$/9QxT8jnz8ejFao4jstpP.0ZpVI/WXInGrAM8LQgm9c4nxRCgBvWK' WHERE email = 'smoke.student@example.com';
'@
$initSql | docker exec -i digital-fitness-postgres psql -U $DbUser -d digital_fitness_restore_test | Out-Null


try {
    # ----------------------------------------------------
    # PHASE 1: Boot First OS Process
    # ----------------------------------------------------
    Write-Host "[1/6] Launching Backend OS Process #1..." -ForegroundColor Yellow
    $proc1 = Start-Process -FilePath "java.exe" -ArgumentList $processArgs -PassThru -RedirectStandardOutput $proc1Out -RedirectStandardError $proc1Err
    $pid1 = $proc1.Id
    Write-Host "  Process #1 started with OS PID: $pid1" -ForegroundColor Gray

    Write-Host "[2/6] Waiting for Process #1 to become healthy..." -ForegroundColor Yellow
    $ready = Wait-BackendReady -TargetPort $Port
    if (-not $ready) {
        if (Test-Path $proc1Err) { Get-Content $proc1Err -Tail 20 | Write-Host -ForegroundColor Red }
        if (Test-Path $proc1Out) { Get-Content $proc1Out -Tail 20 | Write-Host -ForegroundColor Red }
        throw "Process #1 (PID $pid1) failed to become healthy within timeout."
    }
    Write-Host "  Process #1 is UP and healthy on port $Port." -ForegroundColor Green

    # ----------------------------------------------------
    # PHASE 2: Upload File via Real HTTP API
    # ----------------------------------------------------
    Write-Host "[3/6] Authenticating and uploading test photo via Process #1 API..." -ForegroundColor Yellow
    $loginBody = @{
        email = "smoke.student@example.com"
        password = "Password123!"
        deviceName = "OS-Process-Restart-Verifier"
    } | ConvertTo-Json

    $loginResp = Invoke-RestMethod -Uri "http://localhost:$Port/api/v1/auth/sessions" -Method Post -Body $loginBody -ContentType "application/json" -ErrorAction Stop
    $studentToken = $loginResp.accessToken
    Write-Host "  Authenticated student session obtained." -ForegroundColor Gray

    # Construct unique PNG file payload (valid PNG magic + IDAT + IEND + unique bytes)
    $basePng = [System.Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==")
    $uniqueTag = [System.Text.Encoding]::UTF8.GetBytes("OS_PROCESS_RESTART_TOKEN_" + [System.Guid]::NewGuid().ToString())
    $photoBytes = [byte[]]::new($basePng.Length + $uniqueTag.Length)
    [System.Buffer]::BlockCopy($basePng, 0, $photoBytes, 0, $basePng.Length)
    [System.Buffer]::BlockCopy($uniqueTag, 0, $photoBytes, $basePng.Length, $uniqueTag.Length)

    $originalHash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($photoBytes)) -Algorithm SHA256).Hash.ToLowerInvariant()
    $tempPhotoFile = [System.IO.Path]::GetTempFileName() + ".png"
    [System.IO.File]::WriteAllBytes($tempPhotoFile, $photoBytes)

    $uploadOutput = & $curlCommand -s -f -X POST "http://localhost:$Port/api/v1/measurements/progress-photos" `
        -H "Authorization: Bearer $studentToken" `
        -F "file=@$tempPhotoFile;type=image/png" `
        -F "viewType=FRONT" `
        -F "notes=OS Process Restart Drill Parity Test"
    
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($uploadOutput)) {
        throw "Upload to Process #1 failed! Exit code: $LASTEXITCODE, Output: $uploadOutput"
    }

    $uploadJson = $uploadOutput | ConvertFrom-Json
    $photoId = $uploadJson.id
    Write-Host "  Progress photo uploaded successfully! Photo ID: $photoId, SHA-256: $originalHash" -ForegroundColor Green

    # ----------------------------------------------------
    # PHASE 3: Terminate OS Process #1
    # ----------------------------------------------------
    Write-Host "[4/6] Force-killing OS Process #1 (PID $pid1)..." -ForegroundColor Yellow
    Stop-Process -Id $pid1 -Force
    $proc1.WaitForExit(5000)
    Write-Host "  Process #1 (PID $pid1) terminated." -ForegroundColor Gray
    Start-Sleep -Seconds 2

    # Verify port is free
    $portActive = $true
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect("localhost", $Port)
        $tcp.Close()
    } catch {
        $portActive = $false
    }
    if ($portActive) {
        throw "Port $Port is still occupied after Process #1 termination!"
    }
    Write-Host "  Port $Port confirmed released." -ForegroundColor Gray

    # ----------------------------------------------------
    # PHASE 4: Boot Second OS Process (Fresh JVM)
    # ----------------------------------------------------
    Write-Host "[5/6] Launching Backend OS Process #2 (Fresh JVM)..." -ForegroundColor Yellow
    $proc2 = Start-Process -FilePath "java.exe" -ArgumentList $processArgs -PassThru -RedirectStandardOutput $proc2Out -RedirectStandardError $proc2Err
    $pid2 = $proc2.Id
    Write-Host "  Process #2 started with OS PID: $pid2" -ForegroundColor Gray

    if ($pid1 -eq $pid2) {
        throw "Process #2 was assigned identical PID to Process #1! Expected distinct OS PID."
    }
    Write-Host "  Distinct OS PID confirmed: PID1=$pid1 vs PID2=$pid2" -ForegroundColor Green

    $ready2 = Wait-BackendReady -TargetPort $Port
    if (-not $ready2) {
        if (Test-Path $proc2Err) { Get-Content $proc2Err -Tail 20 | Write-Host -ForegroundColor Red }
        if (Test-Path $proc2Out) { Get-Content $proc2Out -Tail 20 | Write-Host -ForegroundColor Red }
        throw "Process #2 (PID $pid2) failed to become healthy within timeout."
    }
    Write-Host "  Process #2 is UP and healthy on port $Port." -ForegroundColor Green

    # ----------------------------------------------------
    # PHASE 5: Fetch File through Process #2 API & Verify Hash
    # ----------------------------------------------------
    Write-Host "[6/6] Downloading photo through Process #2 API and verifying byte parity..." -ForegroundColor Yellow
    $downloadTemp = [System.IO.Path]::GetTempFileName()
    & $curlCommand -s -f "http://localhost:$Port/api/v1/measurements/progress-photos/$photoId/file" `
        -H "Authorization: Bearer $studentToken" `
        -o "$downloadTemp"

    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $downloadTemp)) {
        throw "Failed to download document from Process #2 via API!"
    }

    $downloadedHash = (Get-FileHash -Path $downloadTemp -Algorithm SHA256).Hash.ToLowerInvariant()
    $downloadedSize = (Get-Item $downloadTemp).Length

    Write-Host "  Downloaded from Process #2: $downloadedSize bytes, SHA-256: $downloadedHash" -ForegroundColor Gray

    if ($downloadedHash -ne $originalHash) {
        throw "CHECKSUM MISMATCH! Expected: $originalHash, Got: $downloadedHash"
    }

    # Clean up test photo from database and S3 via Process #2
    try {
        & $curlCommand -s -f -X DELETE "http://localhost:$Port/api/v1/measurements/progress-photos/$photoId" `
            -H "Authorization: Bearer $studentToken" | Out-Null
    } catch {}

    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  REAL OS PROCESS RESTART VERIFICATION SUCCEEDED!" -ForegroundColor Green
    Write-Host "  Process #1 (OS PID $pid1) -> Uploaded photo ($originalHash)" -ForegroundColor Green
    Write-Host "  Process #2 (OS PID $pid2) -> Downloaded and verified identical byte hash" -ForegroundColor Green
    Write-Host "  Persistence across distinct OS JVM processes: 100% BYTE PARITY" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

} finally {
    if ($tempPhotoFile -and (Test-Path $tempPhotoFile)) {
        Remove-Item $tempPhotoFile -Force -ErrorAction SilentlyContinue
    }
    if ($downloadTemp -and (Test-Path $downloadTemp)) {
        Remove-Item $downloadTemp -Force -ErrorAction SilentlyContinue
    }
    if ($proc1 -and -not $proc1.HasExited) {
        Stop-Process -Id $proc1.Id -Force -ErrorAction SilentlyContinue
    }
    if ($proc2 -and -not $proc2.HasExited) {
        Stop-Process -Id $proc2.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 1
    Remove-Item $proc1Out, $proc1Err, $proc2Out, $proc2Err -Force -ErrorAction SilentlyContinue
}
