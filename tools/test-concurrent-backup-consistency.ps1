# Test / Drill script verifying backup consistency under concurrent writes
[CmdletBinding()]
param(
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$SourceDbName = "digital_fitness_restore_test",
    [string]$TestDbName = "df_concurrent_backup_drill",
    [string]$DbUser = "fitness_app",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$TestS3Bucket = "fitness-media-drill"
)

$ErrorActionPreference = "Stop"
$curlCommand = if ($env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Concurrent Write Backup Consistency Drill" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Prepare isolated test database and S3 bucket
Write-Host "[1/6] Initializing isolated database and S3 bucket..." -ForegroundColor Yellow
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $TestDbName;" 2>$null | Out-Null
docker exec $ContainerName psql -U $DbUser -d postgres -c "CREATE DATABASE $TestDbName TEMPLATE $SourceDbName OWNER $DbUser;" 2>$null | Out-Null
$ErrorActionPreference = $prevEAP
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create test database $TestDbName from template $SourceDbName"
}

# Clean any existing media records with bucket $TestS3Bucket
docker exec $ContainerName psql -U $DbUser -d $TestDbName -c "DELETE FROM fitness.media_files WHERE bucket_name = '$TestS3Bucket';" | Out-Null

# Clean test S3 bucket if exists, then recreate
try {
    $existing = Invoke-RestMethod -Uri "$S3Endpoint/$TestS3Bucket" -Method Get -TimeoutSec 3 -ErrorAction Stop
    if ($existing.ListBucketResult.Contents) {
        $keys = $existing.ListBucketResult.Contents | ForEach-Object { $_.Key }
        foreach ($k in $keys) {
            Invoke-RestMethod -Uri "$S3Endpoint/$TestS3Bucket/$k" -Method Delete -ErrorAction SilentlyContinue | Out-Null
        }
    }
} catch {
    Invoke-RestMethod -Uri "$S3Endpoint/$TestS3Bucket" -Method Put -ErrorAction SilentlyContinue | Out-Null
}

# 2. Seed Initial Object A (committed before backup starts)
Write-Host "[2/6] Seeding Initial File A into S3 and DB..." -ForegroundColor Yellow
$fileAContent = "PDF Document A - Initial Baseline - " + [System.Guid]::NewGuid().ToString()
$fileABytes = [System.Text.Encoding]::UTF8.GetBytes($fileAContent)
$fileAKey = "verification-docs/drill/fileA.pdf"
$fileAHash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($fileABytes)) -Algorithm SHA256).Hash.ToLowerInvariant()

# Upload File A to S3
$uploadUrlA = "$S3Endpoint/$TestS3Bucket/$fileAKey"
$tempFileA = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllBytes($tempFileA, $fileABytes)
& $curlCommand -s -f -X PUT -T "$tempFileA" "$uploadUrlA"
Remove-Item $tempFileA -Force

# Insert File A into media_files
$idA = [System.Guid]::NewGuid().ToString()
$insertASql = "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$idA', 'S3', '$TestS3Bucket', '$fileAKey', 'application/pdf', $($fileABytes.Length), '$fileAHash', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');"
docker exec $ContainerName psql -U $DbUser -d $TestDbName -c "$insertASql" | Out-Null

# 3. Setup Concurrent Upload B definitions
Write-Host "[3/6] Setting up concurrent upload B scenario..." -ForegroundColor Yellow
$fileBContent = "PDF Document B - Concurrent Upload between Storage Sync and pg_dump - " + [System.Guid]::NewGuid().ToString()
$fileBBytes = [System.Text.Encoding]::UTF8.GetBytes($fileBContent)
$fileBKey = "verification-docs/drill/fileB_concurrent.pdf"
$fileBHash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($fileBBytes)) -Algorithm SHA256).Hash.ToLowerInvariant()
$idB = [System.Guid]::NewGuid().ToString()

$backupOutputDir = Join-Path $PSScriptRoot "..\database\backup\backups\drill_concurrent_test"
if (Test-Path $backupOutputDir) { Remove-Item $backupOutputDir -Recurse -Force }

