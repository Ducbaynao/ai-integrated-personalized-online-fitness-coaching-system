[CmdletBinding()]
param(
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$DbName = "digital_fitness",
    [string]$DbUser = "fitness_app",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$S3Bucket = "fitness-media",
    [string]$OutputDir = "",
    [string]$PostStorageHook = "",
    [string]$PostDumpHook = "",
    [string]$PrePublishHook = "",
    [string]$PublishFaultInjection = "",
    [switch]$FailOnMissingStorage,
    [switch]$AllowIncompleteStorage,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$curlCommand = if ($env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }

# Helper: Unescape PostgreSQL COPY text format
function Unescape-PostgresCopyField([string]$value) {
    if ($value -eq '\N' -or $value -eq $null) { return $null }
    if (-not $value.Contains('\')) { return $value }

    $sb = [System.Text.StringBuilder]::new()
    $i = 0
    $len = $value.Length
    while ($i -lt $len) {
        $c = $value[$i]
        if ($c -eq '\' -and ($i + 1 -lt $len)) {
            $next = $value[$i + 1]
            switch ($next) {
                'b' { [void]$sb.Append([char]8); $i += 2; continue }
                'f' { [void]$sb.Append([char]12); $i += 2; continue }
                'n' { [void]$sb.Append("`n"); $i += 2; continue }
                'r' { [void]$sb.Append("`r"); $i += 2; continue }
                't' { [void]$sb.Append("`t"); $i += 2; continue }
                'v' { [void]$sb.Append([char]11); $i += 2; continue }
                '\' { [void]$sb.Append('\'); $i += 2; continue }
                default {
                    if ($next -ge '0' -and $next -le '7') {
                        $octalStr = "$next"
                        $j = $i + 2
                        while ($j -lt $len -and ($j - $i) -le 3 -and $value[$j] -ge '0' -and $value[$j] -le '7') {
                            $octalStr += $value[$j]
                            $j++
                        }
                        $byteVal = [Convert]::ToInt32($octalStr, 8)
                        [void]$sb.Append([char]$byteVal)
                        $i = $j
                        continue
                    } else {
                        [void]$sb.Append($next)
                        $i += 2
                        continue
                    }
                }
            }
        } else {
            [void]$sb.Append($c)
            $i++
        }
    }
    return $sb.ToString()
}

# Helper: Encode S3 URI path segments
function Get-S3ObjectUrl([string]$endpoint, [string]$bucket, [string]$key) {
    $segments = $key.Split('/') | ForEach-Object { [System.Uri]::EscapeDataString($_) }
    $encodedKey = $segments -join '/'
    return "$($endpoint.TrimEnd('/'))/$bucket/$encodedKey"
}

# Helper: Parse media_files table snapshot data from pg_restore text output
function Parse-PostgresSnapshotMediaFiles {
    param(
        $RestoreOutput,
        [Parameter(Mandatory=$true)][string]$S3Bucket
    )

    $dbMediaObjects = @()
    $inCopy = $false
    $foundCopyHeader = $false
    $foundCopyTerminator = $false
    $colMap = @{}

    if ($RestoreOutput) {
        $lines = if ($RestoreOutput -is [string]) {
            $RestoreOutput -split "`r?`n"
        } else {
            @($RestoreOutput | ForEach-Object { if ($null -ne $_) { $_.ToString() } })
        }

        foreach ($line in $lines) {
            if (-not $inCopy) {
                if ($line -match '^\s*COPY\s+(?:(?:"?fitness"?\.)?"?media_files"?)\s*\(([^)]+)\)\s*FROM\s+stdin') {
                    $foundCopyHeader = $true
                    $rawCols = $matches[1].Split(',') | ForEach-Object { $_.Trim(' "''').ToLowerInvariant() }
                    for ($i = 0; $i -lt $rawCols.Length; $i++) {
                        $colMap[$rawCols[$i]] = $i
                    }
                    if (-not $colMap.ContainsKey('object_key') -or -not $colMap.ContainsKey('scan_status') -or -not $colMap.ContainsKey('deleted_at')) {
                        throw "CRITICAL SNAPSHOT PARSE ERROR: Snapshot media_files COPY header missing required columns: $($matches[1])"
                    }
                    $inCopy = $true
                    continue
                }
            } else {
                if ($line.Trim() -eq '\.') {
                    $inCopy = $false
                    $foundCopyTerminator = $true
                    break
                }
                if ([string]::IsNullOrWhiteSpace($line)) { continue }

                $cols = $line.Split("`t")
                $maxIdx = 0
                foreach ($v in $colMap.Values) { if ($v -gt $maxIdx) { $maxIdx = $v } }
                if ($cols.Length -le $maxIdx) {
                    throw "CRITICAL SNAPSHOT PARSE ERROR: Row data has fewer columns ($($cols.Length)) than expected ($($maxIdx + 1)): $line"
                }

                $key = Unescape-PostgresCopyField $cols[$colMap['object_key']]
                $scanStatus = Unescape-PostgresCopyField $cols[$colMap['scan_status']]
                $deletedAt = Unescape-PostgresCopyField $cols[$colMap['deleted_at']]
                $bucket = if ($colMap.ContainsKey('bucket_name')) { Unescape-PostgresCopyField $cols[$colMap['bucket_name']] } else { $null }
                $sizeBytes = if ($colMap.ContainsKey('size_bytes')) { Unescape-PostgresCopyField $cols[$colMap['size_bytes']] } else { $null }
                $checksumSha = if ($colMap.ContainsKey('checksum_sha256')) { Unescape-PostgresCopyField $cols[$colMap['checksum_sha256']] } else { $null }

                if ([string]::IsNullOrEmpty($deletedAt) -and $scanStatus -eq 'CLEAN' -and
                    ([string]::IsNullOrEmpty($bucket) -or $bucket -eq $S3Bucket)) {
                    if (-not [string]::IsNullOrWhiteSpace($key)) {
                        $parsedSize = $null
                        if ($sizeBytes -and $sizeBytes -match '^\d+$') {
                            $parsedSize = [int64]$sizeBytes
                        }
                        $dbMediaObjects += [PSCustomObject]@{
                            key = $key
                            bucket = $bucket
                            sizeBytes = $parsedSize
                            checksumSha = if (-not [string]::IsNullOrWhiteSpace($checksumSha)) { $checksumSha.Trim().ToLowerInvariant() } else { $null }
                        }
                    }
                }
            }
        }
    }

    if (-not $foundCopyHeader) {
        throw "CRITICAL SNAPSHOT PARSE ERROR: Valid COPY header for media_files was not found in pg_restore output."
    }
    if ($foundCopyHeader -and -not $foundCopyTerminator) {
        throw "CRITICAL SNAPSHOT PARSE ERROR: COPY block for media_files is truncated: missing terminating '\.' line."
    }

    return $dbMediaObjects
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot "..\database\backup\backups\$timestamp"
}

# 1. Resolve and Validate Output Directory
$resolvedOutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$rootPath = [System.IO.Path]::GetPathRoot($resolvedOutputDir)
if ($resolvedOutputDir.TrimEnd('\', '/') -eq $rootPath.TrimEnd('\', '/')) {
    throw "SAFETY ABORT: OutputDir cannot be filesystem root: '$resolvedOutputDir'"
}
$currentDir = (Get-Location).Path
if ($resolvedOutputDir.TrimEnd('\', '/') -eq $currentDir.TrimEnd('\', '/')) {
    throw "SAFETY ABORT: OutputDir cannot be the current working directory: '$resolvedOutputDir'"
}

$parentDir = Split-Path -Parent $resolvedOutputDir
if ([string]::IsNullOrWhiteSpace($parentDir)) {
    throw "SAFETY ABORT: Invalid parent path for OutputDir: '$resolvedOutputDir'"
}
if (-not (Test-Path -LiteralPath $parentDir)) {
    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
}

$destExistedBefore = Test-Path -LiteralPath $resolvedOutputDir
if ($destExistedBefore) {
    $targetItem = Get-Item -LiteralPath $resolvedOutputDir -Force -ErrorAction SilentlyContinue
    if ($targetItem -and ($targetItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw "SAFETY ABORT: OutputDir cannot be a symlink or junction: '$resolvedOutputDir'"
    }
    $existingItems = Get-ChildItem -LiteralPath $resolvedOutputDir -Force
    if ($existingItems.Count -gt 0 -and -not $Force) {
        throw "SAFETY ABORT: Output directory '$resolvedOutputDir' already exists and is not empty ($($existingItems.Count) item(s) found). Specify an empty/new directory or pass -Force to overwrite."
    }
}

# 2. Establish Isolated Staging Directory for this Run ID
$runId = [System.Guid]::NewGuid().ToString("N")
$stagingDirName = ".staging_" + (Split-Path -Leaf $resolvedOutputDir) + "_" + $runId
$stagingDir = Join-Path $parentDir $stagingDirName
$stagingStorageDir = Join-Path $stagingDir "storage"

New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null
New-Item -ItemType Directory -Path $stagingStorageDir -Force | Out-Null

$dumpTmpPath = $null
$schemaTmpPath = $null
$oldBackupArchive = $null
$oldBackupMoved = $false
$newBackupPublished = $false
$rollbackSucceeded = $false
$lockStream = $null
$lockFilePath = $null

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Digital Fitness Infrastructure Backup" -ForegroundColor Cyan
Write-Host "  Timestamp: $timestamp" -ForegroundColor Cyan
Write-Host "  Run ID:    $runId" -ForegroundColor Cyan
Write-Host "  Staging:   $stagingDir" -ForegroundColor Cyan
Write-Host "  Target:    $resolvedOutputDir" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

try {
    # Step 1: Verify PostgreSQL Container
    Write-Host "[1/4] Checking PostgreSQL container '$ContainerName'..." -ForegroundColor Yellow
    $pgStatus = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    if ($pgStatus -ne "running") {
        throw "PostgreSQL container '$ContainerName' is not running (status: $pgStatus)."
    }

    # Step 2: Object Storage Backup (Storage-first for consistency)
    Write-Host "[2/4] Backing up S3 bucket '$S3Bucket' from $S3Endpoint..." -ForegroundColor Yellow
    $storageFiles = @()
    $missingStorageFiles = @()

    try {
        $resp = Invoke-WebRequest -Uri "$S3Endpoint/$S3Bucket" -Method Get -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        $reader = [System.IO.StreamReader]::new($resp.RawContentStream, [System.Text.Encoding]::UTF8)
        [xml]$bucketXml = $reader.ReadToEnd()
        $contents = $bucketXml.ListBucketResult.Contents
        if ($contents) {
            if ($contents -isnot [System.Array]) {
                $contents = @($contents)
            }

            foreach ($item in $contents) {
                $key = $item.Key
                if ([string]::IsNullOrWhiteSpace($key)) { continue }

                $targetFile = Join-Path $stagingStorageDir $key
                $fileParentDir = Split-Path -Parent $targetFile
                if (-not (Test-Path -LiteralPath $fileParentDir)) {
                    New-Item -ItemType Directory -Path $fileParentDir -Force | Out-Null
                }

                $objectUrl = Get-S3ObjectUrl $S3Endpoint $S3Bucket $key
                & $curlCommand -s -f -g "$objectUrl" -o "$targetFile"
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $targetFile)) {
                    if (-not $AllowIncompleteStorage) {
                        throw "Failed to download object '$key' from $objectUrl (exit code $LASTEXITCODE)"
                    } else {
                        Write-Host "  WARNING: Failed to download object '$key' from $objectUrl. Recording as missing." -ForegroundColor Yellow
                        $missingStorageFiles += $key
                        continue
                    }
                }

                $hash = (Get-FileHash -LiteralPath $targetFile -Algorithm SHA256).Hash.ToLowerInvariant()
                $size = (Get-Item -LiteralPath $targetFile).Length

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
        if (-not $AllowIncompleteStorage) {
            throw "STORAGE BACKUP FAILURE: Failed to list/download S3 bucket '$S3Bucket' from $($S3Endpoint): $_"
        } else {
            Write-Host "  WARNING: S3 bucket listing/download encountered error: $_" -ForegroundColor Yellow
        }
    }

    # Hook for controlled integration drills simulating concurrent mutations between storage sync and pg_dump
    if (-not [string]::IsNullOrWhiteSpace($PostStorageHook)) {
        Write-Host "  [Drill Hook] Executing PostStorageHook..." -ForegroundColor Magenta
        if (Test-Path -LiteralPath $PostStorageHook) {
            & powershell.exe -ExecutionPolicy Bypass -File $PostStorageHook
        } else {
            Invoke-Expression $PostStorageHook
        }
    }

    # Step 3: Database Backup (pg_dump custom format with point-in-time snapshot)
    $dbDumpFile = Join-Path $stagingDir "database.dump"
    $dbSchemaFile = Join-Path $stagingDir "schema_structure.sql"

    Write-Host "[3/4] Dumping database '$DbName'..." -ForegroundColor Yellow
    $dumpTmpPath = "/tmp/db_$timestamp`_$runId.dump"
    $schemaTmpPath = "/tmp/db_schema_$timestamp`_$runId.sql"

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
    $schemaTmpPath = $null

    # Query latest Flyway schema version
    $flywayVersion = docker exec $ContainerName psql -U $DbUser -d $DbName -t -A -c "SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>$null
    if (-not $flywayVersion) {
        $flywayVersion = "UNKNOWN"
    }
    Write-Host "  Database dump complete. Flyway Schema Version: $flywayVersion" -ForegroundColor Green

    # Hook for controlled integration drills simulating mutations or faults between pg_dump completion and reconcile
    if (-not [string]::IsNullOrWhiteSpace($PostDumpHook)) {
        Write-Host "  [Drill Hook] Executing PostDumpHook..." -ForegroundColor Magenta
        if (Test-Path -LiteralPath $PostDumpHook) {
            & powershell.exe -ExecutionPolicy Bypass -File $PostDumpHook
        } else {
            Invoke-Expression $PostDumpHook
        }
        if ($LASTEXITCODE -ne 0) {
            throw "PostDumpHook failed with exit code $LASTEXITCODE"
        }
    }

    # Step 3b: Post-Dump Snapshot Reconcile & Integrity Verification
    # Extract media file references directly from the dumped snapshot ($dumpTmpPath) using pg_restore.
    # NEVER fallback to querying live database - reconcile must remain 100% snapshot-consistent.
    Write-Host "[3b] Verifying database snapshot storage references and catching up concurrent uploads..." -ForegroundColor Yellow

    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $restoreOutput = docker exec $ContainerName pg_restore -a -t media_files -f - "$dumpTmpPath" 2>&1
    $restoreExitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    # Remove temporary dump in container immediately after restore check
    docker exec $ContainerName rm -f "$dumpTmpPath"
    $dumpTmpPath = $null

    if ($restoreExitCode -ne 0) {
        throw "CRITICAL SNAPSHOT EXTRACTION ERROR: pg_restore failed with exit code $($restoreExitCode): $($restoreOutput -join ' ')"
    }

    $dbMediaObjects = Parse-PostgresSnapshotMediaFiles -RestoreOutput $restoreOutput -S3Bucket $S3Bucket

    Write-Host "  Snapshot referenced media files count: $($dbMediaObjects.Count)" -ForegroundColor Gray

    # Map existing storage files by key
    $existingStorageMap = @{}
    foreach ($f in $storageFiles) {
        $existingStorageMap[$f.key] = $f
    }

    $catchUpCount = 0

    foreach ($dbObj in $dbMediaObjects) {
        $key = $dbObj.key
        if ($existingStorageMap.ContainsKey($key)) {
            $storedFile = $existingStorageMap[$key]

            # Validate size if present in DB snapshot metadata
            if ($dbObj.sizeBytes -ne $null -and $storedFile.size -ne $dbObj.sizeBytes) {
                $msg = "DATA INTEGRITY CORRUPTION: Object '$key' size mismatch! Snapshot DB expected: $($dbObj.sizeBytes) bytes, Storage object has: $($storedFile.size) bytes."
                if (-not $AllowIncompleteStorage) {
                    throw $msg
                } else {
                    Write-Host "  WARNING: $msg" -ForegroundColor Yellow
                    $missingStorageFiles += $key
                }
            }

            # Validate checksum if present in DB snapshot metadata
            if ($dbObj.checksumSha -ne $null -and $storedFile.sha256.ToLowerInvariant() -ne $dbObj.checksumSha) {
                $msg = "DATA INTEGRITY CORRUPTION: Object '$key' SHA256 checksum mismatch! Snapshot DB expected: $($dbObj.checksumSha), Storage object has: $($storedFile.sha256)."
                if (-not $AllowIncompleteStorage) {
                    throw $msg
                } else {
                    Write-Host "  WARNING: $msg" -ForegroundColor Yellow
                    $missingStorageFiles += $key
                }
            }
        } else {
            # Object was committed in DB snapshot but missed in initial storage sync -> Catch-up needed!
            Write-Host "  Concurrent upload detected: Object '$key' committed in DB snapshot but missed in initial storage sync. Catching up..." -ForegroundColor Yellow
            $targetFile = Join-Path $stagingStorageDir $key
            $fileParentDir = Split-Path -Parent $targetFile
            if (-not (Test-Path -LiteralPath $fileParentDir)) {
                New-Item -ItemType Directory -Path $fileParentDir -Force | Out-Null
            }

            $objectUrl = Get-S3ObjectUrl $S3Endpoint $S3Bucket $key
            & $curlCommand -s -f -g "$objectUrl" -o "$targetFile"
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $targetFile)) {
                $msg = "REFERENTIAL INTEGRITY VIOLATION: Database snapshot references object '$key' which could not be fetched from S3 ($objectUrl)."
                if (-not $AllowIncompleteStorage) {
                    throw $msg
                } else {
                    Write-Host "  WARNING: $msg. Recording in manifest." -ForegroundColor Yellow
                    $missingStorageFiles += $key
                    continue
                }
            }

            $downloadHash = (Get-FileHash -LiteralPath $targetFile -Algorithm SHA256).Hash.ToLowerInvariant()
            $downloadSize = (Get-Item -LiteralPath $targetFile).Length

            # Validate size if present in DB snapshot metadata
            if ($dbObj.sizeBytes -ne $null -and $downloadSize -ne $dbObj.sizeBytes) {
                $msg = "DATA INTEGRITY CORRUPTION: Caught-up object '$key' size mismatch! Snapshot DB expected: $($dbObj.sizeBytes) bytes, Downloaded has: $downloadSize bytes."
                if (-not $AllowIncompleteStorage) {
                    throw $msg
                } else {
                    Write-Host "  WARNING: $msg" -ForegroundColor Yellow
                    $missingStorageFiles += $key
                }
            }

            # Validate checksum if present in DB snapshot metadata
            if ($dbObj.checksumSha -ne $null -and $downloadHash -ne $dbObj.checksumSha) {
                $msg = "DATA INTEGRITY CORRUPTION: Caught-up object '$key' SHA256 mismatch! Snapshot DB expected: $($dbObj.checksumSha), Downloaded has: $downloadHash."
                if (-not $AllowIncompleteStorage) {
                    throw $msg
                } else {
                    Write-Host "  WARNING: $msg" -ForegroundColor Yellow
                    $missingStorageFiles += $key
                }
            }

            $newStoredObj = [PSCustomObject]@{
                key = $key
                size = $downloadSize
                sha256 = $downloadHash
            }
            $storageFiles += $newStoredObj
            $existingStorageMap[$key] = $newStoredObj
            $catchUpCount++
            Write-Host "  Successfully caught up object: $key ($downloadSize bytes, SHA256: $downloadHash)" -ForegroundColor Green
        }
    }

    # Final verification of referential completeness
    if ($missingStorageFiles.Count -gt 0 -and -not $AllowIncompleteStorage) {
        throw "REFERENTIAL INTEGRITY VIOLATION: $($missingStorageFiles.Count) object(s) referenced in database snapshot are missing from storage ($($missingStorageFiles -join ', ')). Aborting backup."
    }

    if ($catchUpCount -gt 0) {
        Write-Host "  Post-dump reconcile complete: Caught up $catchUpCount concurrent object(s)." -ForegroundColor Green
    } else {
        Write-Host "  Post-dump reconcile complete: All $($dbMediaObjects.Count) DB-referenced objects verified present in storage backup." -ForegroundColor Green
    }

    # Step 4: Compute Checksums & Manifest
    Write-Host "[4/4] Generating backup manifest..." -ForegroundColor Yellow
    $dumpHash = (Get-FileHash -LiteralPath $dbDumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $dumpSize = (Get-Item -LiteralPath $dbDumpFile).Length
    $schemaHash = (Get-FileHash -LiteralPath $dbSchemaFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $schemaSize = (Get-Item -LiteralPath $dbSchemaFile).Length

    $completenessStatus = if ($missingStorageFiles.Count -gt 0) { "INCOMPLETE" } else { "COMPLETE" }

    $manifest = [PSCustomObject]@{
        version = "1.0"
        status = $completenessStatus
        completeness = $completenessStatus
        timestamp = $timestamp
        run_id = $runId
        consistency_model = "storage-first-snapshot-with-post-dump-reconcile"
        database = [PSCustomObject]@{
            container = $ContainerName
            database_name = $DbName
            user = $DbUser
            flyway_version = $flywayVersion
            dump_file = "database.dump"
            dump_size = $dumpSize
            dump_sha256 = $dumpHash
            schema_file = "schema_structure.sql"
            schema_size = $schemaSize
            schema_sha256 = $schemaHash
        }
        storage = [PSCustomObject]@{
            endpoint = $S3Endpoint
            bucket = $S3Bucket
            object_count = $storageFiles.Count
            missing_count = $missingStorageFiles.Count
            missing_references = $missingStorageFiles
            objects = $storageFiles
        }
    }

    $manifestFile = Join-Path $stagingDir "manifest.json"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestFile -Encoding UTF8

    # Step 5: Publication / Commit Phase
    # Staging has succeeded and passed all verification checks. Safely publish to $resolvedOutputDir.

    # Execute PrePublishHook if provided (e.g. for deterministic barrier synchronization before publication)
    if (-not [string]::IsNullOrWhiteSpace($PrePublishHook)) {
        Write-Host "  Executing PrePublishHook..." -ForegroundColor Yellow
        Invoke-Expression $PrePublishHook
    }

    Write-Host "[Commit] Acquiring publication lock for '$resolvedOutputDir'..." -ForegroundColor Yellow
    $lockFilePath = Join-Path $parentDir (".publish_lock_" + (Split-Path -Leaf $resolvedOutputDir) + ".lock")
    try {
        $lockStream = [System.IO.FileStream]::new(
            $lockFilePath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch [System.IO.IOException] {
        throw "PUBLICATION LOCK CONTENTION: Another process is currently publishing to '$resolvedOutputDir'. Publication aborted."
    }

    Write-Host "[Commit] Publishing verified backup to '$resolvedOutputDir'..." -ForegroundColor Yellow

    # Pre-publication re-check of destination state while holding publication lock
    if (Test-Path -LiteralPath $resolvedOutputDir) {
        $targetItem = Get-Item -LiteralPath $resolvedOutputDir -Force -ErrorAction SilentlyContinue
        if ($targetItem -and ($targetItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw "SAFETY ABORT: OutputDir cannot be a symlink or junction: '$resolvedOutputDir'"
        }
        $existingItemsAtPublish = Get-ChildItem -LiteralPath $resolvedOutputDir -Force
        if ($existingItemsAtPublish.Count -gt 0 -and -not $Force) {
            throw "SAFETY ABORT: Output directory '$resolvedOutputDir' already exists at publication time ($($existingItemsAtPublish.Count) item(s) found). Publication aborted to preserve existing backup. Specify a new directory or pass -Force."
        }
    }

    try {
        if (Test-Path -LiteralPath $resolvedOutputDir) {
            # Destination exists (and -Force was provided or directory is empty):
            # Safely move preexisting OutputDir away to a temporary archive using [System.IO.Directory]::Move
            $oldBackupArchive = Join-Path $parentDir (".replace_old_" + (Split-Path -Leaf $resolvedOutputDir) + "_" + $runId)
            [System.IO.Directory]::Move($resolvedOutputDir, $oldBackupArchive)
            $oldBackupMoved = $true
            Write-Host "  Preserved preexisting backup at: $oldBackupArchive" -ForegroundColor Yellow
        }

        # Fault injection hook for testing publication failure/rollback
        if ($PublishFaultInjection -eq "FAIL_MOVE_STAGING") {
            throw "FAULT_INJECTION: Simulated move failure during publication."
        }

        # Atomically move verified staging directory to final $resolvedOutputDir.
        # [System.IO.Directory]::Move guarantees that staging will NEVER be nested as a child directory
        # and fails immediately with IOException if $resolvedOutputDir exists.
        [System.IO.Directory]::Move($stagingDir, $resolvedOutputDir)

        # Fault injection hook for testing blocked rollback
        if ($PublishFaultInjection -eq "BLOCK_ROLLBACK") {
            throw "FAULT_INJECTION: Simulated publication failure with blocked rollback."
        }

        # In-place validation of final publication
        $finalManifestFile = Join-Path $resolvedOutputDir "manifest.json"
        $finalDbDumpFile = Join-Path $resolvedOutputDir "database.dump"
        $finalStorageDir = Join-Path $resolvedOutputDir "storage"

        if (-not (Test-Path -LiteralPath $finalManifestFile) -or -not (Test-Path -LiteralPath $finalDbDumpFile) -or -not (Test-Path -LiteralPath $finalStorageDir)) {
            throw "PUBLICATION FAILURE: Final published backup directory is missing core artifacts (manifest, dump, or storage)."
        }

        $publishedManifest = Get-Content -LiteralPath $finalManifestFile -Raw | ConvertFrom-Json
        if ($publishedManifest.run_id -ne $runId) {
            throw "PUBLICATION INTEGRITY CORRUPTION: Published manifest run_id '$($publishedManifest.run_id)' does not match active run '$runId'!"
        }

        $nestedStaging = Get-ChildItem -LiteralPath $resolvedOutputDir -Filter ".staging_*" -Force
        if ($nestedStaging.Count -gt 0) {
            throw "PUBLICATION INTEGRITY CORRUPTION: Staging directory was nested inside OutputDir!"
        }

        # Publication confirmed valid
        $newBackupPublished = $true

        # Purge the old backup archive now that the new backup is verified published.
        if ($oldBackupArchive -and (Test-Path -LiteralPath $oldBackupArchive)) {
            Remove-Item -LiteralPath $oldBackupArchive -Recurse -Force -ErrorAction SilentlyContinue
            $oldBackupArchive = $null
        }
    } catch {
        # Rollback: If old backup was moved but new backup failed to publish:
        if ($oldBackupMoved -and -not $newBackupPublished -and $oldBackupArchive -and (Test-Path -LiteralPath $oldBackupArchive)) {
            if ($PublishFaultInjection -eq "BLOCK_ROLLBACK") {
                # Create obstacle at $resolvedOutputDir to simulate destination obstructed
                if (-not (Test-Path -LiteralPath $resolvedOutputDir)) {
                    New-Item -ItemType Directory -Path $resolvedOutputDir -Force | Out-Null
                }
                Set-Content -LiteralPath (Join-Path $resolvedOutputDir "obstacle.txt") -Value "OBSTACLE"
            }

            Write-Host "  WARNING: Publication failed. Attempting rollback to restore preexisting backup to '$resolvedOutputDir'..." -ForegroundColor Yellow
            $rollbackError = $null
            try {
                if (-not (Test-Path -LiteralPath $resolvedOutputDir)) {
                    [System.IO.Directory]::Move($oldBackupArchive, $resolvedOutputDir)
                    $rollbackSucceeded = $true
                    Write-Host "  Rollback successful: Original backup restored to '$resolvedOutputDir'." -ForegroundColor Green
                } else {
                    $rollbackError = "Destination '$resolvedOutputDir' already exists or is obstructed; cannot restore original backup directly."
                }
            } catch {
                $rollbackError = $_.ToString()
            }

            if (-not $rollbackSucceeded) {
                # CRITICAL: DO NOT DELETE $oldBackupArchive!
                Write-Host "  CRITICAL ROLLBACK FAILURE: $rollbackError" -ForegroundColor Red
                Write-Host "  CRITICAL: Original backup is safely preserved at '$oldBackupArchive'. DO NOT delete this directory." -ForegroundColor Red
                throw "CRITICAL ROLLBACK FAILURE: Could not restore original backup to '$resolvedOutputDir' ($rollbackError). Original backup is safely preserved at '$oldBackupArchive'. Do not delete this directory."
            }
        }
        throw
    }

    $finalManifestFile = Join-Path $resolvedOutputDir "manifest.json"
    $finalDbDumpFile = Join-Path $resolvedOutputDir "database.dump"
    $finalStorageDir = Join-Path $resolvedOutputDir "storage"

    Write-Host "==========================================================" -ForegroundColor Green
    if ($completenessStatus -eq "INCOMPLETE") {
        Write-Host "  BACKUP COMPLETED WITH WARNINGS (INCOMPLETE ARCHIVE)" -ForegroundColor Yellow
    } else {
        Write-Host "  BACKUP COMPLETED SUCCESSFULLY (VERIFIED ARCHIVE)" -ForegroundColor Green
    }
    Write-Host "  Manifest: $finalManifestFile" -ForegroundColor Green
    Write-Host "  DB Dump:  $finalDbDumpFile (SHA256: $dumpHash)" -ForegroundColor Green
    Write-Host "  Objects:  $($storageFiles.Count) stored in $finalStorageDir" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

    return $finalManifestFile
} catch {
    Write-Host "==========================================================" -ForegroundColor Red
    Write-Host "  BACKUP FAILED: $_" -ForegroundColor Red
    Write-Host "==========================================================" -ForegroundColor Red

    # Scoped cleanup: ONLY remove this run's isolated staging directory. $resolvedOutputDir is NEVER touched.
    if ($stagingDir -and (Test-Path -LiteralPath $stagingDir)) {
        Write-Host "Cleaning up staging directory: $stagingDir" -ForegroundColor Red
        Remove-Item -LiteralPath $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    # If an old backup was preserved during publication rollback, ensure destination is restored
    if ($oldBackupArchive -and (Test-Path -LiteralPath $oldBackupArchive)) {
        if ($oldBackupMoved -and -not $newBackupPublished) {
            if (-not (Test-Path -LiteralPath $resolvedOutputDir)) {
                try {
                    [System.IO.Directory]::Move($oldBackupArchive, $resolvedOutputDir)
                    Write-Host "Restored original backup from '$oldBackupArchive' to '$resolvedOutputDir'." -ForegroundColor Yellow
                } catch {
                    Write-Host "CRITICAL: Could not restore '$oldBackupArchive' to '$resolvedOutputDir'. Preserved safely." -ForegroundColor Red
                }
            } else {
                Write-Host "CRITICAL: Preexisting backup safely preserved at '$oldBackupArchive'. DO NOT delete this directory." -ForegroundColor Red
            }
        } elseif ($newBackupPublished) {
            Remove-Item -LiteralPath $oldBackupArchive -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    if ($dumpTmpPath) {
        docker exec $ContainerName rm -f "$dumpTmpPath" 2>$null | Out-Null
    }
    if ($schemaTmpPath) {
        docker exec $ContainerName rm -f "$schemaTmpPath" 2>$null | Out-Null
    }

    throw
} finally {
    # Release publication lock by disposing the stream owned by this process.
    # The lock sentinel file (.publish_lock_<leaf>.lock) is intentionally preserved on disk:
    # 1. It provides a fixed, idempotent lock target for all subsequent backup runs to this OutputDir.
    # 2. Deleting/unlinking an active lock file causes race conditions (especially on POSIX/Linux systems
    #    where unlinking allows competing processes to recreate divergent inodes, breaking mutual exclusion).
    # 3. A process that failed to acquire the lock ($lockStream -eq $null) will never touch or delete the active lock.
    if ($lockStream) {
        try {
            $lockStream.Dispose()
        } catch {
            Write-Host "WARNING: Failed to dispose publication lock stream: $_" -ForegroundColor Yellow
        }
        $lockStream = $null
    }
}
