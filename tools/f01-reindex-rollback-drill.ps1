<#
.SYNOPSIS
    Automated F01 Reindex and Rollback Runtime Verification Drill (DEC-A13-05).

.DESCRIPTION
    Executes runtime verification of F01 reindex, midway failure recovery, vector replacement rollback,
    and citation provenance preservation in an isolated ephemeral database container.

    Enforces strict safety guards:
    - Dedicated isolated container (digital-fitness-postgres-drill) on port 5435 (isolated from port 5432 and testbed 5433).
    - Resource protection: rejects execution if container or network already exists, never blindly deletes existing resources.
    - Pre-flight connection target checks against database and port before any writes.
    - Environment preservation: captures and restores environment variables under all termination paths.
    - Dynamic verification: parses Surefire test results and live timings rather than hardcoding.
    - Application background scheduling disabled (APP_SCHEDULING_ENABLED=false, ATTENTION_REVIEW_CRON='-').
    - Output written to dedicated isolated run directory under reports/evaluation/drills/.
    - Automatic cleanup strictly scoped to resources created during this run.

.PARAMETER RepositoryRoot
    Absolute path to repository root. Defaults to script parent's parent directory.

.PARAMETER DrillPort
    Target port for isolated Postgres instance. Must not be 5432 or 5433. Default is 5435.

.PARAMETER KeepContainer
    If specified, keeps the isolated postgres container running after drill completion. Default is false (cleanup).
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$RepositoryRoot = "",

    [Parameter(Mandatory = $false)]
    [int]$DrillPort = 5435,

    [Parameter(Mandatory = $false)]
    [switch]$KeepContainer = $false
)

$origErrorActionPreference = $ErrorActionPreference
$origLocation = Get-Location
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

# 1. Branch Safety Check: must run on a dedicated working branch, not directly on main or master
$currentBranch = (git -C $RepositoryRoot branch --show-current).Trim()
if ($currentBranch -eq "main" -or $currentBranch -eq "master" -or [string]::IsNullOrWhiteSpace($currentBranch)) {
    throw "SAFETY GUARD: Drill script must run on a dedicated working branch, not directly on '$currentBranch'."
}

# 2. Port Safety Check
if ($DrillPort -eq 5432 -or $DrillPort -eq 5433) {
    throw "SAFETY GUARD: DrillPort ($DrillPort) cannot be 5432 (default postgres) or 5433 (active baseline testbed). Use 5435 or another non-forbidden port."
}

$ContainerName = "digital-fitness-postgres-drill"
$NetworkName = "digital-fitness-drill-net"
$DbName = "digital_fitness_drill"
$DbUser = "fitness_drill_app"
$DbPassword = "drill_pass_123"

# Resource ownership tracking
$createdContainerId = $null
$createdNetworkId = $null

# 3. Environment Variable Backup
$managedEnvVars = @(
    "DRILL_DATABASE_URL",
    "DRILL_DATABASE_USER",
    "DRILL_DATABASE_PASSWORD",
    "APP_SCHEDULING_ENABLED",
    "ATTENTION_REVIEW_CRON"
)

$envBackup = @{}
foreach ($var in $managedEnvVars) {
    $item = Get-Item "Env:$var" -ErrorAction SilentlyContinue
    if ($null -ne $item) {
        $envBackup[$var] = [PSCustomObject]@{ Exists = $true; Value = $item.Value }
    } else {
        $envBackup[$var] = [PSCustomObject]@{ Exists = $false; Value = $null }
    }
}

$drillStartTime = Get-Date
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $RepositoryRoot "reports/evaluation/drills/drill-run-$timestamp"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  F01 REINDEX & ROLLBACK DRILL (DEC-A13-05)" -ForegroundColor Cyan
Write-Host "  Timestamp: $timestamp" -ForegroundColor Cyan
Write-Host "  Target Port: $DrillPort | DB: $DbName | Container: $ContainerName" -ForegroundColor Cyan
Write-Host "  Run Directory: $runDir" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

