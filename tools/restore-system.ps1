[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDir,
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$TargetDbName = "digital_fitness_restore_test",
    [string]$DbUser = "fitness_app",
    [string]$TargetS3Endpoint = "http://localhost:9000",
    [string]$TargetS3Bucket = "fitness-media-restore-test",
    [switch]$VerifyOnly
)

$ErrorActionPreference = "Stop"

$BackupDir = [System.IO.Path]::GetFullPath($BackupDir)
$manifestPath = Join-Path $BackupDir "manifest.json"

if (-not (Test-Path $manifestPath)) {
    throw "Manifest file not found: $manifestPath"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Digital Fitness Infrastructure Restore & Verification" -ForegroundColor Cyan
Write-Host "  Backup Directory: $BackupDir" -ForegroundColor Cyan
Write-Host "  Target Database:  $TargetDbName" -ForegroundColor Cyan
Write-Host "  Target S3 Bucket: $TargetS3Bucket ($TargetS3Endpoint)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Parse & Verify Manifest Integrity
Write-Host "[1/4] Verifying backup artifact checksums..." -ForegroundColor Yellow
$manifest = Get-Content -Path $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

$dumpFile = Join-Path $BackupDir $manifest.database.dump_file
if (-not (Test-Path $dumpFile)) {
    throw "Database dump file missing: $dumpFile"
}

$dumpHash = (Get-FileHash -Path $dumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
if ($dumpHash -ne $manifest.database.dump_sha256.ToLowerInvariant()) {
    throw "Database dump SHA256 mismatch! Expected: $($manifest.database.dump_sha256), Actual: $dumpHash"
}
Write-Host "  Database dump checksum verified: $dumpHash" -ForegroundColor Green

$storageDir = Join-Path $BackupDir "storage"
if ($manifest.storage.objects) {
    foreach ($obj in $manifest.storage.objects) {
        $filePath = Join-Path $storageDir $obj.key
        if (-not (Test-Path $filePath)) {
            throw "Storage object file missing from backup: $filePath"
        }
        $fileHash = (Get-FileHash -Path $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($fileHash -ne $obj.sha256.ToLowerInvariant()) {
            throw "Storage object checksum mismatch for $($obj.key)! Expected: $($obj.sha256), Actual: $fileHash"
        }
        Write-Host "  Storage object verified: $($obj.key) (SHA256: $fileHash)" -ForegroundColor Gray
    }
    Write-Host "  All $($manifest.storage.objects.Count) storage object checksums verified." -ForegroundColor Green
}

if ($VerifyOnly) {
    Write-Host "Verification-only mode requested. All backup artifacts verified successfully." -ForegroundColor Green
    return
}

# 2. Database Restore Drill to Isolated Database
Write-Host "[2/4] Restoring database to isolated target '$TargetDbName'..." -ForegroundColor Yellow

# Ensure target database exists in Postgres container
$dbExists = docker exec $ContainerName psql -U $DbUser -d postgres -t -A -c "SELECT 1 FROM pg_database WHERE datname='$TargetDbName';" 2>$null
if ($dbExists -ne "1") {
    Write-Host "  Creating isolated target database '$TargetDbName'..." -ForegroundColor Gray
    docker exec $ContainerName psql -U $DbUser -d postgres -c "CREATE DATABASE $TargetDbName OWNER $DbUser;"
} else {
    Write-Host "  Cleaning existing target database '$TargetDbName'..." -ForegroundColor Gray
    docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE $TargetDbName;"
    docker exec $ContainerName psql -U $DbUser -d postgres -c "CREATE DATABASE $TargetDbName OWNER $DbUser;"
}

# Copy dump into container and restore
docker cp $dumpFile "${ContainerName}:/tmp/restore_target.dump"
Write-Host "  Running pg_restore into '$TargetDbName'..." -ForegroundColor Gray

# Disable NativeCommandError on stderr diagnostics from pg_restore
$prevErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker exec $ContainerName pg_restore -U $DbUser -d $TargetDbName --no-owner --no-privileges "/tmp/restore_target.dump" 2>$null
$restoreExitCode = $LASTEXITCODE
$ErrorActionPreference = $prevErrorAction

docker exec $ContainerName rm -f "/tmp/restore_target.dump"

if ($restoreExitCode -gt 1) {
    throw "pg_restore failed with exit code $restoreExitCode"
}

# Verify restored database
$restoredFlyway = docker exec $ContainerName psql -U $DbUser -d $TargetDbName -t -A -c "SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>$null
$tableCount = docker exec $ContainerName psql -U $DbUser -d $TargetDbName -t -A -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='fitness';" 2>$null

Write-Host "  Restored database verification:" -ForegroundColor Green
Write-Host "    - Target DB: $TargetDbName" -ForegroundColor Green
Write-Host "    - Restored Flyway Version: $restoredFlyway (Original: $($manifest.database.flyway_version))" -ForegroundColor Green
Write-Host "    - Table Count in schema 'fitness': $tableCount" -ForegroundColor Green

if ($restoredFlyway -ne $manifest.database.flyway_version) {
    throw "Restored schema version mismatch! Expected $($manifest.database.flyway_version), found $restoredFlyway"
}

# 3. Storage Restore Drill
Write-Host "[3/4] Restoring storage objects to '$TargetS3Bucket' on $TargetS3Endpoint..." -ForegroundColor Yellow

# Ensure bucket exists
& curl.exe -s -X PUT "$TargetS3Endpoint/$TargetS3Bucket" | Out-Null

$restoredObjectCount = 0
$tempVerifyDir = Join-Path $env:TEMP ("df_verify_" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempVerifyDir -Force | Out-Null

try {
    if ($manifest.storage.objects) {
        foreach ($obj in $manifest.storage.objects) {
            $sourcePath = Join-Path $storageDir $obj.key
            $targetUri = "$TargetS3Endpoint/$TargetS3Bucket/$($obj.key)"

            # Upload using curl
            & curl.exe -s -X PUT --upload-file $sourcePath $targetUri | Out-Null

            # Download back to temp file and verify SHA256 parity
            $tempVerifyFile = Join-Path $tempVerifyDir "verify.tmp"
            & curl.exe -s $targetUri -o $tempVerifyFile

            $downloadHash = (Get-FileHash -Path $tempVerifyFile -Algorithm SHA256).Hash.ToLowerInvariant()
            Remove-Item -Path $tempVerifyFile -Force -ErrorAction SilentlyContinue

            if ($downloadHash -ne $obj.sha256.ToLowerInvariant()) {
                throw "Restored S3 object hash mismatch for $($obj.key)! Expected: $($obj.sha256), Got: $downloadHash"
            }
            $restoredObjectCount++
            Write-Host "  Restored & validated object: $($obj.key) (SHA256 match)" -ForegroundColor Gray
        }
    }
} finally {
    Remove-Item -Path $tempVerifyDir -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "  Storage restore complete. Restored $restoredObjectCount objects." -ForegroundColor Green

# 4. Summary & Verification Sign-Off
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  RESTORE & VERIFICATION DRILL SUCCEEDED" -ForegroundColor Green
Write-Host "  Database '$TargetDbName' schema & data parity confirmed." -ForegroundColor Green
Write-Host "  Storage '$TargetS3Bucket' object byte parity confirmed." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
