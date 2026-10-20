<#
.SYNOPSIS
    Shared helper functions and safety guards for F01 Reindex/Rollback Drill scripts.
#>

function Assert-DrillBranchSafety {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Branch
    )
    if ($Branch -eq "main" -or $Branch -eq "master" -or [string]::IsNullOrWhiteSpace($Branch)) {
        throw "SAFETY GUARD: Drill script must run on a dedicated working branch, not directly on '$Branch'."
    }
}

function Assert-DrillPortSafety {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )
    if ($Port -eq 5432 -or $Port -eq 5433) {
        throw "SAFETY GUARD: DrillPort ($Port) cannot be 5432 (default postgres) or 5433 (active baseline testbed). Use 5435 or another non-forbidden port."
    }
}

function Get-DrillGitCurrentBranch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )
    try {
        $output = git -C $RepositoryRoot branch --show-current 2>&1
    } catch {
        throw "Git branch check failed in '$RepositoryRoot': $($_.Exception.Message)"
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $err = if ($null -ne $output) { ($output | Out-String).Trim() } else { "Unknown git error" }
        throw "Git branch check failed with exit code $exitCode in '$RepositoryRoot': $err"
    }
    if ($null -eq $output) {
        return ""
    }
    return ($output | Out-String).Trim()
}

function Get-DrillGitHeadCommit {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )
    try {
        $output = git -C $RepositoryRoot rev-parse HEAD 2>&1
    } catch {
        throw "Git rev-parse HEAD failed in '$RepositoryRoot': $($_.Exception.Message)"
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $err = if ($null -ne $output) { ($output | Out-String).Trim() } else { "Unknown git error" }
        throw "Git rev-parse HEAD failed with exit code $exitCode in '$RepositoryRoot': $err"
    }
    if ($null -eq $output) {
        throw "Git rev-parse HEAD returned empty output in '$RepositoryRoot'."
    }
    return ($output | Out-String).Trim()
}

function Get-DrillGitWorkingTreeStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )
    try {
        $output = git -C $RepositoryRoot status --porcelain 2>&1
    } catch {
        throw "Git status check failed in '$RepositoryRoot': $($_.Exception.Message)"
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $err = if ($null -ne $output) { ($output | Out-String).Trim() } else { "Unknown git error" }
        throw "Git status check failed with exit code $exitCode in '$RepositoryRoot': $err"
    }

    if ($null -eq $output) {
        return [PSCustomObject]@{
            IsClean = $true
            RawOutput = ""
            LineCount = 0
        }
    }

    $rawText = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($rawText)) {
        return [PSCustomObject]@{
            IsClean = $true
            RawOutput = ""
            LineCount = 0
        }
    }

    $lines = @($output | Where-Object { $null -ne $_ -and $_.ToString().Trim().Length -gt 0 })
    return [PSCustomObject]@{
        IsClean = ($lines.Count -eq 0)
        RawOutput = $rawText
        LineCount = $lines.Count
    }
}
