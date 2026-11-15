# Comprehensive Regression Test Suite for Backup & Restore Integrity
# Tests all required integrity, concurrency, publication safety, and parser failure scenarios.
[CmdletBinding()]
param(
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$SourceDbName = "digital_fitness",
    [string]$DbUser = "fitness_app",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$TestS3Bucket = "fitness-media-regression"
)

$ErrorActionPreference = "Stop"
$curlCommand = if ($env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }
$backupScript = Join-Path $PSScriptRoot "backup-system.ps1"
$restoreScript = Join-Path $PSScriptRoot "restore-system.ps1"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Digital Fitness Backup System Regression Test Suite" -ForegroundColor Cyan
Write-Host "  Comprehensive Integrity, Publication & Parser Scenarios" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$testDbBase = "df_regression_db_" + [System.Guid]::NewGuid().ToString("N").Substring(0, 8)
$testDirBase = Join-Path ([System.IO.Path]::GetTempPath()) ("df_reg_drill_" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $testDirBase -Force | Out-Null

# Helper to create test database from template
function New-RegressionDatabase([string]$dbName) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $dbName;" 2>$null | Out-Null
    docker exec $ContainerName psql -U $DbUser -d postgres -c "CREATE DATABASE $dbName TEMPLATE $SourceDbName OWNER $DbUser;" 2>$null | Out-Null
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) {
        throw "Failed to create database $dbName from template $SourceDbName"
    }
    # Isolate test bucket in test database
    docker exec $ContainerName psql -U $DbUser -d $dbName -c "UPDATE fitness.media_files SET bucket_name = 'other-system-bucket' WHERE bucket_name IS NULL OR bucket_name != '$TestS3Bucket';" 2>$null | Out-Null
}

function Drop-RegressionDatabase([string]$dbName) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $dbName;" 2>$null | Out-Null
    $ErrorActionPreference = $prev
}

function Reset-TestS3Bucket([string]$bucket) {
    try {
        $resp = Invoke-WebRequest -Uri "$S3Endpoint/$bucket" -Method Get -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        $reader = [System.IO.StreamReader]::new($resp.RawContentStream, [System.Text.Encoding]::UTF8)
        [xml]$existing = $reader.ReadToEnd()
        if ($existing.ListBucketResult.Contents) {
            $keys = $existing.ListBucketResult.Contents | ForEach-Object { $_.Key }
            foreach ($k in $keys) {
                $segments = $k.Split('/') | ForEach-Object { [System.Uri]::EscapeDataString($_) }
                $url = "$S3Endpoint/$bucket/$($segments -join '/')"
                Invoke-RestMethod -Uri $url -Method Delete -ErrorAction SilentlyContinue | Out-Null
            }
        }
    } catch {
        Invoke-RestMethod -Uri "$S3Endpoint/$bucket" -Method Put -ErrorAction SilentlyContinue | Out-Null
    }
}

function Upload-S3TestFile([string]$bucket, [string]$key, [byte[]]$bytes) {
    $segments = $key.Split('/') | ForEach-Object { [System.Uri]::EscapeDataString($_) }
    $encodedKey = $segments -join '/'
    $url = "$S3Endpoint/$bucket/$encodedKey"
    $tempFile = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllBytes($tempFile, $bytes)
    & $curlCommand -s -f -g -X PUT -T "$tempFile" "$url"
    Remove-Item -LiteralPath $tempFile -Force
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload test file to $url"
    }
}

$passedTests = 0
$expectedScenarios = 18

try {
    Reset-TestS3Bucket $TestS3Bucket

    # -------------------------------------------------------------
    # Scenario 1: Normal backup and restore with clean files
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 1] Normal backup and successful restore..." -ForegroundColor Yellow
    $db1 = "$testDbBase`_s1"
    New-RegressionDatabase $db1
    $file1Key = "docs/standard_test.pdf"
    $file1Bytes = [System.Text.Encoding]::UTF8.GetBytes("Standard Document Content 1")
    $file1Hash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($file1Bytes)) -Algorithm SHA256).Hash.ToLowerInvariant()
    Upload-S3TestFile $TestS3Bucket $file1Key $file1Bytes

    $id1 = [System.Guid]::NewGuid().ToString()
    docker exec $ContainerName psql -U $DbUser -d $db1 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$id1', 'S3', '$TestS3Bucket', '$file1Key', 'application/pdf', $($file1Bytes.Length), '$file1Hash', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');" | Out-Null

    $outDir1 = Join-Path $testDirBase "s1_out"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db1 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir1
    if ($LASTEXITCODE -ne 0) { throw "Scenario 1 backup failed with exit code $LASTEXITCODE" }

    $restoreDb1 = "$db1`_restored"
    $restoreBucket1 = "$TestS3Bucket-rest"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $restoreScript `
        -BackupDir $outDir1 -ContainerName $ContainerName -TargetDbName $restoreDb1 -DbUser $DbUser `
        -TargetS3Endpoint $S3Endpoint -TargetS3Bucket $restoreBucket1 -Force
    if ($LASTEXITCODE -ne 0) { throw "Scenario 1 restore failed with exit code $LASTEXITCODE" }

    Write-Host "  PASS: Scenario 1 backup & restore drill succeeded with full parity." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db1
    Drop-RegressionDatabase $restoreDb1

    # -------------------------------------------------------------
    # Scenario 2: Concurrent upload between storage sync and pg_dump caught up
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 2] Catch-up sync captures concurrent uploads between storage sync and pg_dump..." -ForegroundColor Yellow
    $db2 = "$testDbBase`_s2"
    New-RegressionDatabase $db2
    $outDir2 = Join-Path $testDirBase "s2_out"

    $file2Key = "docs/concurrent_caught_up.pdf"
    $file2Bytes = [System.Text.Encoding]::UTF8.GetBytes("Concurrent Caught-up Content 2")
    $file2Hash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($file2Bytes)) -Algorithm SHA256).Hash.ToLowerInvariant()
    $file2Id = [System.Guid]::NewGuid().ToString()

    $file2Segments = $file2Key.Split('/') | ForEach-Object { [System.Uri]::EscapeDataString($_) }
    $file2Url = "$S3Endpoint/$TestS3Bucket/$($file2Segments -join '/')"

    $hook2Path = Join-Path $testDirBase "hook_s2.ps1"
    $hook2Content = @"