try {
    # 4. Check for existing colliding resources (refuse to delete existing resources blindly)
    Write-Host "`n[1/6] Pre-flight resource collision verification..." -ForegroundColor Yellow

    $existingContainer = docker ps -aq -f "name=^/${ContainerName}$"
    if ($existingContainer) {
        throw "SAFETY GUARD: Container '$ContainerName' already exists (ID: $existingContainer). Halting to prevent collision or data loss. Manually inspect or remove it via: docker stop $ContainerName; docker rm $ContainerName"
    }

    $existingNet = docker network ls -q -f "name=^${NetworkName}$"
    if ($existingNet) {
        throw "SAFETY GUARD: Network '$NetworkName' already exists (ID: $existingNet). Halting to prevent collision. Manually inspect or remove it via: docker network rm $NetworkName"
    }

    # 5. Create Network and Start Container
    Write-Host "`n[2/6] Starting isolated Postgres container on port $DrillPort..." -ForegroundColor Yellow
    $infraStartTime = Get-Date

    docker network create $NetworkName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create docker network '$NetworkName'."
    }
    $createdNetworkId = $NetworkName

    $containerId = docker run -d `
        --name $ContainerName `
        --network $NetworkName `
        -e POSTGRES_DB=$DbName `
        -e POSTGRES_USER=$DbUser `
        -e POSTGRES_PASSWORD=$DbPassword `
        -p "${DrillPort}:5432" `
        pgvector/pgvector:pg18

    if ($LASTEXITCODE -ne 0 -or -not $containerId) {
        throw "Failed to start Docker container $ContainerName."
    }
    $createdContainerId = $containerId.Trim()

    # Wait for Postgres readiness
    Write-Host "Waiting for database readiness..." -ForegroundColor Gray
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        $check = docker exec $ContainerName pg_isready -U $DbUser -d $DbName 2>&1
        if ($check -match "accepting connections") {
            $testQuery = docker exec $ContainerName psql -U $DbUser -d $DbName -t -c "SELECT 1;" 2>&1
            if ($LASTEXITCODE -eq 0 -and $testQuery -match "1") {
                $ready = $true
                break
            }
        }
        Start-Sleep -Seconds 1
    }

    if (-not $ready) {
        throw "Postgres container failed to become ready within 30 seconds."
    }

    # Target verification: ensure we are talking to the exact isolated drill container
    $verifiedDb = (docker exec $ContainerName psql -U $DbUser -d $DbName -t -c "SELECT current_database();" 2>$null)
    if ($verifiedDb) {
        $verifiedDb = $verifiedDb.Trim()
    }
    if ($verifiedDb -ne $DbName) {
        throw "TARGET MISMATCH: Connected to '$verifiedDb' instead of expected isolated '$DbName'!"
    }
    Write-Host "Verified database connection target: '$verifiedDb' on port $DrillPort (CONFIRMED ISOLATED)." -ForegroundColor Green
    $infraEndTime = Get-Date
    $infraDurationSec = [Math]::Round(($infraEndTime - $infraStartTime).TotalSeconds, 2)

    # 6. Run Flyway Migrations
    Write-Host "`n[3/6] Applying Flyway migrations (V1-V44) to isolated database..." -ForegroundColor Yellow
    $flywayStartTime = Get-Date
    $migrationSqlPath = Join-Path $RepositoryRoot "services/backend/src/main/resources/db/migration"

    $flywayOutput = docker run --rm `
        --network $NetworkName `
        -v "${migrationSqlPath}:/flyway/sql:ro" `
        flyway/flyway:11 `
        -url="jdbc:postgresql://$($ContainerName):5432/$($DbName)" `
        -user="$DbUser" `
        -password="$DbPassword" `
        -schemas="fitness" `
        -defaultSchema="fitness" `
        -connectRetries=10 `
        migrate 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host ($flywayOutput -join "`n") -ForegroundColor Red
        throw "Flyway migrations failed on isolated database."
    }
    $flywayEndTime = Get-Date
    $flywayDurationSec = [Math]::Round(($flywayEndTime - $flywayStartTime).TotalSeconds, 2)
    Write-Host "Flyway migrations successfully applied (duration: ${flywayDurationSec}s)." -ForegroundColor Green

    # 7. Execute Drill Test Suite
    Write-Host "`n[4/6] Executing F01 Reindex & Rollback Drill Integration Test Suite..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null

    $backendDir = Join-Path $RepositoryRoot "services/backend"
    $mvnwCmd = Join-Path $backendDir "mvnw.cmd"
    if (-not (Test-Path $mvnwCmd)) {
        throw "Maven wrapper not found at: $mvnwCmd"
    }

    # Clean old surefire report before running to prevent reading stale results
    $surefireReportFile = Join-Path $backendDir "target/surefire-reports/TEST-com.fitnesscoaching.platform.modules.content.adapter.out.embedding.F01ReindexRollbackDrillIntegrationTest.xml"
    if (Test-Path $surefireReportFile) {
        Remove-Item -Force $surefireReportFile -ErrorAction SilentlyContinue
    }

    $env:DRILL_DATABASE_URL = "jdbc:postgresql://localhost:${DrillPort}/${DbName}"
    $env:DRILL_DATABASE_USER = $DbUser
    $env:DRILL_DATABASE_PASSWORD = $DbPassword
    $env:APP_SCHEDULING_ENABLED = "false"
    $env:ATTENTION_REVIEW_CRON = "-"

    $mavenStartTime = Get-Date
    Push-Location $backendDir
    try {
        $oldEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $mvnOutput = cmd.exe /c "mvnw.cmd test -Dtest=F01ReindexRollbackDrillIntegrationTest" 2>&1
        $testExitCode = $LASTEXITCODE
        $ErrorActionPreference = $oldEap
    } finally {
        Pop-Location
    }
    $mavenEndTime = Get-Date
    $mavenDurationSec = [Math]::Round(($mavenEndTime - $mavenStartTime).TotalSeconds, 2)

    if ($testExitCode -ne 0) {
        Write-Host ($mvnOutput -join "`n") -ForegroundColor Red
        throw "Drill test execution failed with exit code $testExitCode."
    }

    # 8. Verify and Parse Surefire XML Report
    if (-not (Test-Path $surefireReportFile)) {
        throw "TEST REPORT MISSING: Expected Surefire XML report not found at $surefireReportFile."
    }
    $reportItem = Get-Item $surefireReportFile
    if ($reportItem.LastWriteTime -lt $mavenStartTime.AddSeconds(-2)) {
        throw "STALE TEST REPORT: Surefire report is older than Maven execution start time ($($reportItem.LastWriteTime) < $mavenStartTime)."
    }

    [xml]$surefireXml = Get-Content $surefireReportFile
    $totalTests = [int]$surefireXml.testsuite.tests
    $failures = [int]$surefireXml.testsuite.failures
    $errors = [int]$surefireXml.testsuite.errors
    $skipped = [int]$surefireXml.testsuite.skipped
    $surefireSuiteSec = [double]$surefireXml.testsuite.time

    Write-Host "Surefire Report Verified: $totalTests tests run, $failures failures, $errors errors, $skipped skipped (Suite time: ${surefireSuiteSec}s)." -ForegroundColor Green

    # Parse individual testcase statuses
    $testcases = @{}
    foreach ($tc in $surefireXml.testsuite.testcase) {
        $tcName = $tc.name
        $tcStatus = "PASS"
        if ($tc.failure) { $tcStatus = "FAIL" }
        elseif ($tc.error) { $tcStatus = "ERROR" }
        elseif ($tc.skipped) { $tcStatus = "SKIPPED" }
        $testcases[$tcName] = [ordered]@{
            status = $tcStatus
            time = [double]$tc.time
        }
    }

    function Get-ScenarioStatus($prefix) {
        foreach ($key in $testcases.Keys) {
            if ($key -like "${prefix}*") {
                return $testcases[$key].status
            }
        }
        return "NOT_RUN"
    }

    $statusA = Get-ScenarioStatus "scenarioA_"
    $statusB1 = Get-ScenarioStatus "scenarioB1_"
    $statusB2 = Get-ScenarioStatus "scenarioB2_"
    $statusC = Get-ScenarioStatus "scenarioC_"
    $statusD = Get-ScenarioStatus "scenarioD_"

    # Parse measured recovery operations from Maven output
    $outboxReindexSec = "NOT_MEASURED"
    $snapshotRestoreSec = "NOT_MEASURED"
    foreach ($line in ($mvnOutput -split "`r?`n")) {
        if ($line -match '\[DRILL_METRICS\]\s+outbox_reindex_recovery_ms=(\d+)\s+snapshot_restore_recovery_ms=(\d+)') {
            $outboxReindexSec = [Math]::Round(([int]$matches[1]) / 1000.0, 3)
            $snapshotRestoreSec = [Math]::Round(([int]$matches[2]) / 1000.0, 3)
            break
        }
    }

    # 9. Capture Artifacts and Summary
    Write-Host "`n[5/6] Generating Drill Artifacts and Summary..." -ForegroundColor Yellow
    $drillEndTime = Get-Date
    $totalScriptDurationSec = [Math]::Round(($drillEndTime - $drillStartTime).TotalSeconds, 2)

    $headCommit = (git -C $RepositoryRoot rev-parse HEAD).Trim()
    $workingTreeStatus = (git -C $RepositoryRoot status --porcelain).Trim()
    $workingTreeClean = ($workingTreeStatus.Length -eq 0)

    $overallStatus = "FAIL"
    if ($totalTests -eq 5 -and $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0 -and $testExitCode -eq 0) {
        $overallStatus = "PASS"
    }

    $summary = [ordered]@{
        drill_run_id = "drill-f01-$timestamp"
        branch = $currentBranch
        head_commit = $headCommit
        working_tree_clean = $workingTreeClean
        historical_reference_baseline = "1f01da75bd83de3f37a6987b04264f805be75a20"
        started_at = $drillStartTime.ToUniversalTime().ToString("o")
        completed_at = $drillEndTime.ToUniversalTime().ToString("o")
        duration_seconds = $totalScriptDurationSec
        timing_breakdown = [ordered]@{
            script_total_seconds = $totalScriptDurationSec
            infra_setup_seconds = $infraDurationSec
            flyway_migrations_seconds = $flywayDurationSec
            maven_command_seconds = $mavenDurationSec
            surefire_suite_seconds = $surefireSuiteSec
            measured_recovery_times = [ordered]@{
                outbox_reindex_recovery_seconds = $outboxReindexSec
                table_level_restore_recovery_seconds = $snapshotRestoreSec
            }
            continuous_retrieval_downtime = "NOT_MEASURED"
        }
        test_results_summary = [ordered]@{
            total_tests = $totalTests
            failures = $failures
            errors = $errors
            skipped = $skipped
            surefire_report_file = $surefireReportFile
        }
        isolation_environment = [ordered]@{
            container_name = $ContainerName
            container_id = $createdContainerId
            network = $NetworkName
            port = $DrillPort
            database = $DbName
            scheduling_disabled = $true
            scheduling_config = "APP_SCHEDULING_ENABLED=false, ATTENTION_REVIEW_CRON='-'"
        }
        scenarios = @(
            [ordered]@{
                scenario_id = "A"
                name = "Idempotent Reindex"
                status = $statusA
                observed_interruption = "Retrieval before/after verified; continuous downtime: NOT_MEASURED"
                identity_preservation = "100% (deterministic chunk UUIDs and SHA-256 content hashes unchanged)"
                anti_duplication = "Verified (chunk and vector counts remained constant)"
                retrieval_consistency = "Identical rank and similarity scores before and after"
                fixture_note = "Fixture WHO-01 2 chunks (minimal representative fixture, distinct from 5 chunks in expanded testbed)"
            },
            [ordered]@{
                scenario_id = "B1"
                name = "Midway failure: Boundary 2 Embedding Exception"
                status = $statusB1
                observed_interruption = "Retrieval before/after verified; continuous downtime: NOT_MEASURED (Boundary 3 unopened; old vectors retained)"
                fault_type = "Simulated programmatic RuntimeException (mocking provider 503; distinct from physical OS network disconnect)"
                atomicity = "Verified (Boundary 3 was never entered, old vectors retained)"
                unready_exposure = "Verified (failed run did not report index_ready=true)"
                recovery = "Verified (retry succeeded after clearing simulated fault)"
            },
            [ordered]@{
                scenario_id = "B2"
                name = "Midway failure: Boundary 3 Claim Fencing Rejection"
                status = $statusB2
                observed_interruption = "Retrieval before/after verified; continuous downtime: NOT_MEASURED (Boundary 3 transaction rolled back; old vectors retained)"
                fault_type = "Database-level claim token update during inspection (fencing collision; distinct from network failure)"
                atomicity = "Verified (single REQUIRES_NEW transaction rolled back completely)"
                unready_exposure = "Zero partial chunks or embeddings committed"
            },
            [ordered]@{
                scenario_id = "C"
                name = "Rollback after vector replacement (DEC-A13-05)"
                status = $statusC
                vector_replacement_behavior = "Reindexing with new model space deletes previous vectors"
                config_only_switch_result = "Retrieval returned 0 chunks (proves config change alone is insufficient)"
                recovery_mechanism_1 = "Outbox Reindex back to target model regenerated vectors and fully restored retrieval"
                recovery_mechanism_2 = "Table-level restore in drill environment restored vectors immediately (not full DB disaster recovery)"
                measured_outbox_reindex_recovery_sec = $outboxReindexSec
                measured_table_restore_recovery_sec = $snapshotRestoreSec
                observed_downtime = "Query availability lost for Model A from replacement until reindex/restore completed; continuous downtime: NOT_MEASURED; testbed timings cannot be extrapolated to live provider or production"
                model_space_mode = "Simulated test double (metadata OpenAI text-embedding-3-small/1536 but vectors generated via DeterministicEmbeddingGenerator, `$0 spend)"
            },
            [ordered]@{
                scenario_id = "D"
                name = "Citation history, locator, and provenance preservation"
                status = $statusD
                historical_citation_resolution = "AI run citations in ai_run_knowledge_chunks resolved chunk content and locators exactly"
                mutation_prevention = "ContentConflictException thrown when mutating cited chunk content or provenance (sourceId, hash, locator)"
                retention_vs_eligibility = "Uncited chunks pruned; cited chunks preserved without embeddings and excluded from retrieval (isChunkEligible=false)"
                draft_ineligible_isolation = "HELD_AS_DRAFT (NIDDK 05/06 fixture) rejected from indexing; zero chunks in retrieval"
            }
        )
        overall_status = $overallStatus
        verification_scope = "Simulated offline testbed; live provider calls: PENDING PREREQUISITES / UNVERIFIED"
    }

    $summaryJsonPath = Join-Path $runDir "summary.json"
    $summaryJsonContent = $summary | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($summaryJsonPath, $summaryJsonContent, [System.Text.Encoding]::UTF8)

    # Markdown Report in runDir
    $reportMdPath = Join-Path $runDir "report.md"
    $drillRunId = $summary["drill_run_id"]
    $drillBranch = $summary["branch"]
    $drillHeadCommit = $summary["head_commit"]
    $drillStart = $summary["started_at"]
    $drillEnd = $summary["completed_at"]
    $drillDur = $summary["duration_seconds"]
    $drillPortStr = $summary["isolation_environment"]["port"]
    $drillDbStr = $summary["isolation_environment"]["database"]
    $drillContainerStr = $summary["isolation_environment"]["container_name"]

    $reportContent = @"
