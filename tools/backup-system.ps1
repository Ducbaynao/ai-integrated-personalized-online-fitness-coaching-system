[CmdletBinding()]
param(
    [string]$ContainerName = "digital-fitness-postgres",
    [string]$DbName = "digital_fitness",
    [string]$DbUser = "fitness_app",
    [string]$S3Endpoint = "http://localhost:9000",
    [string]$S3Bucket = "fitness-media",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

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

# 1. Verify PostgreSQL Container
Write-Host "[1/4] Checking PostgreSQL container '$ContainerName'..." -ForegroundColor Yellow
$pgStatus = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
if ($pgStatus -ne "running") {
    throw "PostgreSQL container '$ContainerName' is not running (status: $pgStatus)."
}

# 2. Database Backup (pg_dump custom format)
$dbDumpFile = Join-Path $OutputDir "database.dump"
$dbSchemaFile = Join-Path $OutputDir "schema_structure.sql"

Write-Host "[2/4] Dumping database '$DbName'..." -ForegroundColor Yellow
docker exec $ContainerName pg_dump -U $DbUser -d $DbName -F c -b -v -f "/tmp/db_$timestamp.dump"
docker cp "${ContainerName}:/tmp/db_$timestamp.dump" $dbDumpFile
docker exec $ContainerName rm -f "/tmp/db_$timestamp.dump"

docker exec $ContainerName pg_dump -U $DbUser -d $DbName --schema-only -f "/tmp/db_schema_$timestamp.sql"
docker cp "${ContainerName}:/tmp/db_schema_$timestamp.sql" $dbSchemaFile
docker exec $ContainerName rm -f "/tmp/db_schema_$timestamp.sql"

# Query latest Flyway schema version
$flywayVersion = docker exec $ContainerName psql -U $DbUser -d $DbName -t -A -c "SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>$null
if (-not $flywayVersion) {
    $flywayVersion = "UNKNOWN"
}
Write-Host "  Database dump complete. Flyway Schema Version: $flywayVersion" -ForegroundColor Green

# 3. Object Storage Backup
Write-Host "[3/4] Backing up S3 bucket '$S3Bucket' from $S3Endpoint..." -ForegroundColor Yellow
$storageFiles = @()

try {
    $bucketXml = Invoke-RestMethod -Uri "$S3Endpoint/$S3Bucket" -Method Get -TimeoutSec 5 -ErrorAction Stop
    $contents = $bucketXml.ListBucketResult.Contents
    if ($contents) {
        # Handle single vs multiple items in XML
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

            Invoke-WebRequest -Uri "$S3Endpoint/$S3Bucket/$key" -OutFile $targetFile -TimeoutSec 10 -ErrorAction Stop
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

# 4. Compute Checksums & Manifest
Write-Host "[4/4] Generating backup manifest..." -ForegroundColor Yellow
$dumpHash = (Get-FileHash -Path $dbDumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
$dumpSize = (Get-Item $dbDumpFile).Length

$manifest = [PSCustomObject]@{
    version = "1.0"
    timestamp = $timestamp
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