`$bytes = [System.Text.Encoding]::UTF8.GetBytes("Concurrent Caught-up Content 2")
`$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllBytes(`$tmp, `$bytes)
& curl.exe -s -f -g -X PUT -T "`$tmp" "$file2Url"
Remove-Item -LiteralPath `$tmp -Force
docker exec $ContainerName psql -U $DbUser -d $db2 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$file2Id', 'S3', '$TestS3Bucket', '$file2Key', 'application/pdf', $($file2Bytes.Length), '$file2Hash', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');"
"@
    $hook2Content | Set-Content -LiteralPath $hook2Path -Encoding UTF8

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db2 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir2 `
        -PostStorageHook $hook2Path
    if ($LASTEXITCODE -ne 0) { throw "Scenario 2 backup failed with exit code $LASTEXITCODE" }

    $manifest2 = Get-Content -LiteralPath (Join-Path $outDir2 "manifest.json") -Raw | ConvertFrom-Json
    $objFound2 = $manifest2.storage.objects | Where-Object { $_.key -eq $file2Key }
    if (-not $objFound2) { throw "Scenario 2: caught-up object missing from manifest!" }
    if ($objFound2.sha256 -ne $file2Hash) { throw "Scenario 2: caught-up object SHA256 mismatch!" }

    Write-Host "  PASS: Scenario 2 concurrent upload caught up and verified in manifest." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db2

    # -------------------------------------------------------------
    # Scenario 3: File referenced by DB snapshot but missing on storage causes failure
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 3] File referenced by DB snapshot but missing on storage FAILS by default..." -ForegroundColor Yellow
    $db3 = "$testDbBase`_s3"
    New-RegressionDatabase $db3
    $outDir3 = Join-Path $testDirBase "s3_out"
    $missingKey = "docs/ghost_file_not_in_s3.pdf"
    $id3 = [System.Guid]::NewGuid().ToString()
    docker exec $ContainerName psql -U $DbUser -d $db3 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$id3', 'S3', '$TestS3Bucket', '$missingKey', 'application/pdf', 1234, '0000000000000000000000000000000000000000000000000000000000000000', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');" | Out-Null

    # Default run (without -AllowIncompleteStorage) MUST fail
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res3 = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db3 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir3 2>&1
    $exit3 = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    $res3Text = ($res3 | Out-String)
    if ($exit3 -eq 0) {
        throw "Scenario 3 FAIL: Backup should have failed on missing storage object, but succeeded!"
    }
    if ($res3Text -notmatch "REFERENTIAL INTEGRITY VIOLATION") {
        throw "Scenario 3 FAIL: Expected error message containing 'REFERENTIAL INTEGRITY VIOLATION', got: $res3Text"
    }

    # Now verify that with -AllowIncompleteStorage, it generates an INCOMPLETE archive
    $outDir3Inc = Join-Path $testDirBase "s3_out_inc"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db3 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir3Inc -AllowIncompleteStorage
    if ($LASTEXITCODE -ne 0) { throw "Scenario 3: -AllowIncompleteStorage should succeed with warning" }

    $manifest3 = Get-Content -LiteralPath (Join-Path $outDir3Inc "manifest.json") -Raw | ConvertFrom-Json
    if ($manifest3.status -ne "INCOMPLETE") { throw "Scenario 3: Expected manifest.status == 'INCOMPLETE'" }

    # Verify restore rejects incomplete archive without -AllowIncomplete
    $restoreDb3 = "$db3`_restored"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res3Rest = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $restoreScript `
        -BackupDir $outDir3Inc -ContainerName $ContainerName -TargetDbName $restoreDb3 -DbUser $DbUser `
        -TargetS3Endpoint $S3Endpoint -TargetS3Bucket "$TestS3Bucket-rest" -Force 2>&1
    $exit3Rest = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    $res3RestText = ($res3Rest | Out-String)
    if ($exit3Rest -eq 0) {
        throw "Scenario 3 FAIL: Restore should have rejected incomplete archive, but succeeded!"
    }
    if ($res3RestText -notmatch "SAFETY ABORT") {
        throw "Scenario 3 FAIL: Expected restore abort message, got: $res3RestText"
    }

    Write-Host "  PASS: Scenario 3 missing storage fails by default, -AllowIncomplete produces INCOMPLETE status, and restore guards against incomplete archives." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db3

    # -------------------------------------------------------------
    # Scenario 4A: Preflight rejects non-existent container
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 4A] Preflight check rejects non-existent container immediately..." -ForegroundColor Yellow
    $outDir4a = Join-Path $testDirBase "s4a_out"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res4a = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName "non_existent_container_xyz" -DbName "dummy" -OutputDir $outDir4a 2>&1
    $exit4a = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    if ($exit4a -eq 0) {
        throw "Scenario 4A FAIL: Backup should fail when container is not running!"
    }
    Write-Host "  PASS: Scenario 4A non-existent container safely rejected at preflight." -ForegroundColor Green
    $passedTests++

    # -------------------------------------------------------------
    # Scenario 4B: Controlled pg_restore failure at snapshot extraction step aborts cleanly
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 4B] Controlled pg_restore failure at snapshot extraction aborts cleanly..." -ForegroundColor Yellow
    $db4b = "$testDbBase`_s4b"
    New-RegressionDatabase $db4b
    $outDir4b = Join-Path $testDirBase "s4b_out"

    # Hook that runs right after pg_dump and corrupts the dump file in container before pg_restore
    $hook4bPath = Join-Path $testDirBase "hook_s4b.ps1"
    $hook4bContent = @"
