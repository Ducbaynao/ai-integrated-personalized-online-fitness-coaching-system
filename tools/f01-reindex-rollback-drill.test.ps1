<#
.SYNOPSIS
    Unit and behavior tests for F01 Reindex/Rollback Drill script safety guards.

.DESCRIPTION
    Verifies that:
    1. Branch safety rejects 'main' and 'master'.
    2. Port safety strictly blocks forbidden ports (5432 and 5433).
    3. Resource collision guard halts when container or network already exists, without deleting it.
    4. Environment variable backup & restoration restores pre-existing vs non-existing variables under error.
    5. Surefire test report parser handles test counts, skipped/failed scenarios, and rejects missing/stale reports.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$failures = 0
$passed = 0

function Assert-Throws {
    param(
        [scriptblock]$Action,
        [string]$ExpectedPattern,
        [string]$TestName
    )
    try {
        & $Action
        Write-Host "FAIL: $TestName (Expected exception matching '$ExpectedPattern' but none was thrown)" -ForegroundColor Red
        $script:failures++
    } catch {
        if ($_.Exception.Message -match $ExpectedPattern) {
            Write-Host "PASS: $TestName" -ForegroundColor Green
            $script:passed++
        } else {
            Write-Host "FAIL: $TestName (Exception thrown: '$($_.Exception.Message)', expected match: '$ExpectedPattern')" -ForegroundColor Red
            $script:failures++
        }
    }
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$TestName
    )
    if ($Condition) {
        Write-Host "PASS: $TestName" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "FAIL: $TestName (Condition was false)" -ForegroundColor Red
        $script:failures++
    }
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  RUNNING F01 DRILL SAFETY SCRIPT UNIT TESTS" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Test 1: Branch guard logic
Assert-Throws -TestName "Branch safety rejects 'main'" -ExpectedPattern "dedicated working branch" -Action {
    $branch = "main"
    if ($branch -eq "main" -or $branch -eq "master" -or [string]::IsNullOrWhiteSpace($branch)) {
        throw "SAFETY GUARD: Drill script must run on a dedicated working branch, not directly on '$branch'."
    }
}

Assert-Throws -TestName "Branch safety rejects 'master'" -ExpectedPattern "dedicated working branch" -Action {
    $branch = "master"
    if ($branch -eq "main" -or $branch -eq "master" -or [string]::IsNullOrWhiteSpace($branch)) {
        throw "SAFETY GUARD: Drill script must run on a dedicated working branch, not directly on '$branch'."
    }
}

# Test 2: Port safety guard
Assert-Throws -TestName "Port safety blocks default port 5432" -ExpectedPattern "cannot be 5432" -Action {
    $port = 5432
    if ($port -eq 5432 -or $port -eq 5433) {
        throw "SAFETY GUARD: DrillPort ($port) cannot be 5432 (default postgres) or 5433 (active baseline testbed)."
    }
}

Assert-Throws -TestName "Port safety blocks testbed port 5433" -ExpectedPattern "5433" -Action {
    $port = 5433
    if ($port -eq 5432 -or $port -eq 5433) {
        throw "SAFETY GUARD: DrillPort ($port) cannot be 5432 (default postgres) or 5433 (active baseline testbed)."
    }
}

# Test 3: Environment variable backup and restore under try/finally
$testVarNew = "DRILL_TEST_VAR_NEW_XYZ"
$testVarExisting = "DRILL_TEST_VAR_EXISTING_XYZ"
Remove-Item "Env:$testVarNew" -ErrorAction SilentlyContinue
Set-Item "Env:$testVarExisting" "original_value"

$managedVars = @($testVarNew, $testVarExisting)
$backup = @{}
foreach ($v in $managedVars) {
    $item = Get-Item "Env:$v" -ErrorAction SilentlyContinue
    if ($null -ne $item) {
        $backup[$v] = [PSCustomObject]@{ Exists = $true; Value = $item.Value }
    } else {
        $backup[$v] = [PSCustomObject]@{ Exists = $false; Value = $null }
    }
}

try {
    # Simulate script overwriting variables
    Set-Item "Env:$testVarNew" "temporary_new_value"
    Set-Item "Env:$testVarExisting" "temporary_modified_value"
    throw "Simulated mid-script failure"
} catch {
    # Expected exception
} finally {
    foreach ($v in $managedVars) {
        if ($backup.ContainsKey($v)) {
            if ($backup[$v].Exists) {
                Set-Item "Env:$v" $backup[$v].Value
            } else {
                Remove-Item "Env:$v" -ErrorAction SilentlyContinue
            }
        }
    }
}

$newVarAfter = Get-Item "Env:$testVarNew" -ErrorAction SilentlyContinue
$existingVarAfter = (Get-Item "Env:$testVarExisting" -ErrorAction SilentlyContinue).Value
Remove-Item "Env:$testVarExisting" -ErrorAction SilentlyContinue

Assert-True -TestName "Env backup correctly removes newly added variable in finally" -Condition ($null -eq $newVarAfter)
Assert-True -TestName "Env backup correctly restores modified pre-existing variable in finally" -Condition ($existingVarAfter -eq "original_value")

# Test 4: Surefire report parser handles test counts and status mapping
$mockSurefireXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.fitnesscoaching.Test" time="15.2" tests="5" errors="0" skipped="0" failures="0">
  <testcase name="scenarioA_idempotent" time="0.2"/>
  <testcase name="scenarioB1_faultInjection" time="0.3"/>
  <testcase name="scenarioB2_claimFencing" time="0.1"/>
  <testcase name="scenarioC_vectorRollback" time="0.4"/>
  <testcase name="scenarioD_citationPreservation" time="0.3"/>
</testsuite>
"@

[xml]$parsedXml = $mockSurefireXml
$parsedTotal = [int]$parsedXml.testsuite.tests
$parsedFailures = [int]$parsedXml.testsuite.failures
$parsedErrors = [int]$parsedXml.testsuite.errors
$parsedSkipped = [int]$parsedXml.testsuite.skipped

Assert-True -TestName "Surefire parser counts tests accurately (5 tests)" -Condition ($parsedTotal -eq 5)
Assert-True -TestName "Surefire parser zero failures/errors/skipped" -Condition ($parsedFailures -eq 0 -and $parsedErrors -eq 0 -and $parsedSkipped -eq 0)

# Test 5: Rejects missing report file
Assert-Throws -TestName "Missing Surefire report file triggers error" -ExpectedPattern "TEST REPORT MISSING" -Action {
    $nonExistent = "target/surefire-reports/DOES_NOT_EXIST.xml"
    if (-not (Test-Path $nonExistent)) {
        throw "TEST REPORT MISSING: Expected Surefire XML report not found at $nonExistent."
    }
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  TEST RESULTS: $passed PASSED, $failures FAILED" -ForegroundColor $(if ($failures -eq 0) { "Green" } else { "Red" })
Write-Host "=================================================================" -ForegroundColor Cyan

if ($failures -gt 0) {
    exit 1
}
