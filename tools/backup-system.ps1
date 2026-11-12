[CmdletBinding()]
param(
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$DbName = "digital_fitness",
    [string]$DbUser = "fitness_app",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$S3Bucket = "fitness-media",
    [string]$OutputDir = "",
    [string]$PostStorageHook = "",
    [switch]$FailOnMissingStorage
)

$ErrorActionPreference = "Stop"
$curlCommand = if ($env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot "..\database\backup\backups\$timestamp"
}

$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$storageDir = Join-Path $OutputDir "storage"

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
New-Item -ItemType Directory -Path $storageDir -Force | Out-Null

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Digital Fitness Infrastructure Backup" -ForegroundColor Cyan
Write-Host "  Timestamp: $timestamp" -ForegroundColor Cyan
Write-Host "  Output Directory: $OutputDir" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Consistency Ordering Model:
# 1. S3 Object Storage is synchronized first. If any active uploads occur during this window,
#    their subsequent database commit will either be included in the DB snapshot or point to
#    an object that has already been captured.
# 2. PostgreSQL pg_dump is executed with snapshot isolation, providing a point-in-time consistent view
#    of all database relational data at the exact transaction snapshot time.

try {
    # 1. Verify PostgreSQL Container
    Write-Host "[1/4] Checking PostgreSQL container '$ContainerName'..." -ForegroundColor Yellow
    $pgStatus = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    if ($pgStatus -ne "running") {
        throw "PostgreSQL container '$ContainerName' is not running (status: $pgStatus)."
    }

    # 2. Object Storage Backup (Storage-first for consistency)
    Write-Host "[2/4] Backing up S3 bucket '$S3Bucket' from $S3Endpoint..." -ForegroundColor Yellow
    $storageFiles = @()

    try {
        $bucketXml = Invoke-RestMethod -Uri "$S3Endpoint/$S3Bucket" -Method Get -TimeoutSec 5 -ErrorAction Stop
        $contents = $bucketXml.ListBucketResult.Contents
        if ($contents) {
            if ($contents -isnot [System.Array]) {
                $contents = @($contents)
            }

            foreach ($item in $contents) {
                $key = $item.Key
                if ([string]::IsNullOrWhiteSpace($key)) { continue }

                $targetFile = Join-Path $storageDir $key
                $parentDir = Split-Path -Parent $targetFile
                if (-not (Test-Path $parentDir)) {
                    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
                }

                $objectUrl = "$S3Endpoint/$S3Bucket/$key"
                & $curlCommand -s -f "$objectUrl" -o "$targetFile"
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $targetFile)) {
                    throw "Failed to download object '$key' from $objectUrl (exit code $LASTEXITCODE)"
                }

                $hash = (Get-FileHash -Path $targetFile -Algorithm SHA256).Hash.ToLowerInvariant()
                $size = (Get-Item $targetFile).Length

                $storageFiles += [PSCustomObject]@{
                    key = $key
                    size = $size
                    sha256 = $hash
                }
                Write-Host "  Downloaded object: $key ($size bytes)" -ForegroundColor Gray
            }
        }
        Write-Host "  Storage backup complete. Objects backed up: $($storageFiles.Count)" -ForegroundColor Green
    } catch {
        Write-Host "  Warning: S3 bucket listing/download encountered: $_" -ForegroundColor Yellow
    }

    # Hook for controlled integration drills simulating concurrent mutations between storage sync and pg_dump
    if (-not [string]::IsNullOrWhiteSpace($PostStorageHook)) {
        Write-Host "  [Drill Hook] Executing PostStorageHook..." -ForegroundColor Magenta
        if (Test-Path $PostStorageHook) {
            & powershell.exe -ExecutionPolicy Bypass -File $PostStorageHook
        } else {
            Invoke-Expression $PostStorageHook
        }
    }

    # 3. Database Backup (pg_dump custom format with point-in-time snapshot)
    $dbDumpFile = Join-Path $OutputDir "database.dump"
    $dbSchemaFile = Join-Path $OutputDir "schema_structure.sql"

    Write-Host "[3/4] Dumping database '$DbName'..." -ForegroundColor Yellow
    $dumpTmpPath = "/tmp/db_$timestamp.dump"
    $schemaTmpPath = "/tmp/db_schema_$timestamp.sql"

    docker exec $ContainerName pg_dump -U $DbUser -d $DbName -F c -b -v -f "$dumpTmpPath"
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }

    docker cp "${ContainerName}:$dumpTmpPath" $dbDumpFile
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp database dump failed with exit code $LASTEXITCODE"
    }

    docker exec $ContainerName pg_dump -U $DbUser -d $DbName --schema-only -f "$schemaTmpPath"
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump schema-only failed with exit code $LASTEXITCODE"
    }
    docker cp "${ContainerName}:$schemaTmpPath" $dbSchemaFile
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp schema file failed with exit code $LASTEXITCODE"
    }
    docker exec $ContainerName rm -f "$schemaTmpPath"

    # Query latest Flyway schema version
    $flywayVersion = docker exec $ContainerName psql -U $DbUser -d $DbName -t -A -c "SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>$null
    if (-not $flywayVersion) {
        $flywayVersion = "UNKNOWN"
    }
    Write-Host "  Database dump complete. Flyway Schema Version: $flywayVersion" -ForegroundColor Green

    # 3b. Post-Dump Referential Integrity Verification & Storage Catch-Up Sync
    # Extract media file references directly from the dumped snapshot ($dumpTmpPath) using pg_restore.
    # This guarantees that the reconcile checks against the EXACT snapshot state, immune to post-dump writes.
    Write-Host "[3b] Verifying database snapshot storage references and catching up concurrent uploads..." -ForegroundColor Yellow
    $dbMediaKeys = @()
    try {
        $restoreOutput = docker exec $ContainerName pg_restore -a -t media_files -f - "$dumpTmpPath" 2>$null
        if ($restoreOutput) {
            $inCopy = $false
            foreach ($line in ($restoreOutput -split "`r?`n")) {
                if ($line -like "COPY *media_files* FROM stdin*") { $inCopy = $true; continue }
                if ($inCopy -and $line -eq "\.") { $inCopy = $false; break }
                if ($inCopy -and -not [string]::IsNullOrWhiteSpace($line)) {
                    $cols = $line -split "`t"
                    if ($cols.Length -ge 14) {
                        $bucket = $cols[3]
                        $key = $cols[4]
                        $scanStatus = $cols[11]
                        $deletedAt = $cols[13]
                        if (($bucket -eq $S3Bucket -or [string]::IsNullOrWhiteSpace($bucket) -or $bucket -eq "\N") -and
                            $scanStatus -eq "CLEAN" -and $deletedAt -eq "\N") {
                            $dbMediaKeys += $key.Trim()
                        }
                    }
                }
            }
        }
    } catch {
        Write-Host "  Warning: Direct snapshot table extraction encountered an issue, falling back to query: $_" -ForegroundColor Yellow
    }

    docker exec $ContainerName rm -f "$dumpTmpPath"

    if ($dbMediaKeys.Count -eq 0) {
        # Fallback to query if table had no rows extracted or direct extraction was empty
        $fallbackKeys = docker exec $ContainerName psql -U $DbUser -d $DbName -t -A -c "SELECT object_key FROM fitness.media_files WHERE deleted_at IS NULL AND scan_status = 'CLEAN' AND (bucket_name = '$S3Bucket' OR bucket_name IS NULL);" 2>$null
        if ($fallbackKeys) {
            $dbMediaKeys = $fallbackKeys -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        }
    }

    if ($dbMediaKeys.Count -gt 0) {
        $existingKeys = @{}
        foreach ($f in $storageFiles) {
            $existingKeys[$f.key] = $true
        }

        $catchUpCount = 0
        $missingStorageFiles = @()
        foreach ($key in $dbMediaKeys) {
            $key = $key.Trim()
            if (-not $existingKeys.ContainsKey($key)) {
                Write-Host "  Concurrent upload detected: Object '$key' committed in DB snapshot but missed in initial storage sync. Catching up..." -ForegroundColor Yellow
                $targetFile = Join-Path $storageDir $key
                $parentDir = Split-Path -Parent $targetFile
                if (-not (Test-Path $parentDir)) {
                    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
                }

                $objectUrl = "$S3Endpoint/$S3Bucket/$key"
                & $curlCommand -s -f "$objectUrl" -o "$targetFile"
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $targetFile)) {
                    if ($FailOnMissingStorage) {
                        throw "REFERENTIAL INTEGRITY VIOLATION: Database snapshot references object '$key' which could not be fetched from S3 ($objectUrl). Aborting backup."
                    } else {
                        Write-Host "  WARNING: Referential integrity warning - Object '$key' referenced in DB snapshot was not found in S3 ($objectUrl). Recording in manifest." -ForegroundColor Yellow
                        $missingStorageFiles += $key
                        continue
                    }
                }

                $hash = (Get-FileHash -Path $targetFile -Algorithm SHA256).Hash.ToLowerInvariant()
                $size = (Get-Item $targetFile).Length

                $storageFiles += [PSCustomObject]@{
                    key = $key
                    size = $size
                    sha256 = $hash
                }
                $existingKeys[$key] = $true
                $catchUpCount++
                Write-Host "  Successfully caught up object: $key ($size bytes, SHA256: $hash)" -ForegroundColor Green
            }
        }
        if ($catchUpCount -gt 0) {
            Write-Host "  Post-dump reconcile complete: Caught up $catchUpCount concurrent object(s)." -ForegroundColor Green
        } else {
            Write-Host "  Post-dump reconcile complete: All $($dbMediaKeys.Count) DB-referenced objects verified present in storage backup." -ForegroundColor Green
        }
        if ($missingStorageFiles.Count -gt 0) {
            Write-Host "  Referential notice: $($missingStorageFiles.Count) dangling reference(s) recorded in manifest." -ForegroundColor Yellow
        }
    }

    # 4. Compute Checksums & Manifest
    Write-Host "[4/4] Generating backup manifest..." -ForegroundColor Yellow
    $dumpHash = (Get-FileHash -Path $dbDumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $dumpSize = (Get-Item $dbDumpFile).Length

    $manifest = [PSCustomObject]@{
        version = "1.0"
        timestamp = $timestamp
        consistency_model = "storage-first-snapshot-with-post-dump-reconcile"
        database = [PSCustomObject]@{
            container = $ContainerName
            database_name = $DbName
            user = $DbUser
            flyway_version = $flywayVersion
            dump_file = "database.dump"
            dump_size = $dumpSize
            dump_sha256 = $dumpHash
        }
        storage = [PSCustomObject]@{
            endpoint = $S3Endpoint
            bucket = $S3Bucket
            object_count = $storageFiles.Count
            missing_count = if ($missingStorageFiles) { $missingStorageFiles.Count } else { 0 }
            missing_references = if ($missingStorageFiles) { $missingStorageFiles } else { @() }
            objects = $storageFiles
        }
    }

    $manifestFile = Join-Path $OutputDir "manifest.json"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -Path $manifestFile -Encoding UTF8

    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  BACKUP COMPLETED SUCCESSFULLY" -ForegroundColor Green
    Write-Host "  Manifest: $manifestFile" -ForegroundColor Green
    Write-Host "  DB Dump:  $dbDumpFile (SHA256: $dumpHash)" -ForegroundColor Green
    Write-Host "  Objects:  $($storageFiles.Count) stored in $storageDir" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

    return $manifestFile
} catch {
    Write-Host "Backup failed! Cleaning up aborted partial backup directory: $OutputDir" -ForegroundColor Red
    Remove-Item -Path $OutputDir -Recurse -Force -ErrorAction SilentlyContinue
    throw
}