# Truncate and overwrite dump file inside container so pg_restore fails with exit code != 0
docker exec $ContainerName bash -c "echo 'CORRUPTED_NOT_A_VALID_CUSTOM_DUMP' > /tmp/db_*.dump"
"@
    $hook4bContent | Set-Content -LiteralPath $hook4bPath -Encoding UTF8

    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res4b = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db4b -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir4b `
        -PostDumpHook $hook4bPath 2>&1
    $exit4b = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    $res4bText = ($res4b | Out-String)
    if ($exit4b -eq 0) {
        throw "Scenario 4B FAIL: Backup should fail when pg_restore extraction fails!"
    }
    if ($res4bText -notmatch "CRITICAL SNAPSHOT EXTRACTION ERROR") {
        throw "Scenario 4B FAIL: Expected 'CRITICAL SNAPSHOT EXTRACTION ERROR' in output, got: $res4bText"
    }
    if (Test-Path -LiteralPath $outDir4b) {
        throw "Scenario 4B FAIL: OutputDir should not have been created on failed backup!"
    }

    Write-Host "  PASS: Scenario 4B controlled pg_restore failure properly detected and aborted." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db4b

    # -------------------------------------------------------------
    # Scenario 5: Valid snapshot with NO files succeeds without live DB fallback
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 5] Valid snapshot with 0 files succeeds without live DB fallback..." -ForegroundColor Yellow
    $db5 = "$testDbBase`_s5"
    New-RegressionDatabase $db5
    $emptyBucket = "$TestS3Bucket-empty"
    Reset-TestS3Bucket $emptyBucket

    $outDir5 = Join-Path $testDirBase "s5_out"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db5 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $emptyBucket -OutputDir $outDir5
    if ($LASTEXITCODE -ne 0) { throw "Scenario 5 backup failed with exit code $LASTEXITCODE" }

    $manifest5 = Get-Content -LiteralPath (Join-Path $outDir5 "manifest.json") -Raw | ConvertFrom-Json
    if ($manifest5.storage.object_count -ne 0) { throw "Scenario 5: Expected 0 objects in manifest, got $($manifest5.storage.object_count)" }
    if ($manifest5.status -ne "COMPLETE") { throw "Scenario 5: Expected COMPLETE status for empty table" }

    Write-Host "  PASS: Scenario 5 empty snapshot succeeds cleanly with 0 objects." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db5

    # -------------------------------------------------------------
    # Scenario 6: Deterministic post-dump DB mutation does NOT alter reconcile set
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 6] Deterministic post-dump DB mutation is omitted from dump and reconcile..." -ForegroundColor Yellow
    $db6 = "$testDbBase`_s6"
    New-RegressionDatabase $db6
    $outDir6 = Join-Path $testDirBase "s6_out"

    $lateFileKey = "docs/deterministic_post_dump_mutation.pdf"
    $hook6Path = Join-Path $testDirBase "hook_s6.ps1"
    # PostDumpHook runs synchronously after pg_dump snapshot completes, inserting a new committed row into live DB
    $hook6Content = @"
