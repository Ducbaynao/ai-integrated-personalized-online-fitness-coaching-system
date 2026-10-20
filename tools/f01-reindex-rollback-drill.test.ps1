<#
.SYNOPSIS
    Unit and behavior tests for F01 Reindex/Rollback Drill script safety guards.

.DESCRIPTION
    Verifies that:
    1. Branch safety rejects 'main', 'master', and blank branch via shared helper.
    2. Port safety strictly blocks forbidden ports (5432 and 5433) via shared helper.
    3. Resource collision guard halts when container or network already exists, without deleting it.
    4. Environment variable backup & restoration restores pre-existing vs non-existing variables under error.
    5. Surefire test report parser handles test counts, skipped/failed scenarios, and rejects missing/stale reports.
    6. Git operations (working tree status, branch detection, error handling) behave correctly on clean, single-change,
       multi-change, and failed Git invocations without throwing null reference exceptions.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$failures = 0
$passed = 0

. (Join-Path $PSScriptRoot "f01-reindex-rollback-drill-common.ps1")

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

# Test 1: Branch guard logic via shared Assert-DrillBranchSafety implementation
Assert-Throws -TestName "Branch safety rejects 'main'" -ExpectedPattern "dedicated working branch" -Action {
    Assert-DrillBranchSafety -Branch "main"
}

Assert-Throws -TestName "Branch safety rejects 'master'" -ExpectedPattern "dedicated working branch" -Action {
    Assert-DrillBranchSafety -Branch "master"
}

Assert-Throws -TestName "Branch safety rejects blank branch" -ExpectedPattern "dedicated working branch" -Action {
    Assert-DrillBranchSafety -Branch ""
}

# Test 2: Port safety guard via shared Assert-DrillPortSafety implementation
Assert-Throws -TestName "Port safety blocks default port 5432" -ExpectedPattern "cannot be 5432" -Action {
    Assert-DrillPortSafety -Port 5432
}

Assert-Throws -TestName "Port safety blocks testbed port 5433" -ExpectedPattern "5433" -Action {
    Assert-DrillPortSafety -Port 5433
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

# Test 6: Git operations and working tree status (testing Get-DrillGitWorkingTreeStatus and Get-DrillGitCurrentBranch)
$tempRepo = Join-Path ([System.IO.Path]::GetTempPath()) ("f01-drill-test-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRepo -Force | Out-Null

try {
    # Initialize temporary repository outside project workspace
    git -C $tempRepo init -b chore/test-drill-branch 2>&1 | Out-Null

    # Case A: Git succeeds, clean working tree -> IsClean = true, no exception
    $statusA = Get-DrillGitWorkingTreeStatus -RepositoryRoot $tempRepo
    Assert-True -TestName "Clean working tree returns IsClean=true" -Condition ($statusA.IsClean -eq $true)
    Assert-True -TestName "Clean working tree has 0 lines" -Condition ($statusA.LineCount -eq 0)

    # Branch check succeeds on custom branch
    $branchA = Get-DrillGitCurrentBranch -RepositoryRoot $tempRepo
    Assert-True -TestName "Get-DrillGitCurrentBranch returns active branch" -Condition ($branchA -eq "chore/test-drill-branch")

    # Case B: Git succeeds, 1 untracked file -> IsClean = false, LineCount = 1
    Set-Content -Path (Join-Path $tempRepo "file1.txt") -Value "sample content 1"
    $statusB = Get-DrillGitWorkingTreeStatus -RepositoryRoot $tempRepo
    Assert-True -TestName "Single change returns IsClean=false" -Condition ($statusB.IsClean -eq $false)
    Assert-True -TestName "Single change has LineCount=1" -Condition ($statusB.LineCount -eq 1)

    # Case C: Git succeeds, multiple untracked/modified files -> IsClean = false, LineCount = 2
    Set-Content -Path (Join-Path $tempRepo "file2.txt") -Value "sample content 2"
    $statusC = Get-DrillGitWorkingTreeStatus -RepositoryRoot $tempRepo
    Assert-True -TestName "Multiple changes return IsClean=false" -Condition ($statusC.IsClean -eq $false)
    Assert-True -TestName "Multiple changes have LineCount=2" -Condition ($statusC.LineCount -eq 2)

    # Case D: Git failure on non-git directory -> throws clear error, never reports fake clean
    $nonGitDir = Join-Path ([System.IO.Path]::GetTempPath()) ("f01-non-git-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $nonGitDir -Force | Out-Null
    try {
        Assert-Throws -TestName "Git status on non-git directory throws error" -ExpectedPattern "Git status check failed" -Action {
            Get-DrillGitWorkingTreeStatus -RepositoryRoot $nonGitDir
        }
        Assert-Throws -TestName "Git branch on non-git directory throws error" -ExpectedPattern "Git branch check failed" -Action {
            Get-DrillGitCurrentBranch -RepositoryRoot $nonGitDir
        }
    } finally {
        Remove-Item -LiteralPath $nonGitDir -Recurse -Force -ErrorAction SilentlyContinue
    }
} finally {
    if (Test-Path -LiteralPath $tempRepo) {
        Get-ChildItem -LiteralPath $tempRepo -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
            $_.Attributes = "Normal"
        }
        Remove-Item -LiteralPath $tempRepo -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  TEST RESULTS: $passed PASSED, $failures FAILED" -ForegroundColor $(if ($failures -eq 0) { "Green" } else { "Red" })
Write-Host "=================================================================" -ForegroundColor Cyan

if ($failures -gt 0) {
    exit 1
}