# Write the hook script executed right after Storage Backup finishes but before pg_dump starts
$hookScriptPath = Join-Path $PSScriptRoot "..\database\backup\backups\drill_hook.ps1"
$hookScriptContent = @"
Write-Host "    >>> SIMULATING CONCURRENT WRITE: Uploading File B to S3 & committing to DB... <<<" -ForegroundColor Cyan
`$uploadUrlB = "$S3Endpoint/$TestS3Bucket/$fileBKey"
`$tempFileB = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllBytes(`$tempFileB, [System.Convert]::FromBase64String("$([System.Convert]::ToBase64String($fileBBytes))"))
& "$curlCommand" -s -f -X PUT -T "`$tempFileB" "`$uploadUrlB"
Remove-Item `$tempFileB -Force

`$insertBSql = "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$idB', 'S3', '$TestS3Bucket', '$fileBKey', 'application/pdf', $($fileBBytes.Length), '$fileBHash', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');"
docker exec $ContainerName psql -U $DbUser -d $TestDbName -c "`$insertBSql" | Out-Null
Write-Host "    >>> CONCURRENT WRITE COMMITTED: File B in S3 and DB <<<" -ForegroundColor Cyan
"@
$hookScriptContent | Set-Content -Path $hookScriptPath -Encoding UTF8

# 4. Execute Backup with Hook & Post-Dump Catch-up
Write-Host "[4/6] Executing backup with controlled concurrent write and Catch-Up Reconcile..." -ForegroundColor Yellow
$backupScript = Join-Path $PSScriptRoot "backup-system.ps1"
& powershell.exe -ExecutionPolicy Bypass -File $backupScript `
    -ContainerName $ContainerName `
    -DbName $TestDbName `
    -DbUser $DbUser `
    -S3Endpoint $S3Endpoint `
    -S3Bucket $TestS3Bucket `
    -OutputDir $backupOutputDir `
    -PostStorageHook $hookScriptPath

Remove-Item $hookScriptPath -Force -ErrorAction SilentlyContinue

if ($LASTEXITCODE -ne 0) {
    throw "Backup failed with exit code $LASTEXITCODE"
}

# 5. Restore into completely isolated target
Write-Host "[5/6] Restoring into isolated target environment..." -ForegroundColor Yellow
$restoreTargetDb = "df_concurrent_restored"
$restoreTargetBucket = "fitness-media-drill-restored"
$restoreScript = Join-Path $PSScriptRoot "restore-system.ps1"

& powershell.exe -ExecutionPolicy Bypass -File $restoreScript `
    -BackupDir $backupOutputDir `
    -ContainerName $ContainerName `
    -TargetDbName $restoreTargetDb `
    -DbUser $DbUser `
    -TargetS3Endpoint $S3Endpoint `
    -TargetS3Bucket $restoreTargetBucket `
    -Force

if ($LASTEXITCODE -ne 0) {
    throw "Restore failed with exit code $LASTEXITCODE"
}

# 6. Verify Referential Integrity and Checksums on Restored System
Write-Host "[6/6] Verifying referential integrity and byte parity on restored system..." -ForegroundColor Yellow

# Query media records from restored database
$restoredKeys = docker exec $ContainerName psql -U $DbUser -d $restoreTargetDb -t -A -c "SELECT object_key FROM fitness.media_files WHERE bucket_name = '$TestS3Bucket' AND deleted_at IS NULL;"
Write-Host "  Restored DB media records: $($restoredKeys -join ', ')" -ForegroundColor Gray

if ($restoredKeys -notcontains $fileAKey) {
    throw "FAIL: Restored DB is missing File A record!"
}
if ($restoredKeys -notcontains $fileBKey) {
    throw "FAIL: Restored DB is missing File B record!"
}

# Fetch File A from restored S3 bucket
$fetchFileA = [System.IO.Path]::GetTempFileName()
& $curlCommand -s -f "$S3Endpoint/$restoreTargetBucket/$fileAKey" -o "$fetchFileA"
$fetchedHashA = (Get-FileHash -Path $fetchFileA -Algorithm SHA256).Hash.ToLowerInvariant()
Remove-Item $fetchFileA -Force

# Fetch File B from restored S3 bucket
$fetchFileB = [System.IO.Path]::GetTempFileName()
& $curlCommand -s -f "$S3Endpoint/$restoreTargetBucket/$fileBKey" -o "$fetchFileB"
$fetchedHashB = (Get-FileHash -Path $fetchFileB -Algorithm SHA256).Hash.ToLowerInvariant()
Remove-Item $fetchFileB -Force

if ($fetchedHashA -ne $fileAHash) {
    throw "FAIL: File A checksum mismatch on restored S3! Expected: $fileAHash, Got: $fetchedHashA"
}
if ($fetchedHashB -ne $fileBHash) {
    throw "FAIL: File B checksum mismatch on restored S3! Expected: $fileBHash, Got: $fetchedHashB"
}

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  DRILL SUCCESSFUL!" -ForegroundColor Green
Write-Host "  File A (initial) & File B (concurrent upload) both restored." -ForegroundColor Green
Write-Host "  Checksums verified: File A ($fetchedHashA) and File B ($fetchedHashB)." -ForegroundColor Green
Write-Host "  Post-dump catch-up reconcile successfully prevented dangling pointer." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

# Cleanup drill databases
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $TestDbName;" 2>$null | Out-Null
docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $restoreTargetDb;" 2>$null | Out-Null
$ErrorActionPreference = $prevEAP
Remove-Item $backupOutputDir -Recurse -Force -ErrorAction SilentlyContinue