docker exec $ContainerName psql -U $DbUser -d $db6 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES (gen_random_uuid(), 'S3', '$TestS3Bucket', '$lateFileKey', 'application/pdf', 10, 'abc', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');"
if (`$LASTEXITCODE -ne 0) { exit 1 }
exit 0
"@
    $hook6Content | Set-Content -LiteralPath $hook6Path -Encoding UTF8

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db6 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir6 `
        -PostDumpHook $hook6Path
    if ($LASTEXITCODE -ne 0) { throw "Scenario 6 backup failed with exit code $LASTEXITCODE" }

    # Verify that live DB DOES contain $lateFileKey
    $liveKeyCheck = docker exec $ContainerName psql -U $DbUser -d $db6 -t -A -c "SELECT count(*) FROM fitness.media_files WHERE object_key = '$lateFileKey';"
    if ($liveKeyCheck.Trim() -ne "1") {
        throw "Scenario 6 setup error: postFileKey was not committed in live DB"
    }

    # Verify that manifest does NOT contain $lateFileKey because it was committed after pg_dump snapshot
    $manifest6 = Get-Content -LiteralPath (Join-Path $outDir6 "manifest.json") -Raw | ConvertFrom-Json
    $manifestHasLateFile = $manifest6.storage.objects | Where-Object { $_.key -eq $lateFileKey }
    if ($manifestHasLateFile) {
        throw "Scenario 6 FAIL: Manifest contains file that was committed after pg_dump snapshot!"
    }

    Write-Host "  PASS: Scenario 6 post-dump live DB mutations strictly omitted from snapshot reconcile." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db6

    # -------------------------------------------------------------
    # Scenario 7A: OutputDir with existing data is PRESERVED when command is rejected
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 7A] OutputDir with existing data is protected from deletion..." -ForegroundColor Yellow
    $outDir7a = Join-Path $testDirBase "s7a_protected_dir"
    New-Item -ItemType Directory -Path $outDir7a -Force | Out-Null
    $canaryFile7a = Join-Path $outDir7a "canary_data.txt"
    Set-Content -LiteralPath $canaryFile7a -Value "CRITICAL_USER_DATA_DO_NOT_DELETE"

    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res7a = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $SourceDbName -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir7a 2>&1
    $exit7a = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    if ($exit7a -eq 0) {
        throw "Scenario 7A FAIL: Backup into non-empty directory without -Force should have failed!"
    }
    if (-not (Test-Path -LiteralPath $canaryFile7a)) {
        throw "Scenario 7A FAIL: Canary file was DELETED! Data protection failed!"
    }
    $canaryContent7a = Get-Content -LiteralPath $canaryFile7a
    if ($canaryContent7a -ne "CRITICAL_USER_DATA_DO_NOT_DELETE") {
        throw "Scenario 7A FAIL: Canary file content was modified!"
    }

    Write-Host "  PASS: Scenario 7A non-empty OutputDir safely rejected and canary data preserved." -ForegroundColor Green
    $passedTests++

    # -------------------------------------------------------------
    # Scenario 7B: OutputDir as symlink or junction is safely rejected
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 7B] OutputDir as junction/symlink is safely rejected..." -ForegroundColor Yellow
    $realTarget7b = Join-Path $testDirBase "s7b_real_target"
    $junctionDir7b = Join-Path $testDirBase "s7b_junction"
    New-Item -ItemType Directory -Path $realTarget7b -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $realTarget7b "existing_child") -Force | Out-Null

    # Create junction on Windows
    $createJunctionCmd = "cmd.exe /c mklink /J `"$junctionDir7b`" `"$realTarget7b`""
    Invoke-Expression $createJunctionCmd | Out-Null

    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res7b = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $SourceDbName -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $junctionDir7b 2>&1
    $exit7b = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    $res7bText = ($res7b | Out-String)
    if ($exit7b -eq 0) {
        throw "Scenario 7B FAIL: Backup into symlink/junction should have been rejected!"
    }
    if ($res7bText -notmatch "cannot be a symlink or junction") {
        throw "Scenario 7B FAIL: Expected symlink/junction rejection error, got: $res7bText"
    }

    Write-Host "  PASS: Scenario 7B junction OutputDir rejected with safety abort." -ForegroundColor Green
    $passedTests++

    # -------------------------------------------------------------
    # Scenario 8: Cleanup on failure impacts ONLY current run's staging dir
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 8] Cleanup on failure deletes staging dir only, preserves parent and siblings..." -ForegroundColor Yellow
    $parentDir8 = Join-Path $testDirBase "s8_parent"
    New-Item -ItemType Directory -Path $parentDir8 -Force | Out-Null
    $siblingDir8 = Join-Path $parentDir8 "sibling_folder"
    New-Item -ItemType Directory -Path $siblingDir8 -Force | Out-Null
    $siblingFile8 = Join-Path $siblingDir8 "sibling_file.txt"
    Set-Content -LiteralPath $siblingFile8 -Value "Sibling data"

    $db8 = "$testDbBase`_s8"
    New-RegressionDatabase $db8
    # Insert missing media file to force backup failure
    docker exec $ContainerName psql -U $DbUser -d $db8 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES (gen_random_uuid(), 'S3', '$TestS3Bucket', 'docs/fail_trigger.pdf', 'application/pdf', 10, 'abc', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');" | Out-Null

    $targetDir8 = Join-Path $parentDir8 "target_fail"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res8 = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db8 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $targetDir8 2>&1
    $exit8 = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    if ($exit8 -eq 0) { throw "Scenario 8 setup error: backup should have failed" }
    if (Test-Path -LiteralPath $targetDir8) {
        throw "Scenario 8 FAIL: Target directory should not have been created on aborted backup!"
    }
    if (-not (Test-Path -LiteralPath $siblingFile8)) {
        throw "Scenario 8 FAIL: Sibling file was deleted during failure cleanup!"
    }
    # Verify no dangling .staging_ directories in parentDir8
    $danglingStaging = Get-ChildItem -LiteralPath $parentDir8 -Filter ".staging_*"
    if ($danglingStaging.Count -gt 0) {
        throw "Scenario 8 FAIL: Staging directory was not cleaned up on failure!"
    }

    Write-Host "  PASS: Scenario 8 failure cleanup strictly scoped to staging, siblings intact." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db8

    # -------------------------------------------------------------
    # Scenario 9A: Two concurrent backups to distinct directories succeed without interference
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 9A] Two concurrent backups to distinct directories run without conflict..." -ForegroundColor Yellow
    $db9_1 = "$testDbBase`_s9_1"
    $db9_2 = "$testDbBase`_s9_2"
    New-RegressionDatabase $db9_1
    New-RegressionDatabase $db9_2
    $outDir9_1 = Join-Path $testDirBase "s9_out_1"
    $outDir9_2 = Join-Path $testDirBase "s9_out_2"

    $job1 = Start-Job -ScriptBlock {
        param($scr, $c, $d, $u, $s3, $b, $o)
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scr `
            -ContainerName $c -DbName $d -DbUser $u -S3Endpoint $s3 -S3Bucket $b -OutputDir $o
    } -ArgumentList $backupScript, $ContainerName, $db9_1, $DbUser, $S3Endpoint, $TestS3Bucket, $outDir9_1

    $job2 = Start-Job -ScriptBlock {
        param($scr, $c, $d, $u, $s3, $b, $o)
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scr `
            -ContainerName $c -DbName $d -DbUser $u -S3Endpoint $s3 -S3Bucket $b -OutputDir $o
    } -ArgumentList $backupScript, $ContainerName, $db9_2, $DbUser, $S3Endpoint, $TestS3Bucket, $outDir9_2

    $completedJobs = Wait-Job -Job @($job1, $job2) -Timeout 60
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $resJ1 = Receive-Job $job1 2>&1
    $resJ2 = Receive-Job $job2 2>&1
    $ErrorActionPreference = $prevEAP

    if ($job1.State -ne "Completed" -or $job2.State -ne "Completed") {
        throw "Scenario 9A FAIL: Concurrent backup jobs timed out or failed! J1: $($job1.State), J2: $($job2.State)"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $outDir9_1 "manifest.json")) -or -not (Test-Path -LiteralPath (Join-Path $outDir9_2 "manifest.json"))) {
        throw "Scenario 9A FAIL: One of the concurrent backup manifests is missing!"
    }

    Write-Host "  PASS: Scenario 9A concurrent backups to separate targets completed with isolated staging." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db9_1
    Drop-RegressionDatabase $db9_2

    # -------------------------------------------------------------
    # Scenario 9B: True concurrent publication collision on SAME OutputDir with barrier sync
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 9B] Concurrent publication collision on SAME OutputDir with barrier sync..." -ForegroundColor Yellow
    $db9b_1 = "$testDbBase`_s9b_1"
    $db9b_2 = "$testDbBase`_s9b_2"
    New-RegressionDatabase $db9b_1
    New-RegressionDatabase $db9b_2
    $sharedOutDir = Join-Path $testDirBase "s9b_shared_target"

    $p1Sig = (Join-Path $testDirBase "p1_ready.sig").Replace('\', '/')
    $p2Sig = (Join-Path $testDirBase "p2_ready.sig").Replace('\', '/')
    if (Test-Path -LiteralPath $p1Sig) { Remove-Item -LiteralPath $p1Sig -Force }
    if (Test-Path -LiteralPath $p2Sig) { Remove-Item -LiteralPath $p2Sig -Force }

    # Barrier hook: each process announces readiness via signal file and waits for competitor
    $hookP1 = "Set-Content -LiteralPath '$p1Sig' -Value '1'; `$sw = [System.Diagnostics.Stopwatch]::StartNew(); while (`$sw.Elapsed.TotalSeconds -lt 20 -and -not (Test-Path -LiteralPath '$p2Sig')) { Start-Sleep -Milliseconds 50 }"
    $hookP2 = "Set-Content -LiteralPath '$p2Sig' -Value '2'; `$sw = [System.Diagnostics.Stopwatch]::StartNew(); while (`$sw.Elapsed.TotalSeconds -lt 20 -and -not (Test-Path -LiteralPath '$p1Sig')) { Start-Sleep -Milliseconds 50 }"

    # Launch background jobs that execute child powershell.exe and return structured results with real exit codes
    $job1 = Start-Job -ScriptBlock {
        param($name, $scr, $c, $d, $u, $s3, $b, $o, $hook)
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scr `
            -ContainerName $c -DbName $d -DbUser $u -S3Endpoint $s3 -S3Bucket $b -OutputDir $o -PrePublishHook $hook 2>&1
        $realExitCode = $LASTEXITCODE
        return [PSCustomObject]@{
            JobName  = $name
            DbName   = $d
            ExitCode = $realExitCode
            Output   = ($output | Out-String)
        }
    } -ArgumentList "Job1", $backupScript, $ContainerName, $db9b_1, $DbUser, $S3Endpoint, $TestS3Bucket, $sharedOutDir, $hookP1

    $job2 = Start-Job -ScriptBlock {
        param($name, $scr, $c, $d, $u, $s3, $b, $o, $hook)
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scr `
            -ContainerName $c -DbName $d -DbUser $u -S3Endpoint $s3 -S3Bucket $b -OutputDir $o -PrePublishHook $hook 2>&1
        $realExitCode = $LASTEXITCODE
        return [PSCustomObject]@{
            JobName  = $name
            DbName   = $d
            ExitCode = $realExitCode
            Output   = ($output | Out-String)
        }
    } -ArgumentList "Job2", $backupScript, $ContainerName, $db9b_2, $DbUser, $S3Endpoint, $TestS3Bucket, $sharedOutDir, $hookP2

    $completedJobs = Wait-Job -Job @($job1, $job2) -Timeout 60
    $j1Done = ($job1.State -eq "Completed" -or $job1.State -eq "Failed")
    $j2Done = ($job2.State -eq "Completed" -or $job2.State -eq "Failed")

    if (-not $j1Done -or -not $j2Done) {
        Write-Host "Scenario 9B timeout or incomplete jobs detected. Stopping jobs and harvesting logs..." -ForegroundColor Red
        Stop-Job -Job @($job1, $job2) -ErrorAction SilentlyContinue
        $rawJ1 = Receive-Job $job1 2>&1
        $rawJ2 = Receive-Job $job2 2>&1
        Remove-Job -Job @($job1, $job2) -Force -ErrorAction SilentlyContinue
        throw "Scenario 9B FAIL: Jobs did not complete within 60s timeout! J1: $($job1.State), J2: $($job2.State). Logs J1: $rawJ1 | Logs J2: $rawJ2"
    }

    $res1 = Receive-Job $job1
    $res2 = Receive-Job $job2
    Remove-Job -Job @($job1, $job2) -Force -ErrorAction SilentlyContinue

    $exit1 = $res1.ExitCode
    $exit2 = $res2.ExitCode

    # Exactly one process should succeed (exit 0), exactly one should safely fail/abort (exit != 0)
    if (($exit1 -eq 0 -and $exit2 -eq 0) -or ($exit1 -ne 0 -and $exit2 -ne 0)) {
        throw "Scenario 9B FAIL: Expected exactly one winner (exit 0) and one loser (exit != 0)! Job1 exit: $exit1, Job2 exit: $exit2. J1 Output: $($res1.Output) | J2 Output: $($res2.Output)"
    }

    $winner = if ($exit1 -eq 0) { $res1 } else { $res2 }
    $loser  = if ($exit1 -ne 0) { $res1 } else { $res2 }

    # Loser must have been safely rejected by publication lock contention or destination safety check
    if ($loser.Output -notmatch "(PUBLICATION LOCK CONTENTION|SAFETY ABORT)") {
        throw "Scenario 9B FAIL: Losing job was not rejected by lock contention or safety abort! Got: $($loser.Output)"
    }

    # Verify final published backup artifacts in OutputDir
    $finalManifestPath = Join-Path $sharedOutDir "manifest.json"
    if (-not (Test-Path -LiteralPath $finalManifestPath)) {
        throw "Scenario 9B FAIL: Winner manifest missing from $sharedOutDir!"
    }
    $publishedManifest = Get-Content -LiteralPath $finalManifestPath -Raw | ConvertFrom-Json

    # 1. manifest.run_id belongs to the winning job:
    if ($winner.Output -match 'Run ID:\s+([a-f0-9]{32})') {
        $winnerExpectedRunId = $matches[1]
        if ($publishedManifest.run_id -ne $winnerExpectedRunId) {
            throw "Scenario 9B FAIL: Published manifest run_id '$($publishedManifest.run_id)' does not match winner run_id '$winnerExpectedRunId'!"
        }
    } else {
        throw "Scenario 9B FAIL: Could not parse winner run ID from log!"
    }

    # 2. Database name / source identity matches winning backup
    if ($publishedManifest.database.database_name -ne $winner.DbName) {
        throw "Scenario 9B FAIL: Published manifest database_name '$($publishedManifest.database.database_name)' does not match winner db '$($winner.DbName)'!"
    }

    # 3. Dump and storage files match checksums in manifest
    $dumpFilePath = Join-Path $sharedOutDir "database.dump"
    if (-not (Test-Path -LiteralPath $dumpFilePath)) {
        throw "Scenario 9B FAIL: database.dump missing from published backup!"
    }
    $actualDumpSha = (Get-FileHash -LiteralPath $dumpFilePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualDumpSha -ne $publishedManifest.database.dump_sha256) {
        throw "Scenario 9B FAIL: database.dump checksum mismatch! Expected $($publishedManifest.database.dump_sha256), got $actualDumpSha"
    }

    if ($publishedManifest.storage.objects) {
        foreach ($obj in $publishedManifest.storage.objects) {
            $objPath = Join-Path (Join-Path $sharedOutDir "storage") $obj.key
            if (-not (Test-Path -LiteralPath $objPath)) {
                throw "Scenario 9B FAIL: Storage object '$($obj.key)' missing from published backup!"
            }
            $actualObjSha = (Get-FileHash -LiteralPath $objPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualObjSha -ne $obj.sha256.ToLowerInvariant()) {
                throw "Scenario 9B FAIL: Storage object '$($obj.key)' checksum mismatch!"
            }
        }
    }

    # 4. Anti-nesting check: no .staging_* directory inside OutputDir
    $nestedStaging9b = Get-ChildItem -LiteralPath $sharedOutDir -Filter ".staging_*" -Force
    if ($nestedStaging9b.Count -gt 0) {
        throw "Scenario 9B FAIL: Staging directory was nested inside OutputDir!"
    }

    # 5. Loser cleaned up its own staging directory in parent dir, no interference with winner
    $parentDir9b = Split-Path -Parent $sharedOutDir
    $danglingStaging9b = Get-ChildItem -LiteralPath $parentDir9b -Filter ".staging_s9b_shared_target_*"
    if ($danglingStaging9b.Count -gt 0) {
        throw "Scenario 9B FAIL: Losing process left dangling staging directory: $($danglingStaging9b.FullName)"
    }

    # 6. Verify lock file sentinel still safely exists on disk without being deleted
    $expectedLockFile = Join-Path $parentDir9b (".publish_lock_" + (Split-Path -Leaf $sharedOutDir) + ".lock")
    if (-not (Test-Path -LiteralPath $expectedLockFile)) {
        throw "Scenario 9B FAIL: Publication lock file was deleted! It must remain on disk as an idempotent sentinel."
    }

    Write-Host "  PASS: Scenario 9B true concurrent collision resolved: $($winner.JobName) won publication, loser rejected safely without data corruption." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db9b_1
    Drop-RegressionDatabase $db9b_2

    # -------------------------------------------------------------
    # Scenario 10: Special characters in object key handled correctly
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 10] Special characters in object key (spaces, utf-8, brackets)..." -ForegroundColor Yellow
    $db10 = "$testDbBase`_s10"
    New-RegressionDatabase $db10
    $specialKey = "verification-docs/user [1]/hồ sơ & ảnh 2026.pdf"
    $specialBytes = [System.Text.Encoding]::UTF8.GetBytes("Special character test content: Vietnamese & Brackets")
    $specialHash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($specialBytes)) -Algorithm SHA256).Hash.ToLowerInvariant()
    Upload-S3TestFile $TestS3Bucket $specialKey $specialBytes

    $id10 = [System.Guid]::NewGuid().ToString()
    $escapedKeySql = $specialKey.Replace("'", "''")
    docker exec $ContainerName psql -U $DbUser -d $db10 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$id10', 'S3', '$TestS3Bucket', '$escapedKeySql', 'application/pdf', $($specialBytes.Length), '$specialHash', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');" | Out-Null

    $outDir10 = Join-Path $testDirBase "s10_out"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db10 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir10
    if ($LASTEXITCODE -ne 0) { throw "Scenario 10 backup failed with exit code $LASTEXITCODE" }

    $manifest10 = Get-Content -LiteralPath (Join-Path $outDir10 "manifest.json") -Raw | ConvertFrom-Json
    $objFound10 = $manifest10.storage.objects | Where-Object { $_.key -eq $specialKey }
    if (-not $objFound10) { throw "Scenario 10 FAIL: Special key missing from manifest!" }

    # Restore and verify
    $restoreDb10 = "$db10`_restored"
    $restoreBucket10 = "$TestS3Bucket-rest-spec"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $restoreScript `
        -BackupDir $outDir10 -ContainerName $ContainerName -TargetDbName $restoreDb10 -DbUser $DbUser `
        -TargetS3Endpoint $S3Endpoint -TargetS3Bucket $restoreBucket10 -Force
    if ($LASTEXITCODE -ne 0) { throw "Scenario 10 restore failed with exit code $LASTEXITCODE" }

    Write-Host "  PASS: Scenario 10 special characters unescaped, backed up, and restored with parity." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db10
    Drop-RegressionDatabase $restoreDb10

    # -------------------------------------------------------------
    # Scenario 11: Size/checksum mismatch against DB metadata detected
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 11] Size/checksum mismatch detected as DATA INTEGRITY CORRUPTION..." -ForegroundColor Yellow
    $db11 = "$testDbBase`_s11"
    New-RegressionDatabase $db11
    $corruptKey = "docs/tampered_file.pdf"
    $corruptBytes = [System.Text.Encoding]::UTF8.GetBytes("Actual content in S3")
    Upload-S3TestFile $TestS3Bucket $corruptKey $corruptBytes

    $badSha = "1111111111111111111111111111111111111111111111111111111111111111"
    $id11 = [System.Guid]::NewGuid().ToString()
    docker exec $ContainerName psql -U $DbUser -d $db11 -c "INSERT INTO fitness.media_files (id, storage_provider, bucket_name, object_key, content_type, size_bytes, checksum_sha256, media_purpose, visibility, scan_status) VALUES ('$id11', 'S3', '$TestS3Bucket', '$corruptKey', 'application/pdf', $($corruptBytes.Length), '$badSha', 'VERIFICATION_DOCUMENT', 'PRIVATE', 'CLEAN');" | Out-Null

    $outDir11 = Join-Path $testDirBase "s11_out"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res11 = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db11 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir11 2>&1
    $exit11 = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    $res11Text = ($res11 | Out-String)
    if ($exit11 -eq 0) {
        throw "Scenario 11 FAIL: Backup should fail on SHA256 checksum mismatch against DB metadata!"
    }
    if ($res11Text -notmatch "DATA INTEGRITY CORRUPTION") {
        throw "Scenario 11 FAIL: Expected 'DATA INTEGRITY CORRUPTION' message, got: $res11Text"
    }

    Write-Host "  PASS: Scenario 11 metadata corruption detected and aborted backup." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db11

    # -------------------------------------------------------------
    # Scenario 12: Snapshot parser failure cases (missing header, truncated block, missing columns)
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 12] Snapshot parser validates header, terminating dot, and required columns..." -ForegroundColor Yellow

    # Extract Parse-PostgresSnapshotMediaFiles function from backup-system.ps1 using PowerShell AST
    $scriptContent = Get-Content -LiteralPath $backupScript -Raw
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($scriptContent, [ref]$null, [ref]$null)
    $unescapeAst = $ast.Find({ $args[0] -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $args[0].Name -eq "Unescape-PostgresCopyField" }, $true)
    $parseAst = $ast.Find({ $args[0] -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $args[0].Name -eq "Parse-PostgresSnapshotMediaFiles" }, $true)
    if (-not $unescapeAst -or -not $parseAst) {
        throw "Scenario 12 setup error: Could not extract helper functions from backup script AST"
    }
    Invoke-Expression $unescapeAst.Extent.Text
    Invoke-Expression $parseAst.Extent.Text

    # 12A: Missing COPY header
    $threw12a = $false
    try {
        Parse-PostgresSnapshotMediaFiles -RestoreOutput "SELECT 1;\n-- Just some SQL comments\n" -S3Bucket "test-bucket"
    } catch {
        if ($_ -match "Valid COPY header for media_files was not found") {
            $threw12a = $true
        } else {
            throw "Scenario 12A unexpected error: $_"
        }
    }
    if (-not $threw12a) { throw "Scenario 12A FAIL: Missing COPY header should throw parse error!" }

    # 12B: Truncated COPY block (missing terminating '\.')
    $threw12b = $false
    try {
        $truncatedText = "COPY fitness.media_files (id, object_key, scan_status, deleted_at) FROM stdin;`nrow1`tkey1`tCLEAN`t\N"
        Parse-PostgresSnapshotMediaFiles -RestoreOutput $truncatedText -S3Bucket "test-bucket"
    } catch {
        if ($_ -match "COPY block for media_files is truncated: missing terminating") {
            $threw12b = $true
        } else {
            throw "Scenario 12B unexpected error: $_"
        }
    }
    if (-not $threw12b) { throw "Scenario 12B FAIL: Truncated COPY block should throw parse error!" }

    # 12C: Missing required column in COPY header (missing deleted_at)
    $threw12c = $false
    try {
        $badHeader = "COPY fitness.media_files (id, object_key, scan_status) FROM stdin;`n\."
        Parse-PostgresSnapshotMediaFiles -RestoreOutput $badHeader -S3Bucket "test-bucket"
    } catch {
        if ($_ -match "COPY header missing required columns") {
            $threw12c = $true
        } else {
            throw "Scenario 12C unexpected error: $_"
        }
    }
    if (-not $threw12c) { throw "Scenario 12C FAIL: Missing required column in header should throw error!" }

    # 12D: Row data with fewer columns than expected
    $threw12d = $false
    try {
        $fewCols = "COPY fitness.media_files (id, object_key, scan_status, deleted_at) FROM stdin;`nsingle_col_row`n\."
        Parse-PostgresSnapshotMediaFiles -RestoreOutput $fewCols -S3Bucket "test-bucket"
    } catch {
        if ($_ -match "Row data has fewer columns") {
            $threw12d = $true
        } else {
            throw "Scenario 12D unexpected error: $_"
        }
    }
    if (-not $threw12d) { throw "Scenario 12D FAIL: Row data with fewer columns should throw error!" }

    # 12E: Valid header with 0 rows (header followed immediately by \.) succeeds with 0 objects
    $emptyValid = "COPY fitness.media_files (id, object_key, scan_status, deleted_at) FROM stdin;`n\.`n"
    $res12e = Parse-PostgresSnapshotMediaFiles -RestoreOutput $emptyValid -S3Bucket "test-bucket"
    if ($res12e.Count -ne 0) { throw "Scenario 12E FAIL: Expected 0 objects for empty table, got $($res12e.Count)" }

    Write-Host "  PASS: Scenario 12 parser correctly enforces header, terminator, required columns, and handles 0 rows." -ForegroundColor Green
    $passedTests++

    # -------------------------------------------------------------
    # Scenario 13: Rollback on publication failure preserves preexisting backup (13A & 13B)
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 13A] Publication failure rollback restores original backup using real script..." -ForegroundColor Yellow
    $outDir13 = Join-Path $testDirBase "s13_preserve_target"
    New-Item -ItemType Directory -Path $outDir13 -Force | Out-Null
    $canaryFile13 = Join-Path $outDir13 "critical_preexisting_data.json"
    $canaryContent13 = '{"version":"original","data":"DO_NOT_LOSE_THIS_FILE","timestamp":"2026-11-13"}'
    Set-Content -LiteralPath $canaryFile13 -Value $canaryContent13
    $origCanarySha = (Get-FileHash -LiteralPath $canaryFile13 -Algorithm SHA256).Hash.ToLowerInvariant()

    $db13 = "$testDbBase`_s13"
    New-RegressionDatabase $db13

    # Run real backup script with -Force and -PublishFaultInjection "FAIL_MOVE_STAGING"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res13a = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db13 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir13 -Force `
        -PublishFaultInjection "FAIL_MOVE_STAGING" 2>&1
    $exit13a = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    if ($exit13a -eq 0) {
        throw "Scenario 13A FAIL: Backup with fault injection should have returned non-zero exit code!"
    }

    # Verify original backup was restored back to $outDir13
    if (-not (Test-Path -LiteralPath $canaryFile13)) {
        throw "Scenario 13A FAIL: Original canary file is missing after rollback!"
    }
    $restoredCanarySha = (Get-FileHash -LiteralPath $canaryFile13 -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($restoredCanarySha -ne $origCanarySha) {
        throw "Scenario 13A FAIL: Restored canary file checksum mismatch! Expected: $origCanarySha, Got: $restoredCanarySha"
    }

    # Verify no dangling .replace_old_* archive left
    $parentDir13 = Split-Path -Parent $outDir13
    $danglingArchive13 = Get-ChildItem -LiteralPath $parentDir13 -Filter ".replace_old_s13_preserve_target_*"
    if ($danglingArchive13.Count -gt 0) {
        throw "Scenario 13A FAIL: Dangling replace_old archive left after successful rollback: $($danglingArchive13.FullName)"
    }

    Write-Host "  PASS: Scenario 13A real script rolled back and restored original backup intact." -ForegroundColor Green
    $passedTests++

    # 13B: Rollback obstructed preserves original backup at archive location without deletion
    Write-Host "`n[Scenario 13B] Obstructed rollback safely preserves original backup in archive without deleting..." -ForegroundColor Yellow
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $res13b = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript `
        -ContainerName $ContainerName -DbName $db13 -DbUser $DbUser `
        -S3Endpoint $S3Endpoint -S3Bucket $TestS3Bucket -OutputDir $outDir13 -Force `
        -PublishFaultInjection "BLOCK_ROLLBACK" 2>&1
    $exit13b = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP

    if ($exit13b -eq 0) {
        throw "Scenario 13B FAIL: Backup with BLOCK_ROLLBACK fault injection should have failed!"
    }

    $res13bText = ($res13b | Out-String)
    if ($res13bText -notmatch "CRITICAL ROLLBACK FAILURE") {
        throw "Scenario 13B FAIL: Expected CRITICAL ROLLBACK FAILURE error, got: $res13bText"
    }

    # Locate the preserved archive
    $preservedArchives = Get-ChildItem -LiteralPath $parentDir13 -Filter ".replace_old_s13_preserve_target_*"
    if ($preservedArchives.Count -eq 0) {
        throw "Scenario 13B FAIL: Preexisting backup archive was deleted or missing when rollback was obstructed!"
    }
    $preservedArchiveDir = $preservedArchives[0].FullName
    $preservedCanaryFile = Join-Path $preservedArchiveDir "critical_preexisting_data.json"
    if (-not (Test-Path -LiteralPath $preservedCanaryFile)) {
        throw "Scenario 13B FAIL: Canary file missing in preserved archive!"
    }
    $preservedCanarySha = (Get-FileHash -LiteralPath $preservedCanaryFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($preservedCanarySha -ne $origCanarySha) {
        throw "Scenario 13B FAIL: Preserved canary checksum corrupted! Expected: $origCanarySha, Got: $preservedCanarySha"
    }

    Write-Host "  PASS: Scenario 13B obstructed rollback strictly preserved original backup in '$preservedArchiveDir' with 100% checksum parity." -ForegroundColor Green
    $passedTests++
    Drop-RegressionDatabase $db13

    # -------------------------------------------------------------
    # Scenario 14: Publication lock lifecycle across 3 processes and OS crash recovery
    # -------------------------------------------------------------
    Write-Host "`n[Scenario 14] Publication lock lifecycle across 3 processes and OS crash recovery..." -ForegroundColor Yellow
    $lockLifecycleScript = Join-Path $PSScriptRoot "test-lock-lifecycle.ps1"
    $lockDir14 = Join-Path $testDirBase "s14_lock_drill"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $lockLifecycleScript -TestDirectory $lockDir14
    if ($LASTEXITCODE -ne 0) {
        throw "Scenario 14 FAIL: Lock lifecycle drill failed with exit code $LASTEXITCODE!"
    }
    Write-Host "  PASS: Scenario 14 publication lock multi-process contention, orderly release, and OS crash recovery validated." -ForegroundColor Green
    $passedTests++

    Write-Host "`n==========================================================" -ForegroundColor Green
    if ($passedTests -ne $expectedScenarios) {
        Write-Host "  REGRESSION SUITE FAILED: Passed $passedTests of $expectedScenarios expected scenarios!" -ForegroundColor Red
        throw "REGRESSION SUITE FAILED: Passed $passedTests of $expectedScenarios expected scenarios!"
    }
    Write-Host "  ALL $passedTests / $expectedScenarios REGRESSION SCENARIOS PASSED!" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

} finally {
    Remove-Item -LiteralPath $testDirBase -Recurse -Force -ErrorAction SilentlyContinue
}