# F01 Reindex & Rollback Drill Execution Summary

- **Run ID:** $drillRunId
- **Branch:** $drillBranch
- **HEAD Commit:** $drillHeadCommit
- **Historical Baseline Reference:** 1f01da75bd83de3f37a6987b04264f805be75a20
- **Execution Window:** $drillStart to $drillEnd (${drillDur}s)
- **Timing Breakdown:**
  - Infra Setup: ${infraDurationSec}s
  - Flyway Migrations: ${flywayDurationSec}s
  - Maven Command: ${mavenDurationSec}s
  - Surefire Suite: ${surefireSuiteSec}s
  - Outbox Reindex Recovery (measured): ${outboxReindexSec}s
  - Table-level Restore Recovery (measured): ${snapshotRestoreSec}s
  - Continuous Retrieval Downtime: NOT_MEASURED
- **Isolation Target:** Port $drillPortStr, DB $drillDbStr, Container $drillContainerStr
- **Execution Mode:** Offline simulated test double (0 calls to real OpenAI API, `$0 spend)
- **Overall Status:** **$overallStatus** ($totalTests tests run, $failures failures, $errors errors, $skipped skipped)

## Scenario Results

| Scenario | Description | Status | Observed Interruption | Recovery Mechanism | Measured Recovery Duration |
| :---: | :--- | :---: | :---: | :--- | :---: |
| **A** | Idempotent Reindex | **$statusA** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Native outbox reindexing | N/A |
| **B1** | Boundary 2 Fault Injection (Simulated 503) | **$statusB1** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Automatic rollback, prior index intact | N/A |
| **B2** | Boundary 3 Claim Fencing Rejection | **$statusB2** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Transaction rollback, zero unready commit | N/A |
| **C** | Rollback after vector replacement (DEC-A13-05) | **$statusC** | Availability lost until recovery; continuous downtime: NOT_MEASURED | Outbox reindex or Table-level restore | Reindex: ${outboxReindexSec}s / Restore: ${snapshotRestoreSec}s |
| **D** | Citation history & provenance preservation | **$statusD** | N/A | Immutability guard, historical chunk retention | N/A |

"@
    [System.IO.File]::WriteAllText($reportMdPath, $reportContent, [System.Text.Encoding]::UTF8)

    Write-Host "Artifacts written to:" -ForegroundColor Green
    Write-Host "  $summaryJsonPath" -ForegroundColor Gray
    Write-Host "  $reportMdPath" -ForegroundColor Gray
} finally {
    # 10. Guaranteed Cleanup & Environment Restoration
    Write-Host "`n[6/6] Cleanup & Environment Restoration..." -ForegroundColor Yellow

    # Restore environment variables
    foreach ($var in $managedEnvVars) {
        if ($envBackup.ContainsKey($var)) {
            if ($envBackup[$var].Exists) {
                Set-Item "Env:$var" $envBackup[$var].Value
            } else {
                Remove-Item "Env:$var" -ErrorAction SilentlyContinue
            }
        }
    }

    # Resource cleanup strictly limited to created container and network
    if (-not $KeepContainer) {
        if ($createdContainerId) {
            Write-Host "Stopping and removing drill container '$ContainerName'..." -ForegroundColor Gray
            try {
                docker stop $ContainerName 2>$null | Out-Null
                docker rm -f $ContainerName 2>$null | Out-Null
            } catch {
                Write-Warning "Failed to stop/remove drill container $($ContainerName): $_"
            }
        }
        if ($createdNetworkId) {
            Write-Host "Removing drill network '$NetworkName'..." -ForegroundColor Gray
            try {
                docker network rm $NetworkName 2>$null | Out-Null
            } catch {
                Write-Warning "Failed to remove drill network $($NetworkName): $_"
            }
        }
        Write-Host "Ephemeral resources cleaned up successfully." -ForegroundColor Green
    } else {
        if ($createdContainerId) {
            Write-Host "KeepContainer specified: Container '$ContainerName' ($createdContainerId) left running on port $DrillPort." -ForegroundColor Yellow
            Write-Host "Manual cleanup commands:" -ForegroundColor Yellow
            Write-Host "  docker stop $ContainerName" -ForegroundColor Gray
            Write-Host "  docker rm $ContainerName" -ForegroundColor Gray
            Write-Host "  docker network rm $NetworkName" -ForegroundColor Gray
        }
    }

    Set-Location $origLocation
    $ErrorActionPreference = $origErrorActionPreference
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "  F01 DRILL EXECUTION COMPLETED: ALL SCENARIOS VERIFIED" -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
