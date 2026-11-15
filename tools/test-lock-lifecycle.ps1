# Comprehensive Lock Lifecycle & OS Recovery Test
# Validates exclusive publication lock semantics across multiple processes and OS crash recovery.
# Works cross-platform on Windows (PowerShell 5.1/7) and Linux (PowerShell Core 7+).
[CmdletBinding()]
param(
    [string]$TestDirectory = ""
)

$ErrorActionPreference = "Stop"

$psExe = if (Get-Command pwsh -ErrorAction SilentlyContinue) { "pwsh" } elseif ($env:OS -eq "Windows_NT") { "powershell.exe" } else { "pwsh" }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Digital Fitness Publication Lock Lifecycle Drill" -ForegroundColor Cyan
Write-Host "  Engine: $psExe on $([System.Environment]::OSVersion.VersionString)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$testDir = if ([string]::IsNullOrWhiteSpace($TestDirectory)) {
    Join-Path ([System.IO.Path]::GetTempPath()) ("df_lock_drill_" + [System.Guid]::NewGuid().ToString("N"))
} else {
    $TestDirectory
}

if (-not (Test-Path -LiteralPath $testDir)) {
    New-Item -ItemType Directory -Path $testDir -Force | Out-Null
}

$targetLeaf = "backup_target"
$targetDir = Join-Path $testDir $targetLeaf
$lockFilePath = Join-Path $testDir (".publish_lock_" + $targetLeaf + ".lock")

# Signals
$sigAHolding = Join-Path $testDir "sig_a_holding.sig"
$sigARelease = Join-Path $testDir "sig_a_release.sig"
$sigCrashHolding = Join-Path $testDir "sig_crash_holding.sig"

function Try-AcquireLock([string]$path) {
    try {
        $stream = [System.IO.FileStream]::new(
            $path,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        return @{ Success = $true; Stream = $stream; Error = $null }
    } catch [System.IO.IOException] {
        return @{ Success = $false; Stream = $null; Error = "PUBLICATION LOCK CONTENTION" }
    } catch {
        return @{ Success = $false; Stream = $null; Error = $_.Exception.Message }
    }
}

try {
    # -------------------------------------------------------------
    # Phase 1: 3-Process Contention & Orderly Release
    # -------------------------------------------------------------
    Write-Host "`n[Phase 1] 3-Process Contention & Orderly Release..." -ForegroundColor Yellow

    # Launch Process A in background: acquires lock, signals holding, waits for release signal, disposes stream
    $procAScript = @"
`$ErrorActionPreference = 'Stop'
`$stream = [System.IO.FileStream]::new('$lockFilePath', [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
Set-Content -LiteralPath '$sigAHolding' -Value 'A_ACQUIRED'
`$sw = [System.Diagnostics.Stopwatch]::StartNew()
while (`$sw.Elapsed.TotalSeconds -lt 30 -and -not (Test-Path -LiteralPath '$sigARelease')) {
    Start-Sleep -Milliseconds 50
}
`$stream.Dispose()
"@

    $procA = Start-Process -FilePath $psExe -ArgumentList @("-NoProfile", "-NonInteractive", "-Command", $procAScript) -PassThru
    
    # Wait for Process A to acquire lock
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 15 -and -not (Test-Path -LiteralPath $sigAHolding)) {
        Start-Sleep -Milliseconds 50
    }
    if (-not (Test-Path -LiteralPath $sigAHolding)) {
        throw "Phase 1 FAIL: Process A failed to acquire lock within timeout!"
    }
    Write-Host "  Step 1: Process A (PID $($procA.Id)) acquired and is holding publication lock." -ForegroundColor Green

    # Process B attempts to acquire the same lock while A holds it
    $resultB = Try-AcquireLock $lockFilePath
    if ($resultB.Success) {
        $resultB.Stream.Dispose()
        throw "Phase 1 FAIL: Process B acquired lock while Process A was holding it!"
    }
    if ($resultB.Error -ne "PUBLICATION LOCK CONTENTION") {
        throw "Phase 1 FAIL: Expected PUBLICATION LOCK CONTENTION for Process B, got: $($resultB.Error)"
    }
    Write-Host "  Step 2: Process B attempted lock acquisition and was correctly rejected ($($resultB.Error))." -ForegroundColor Green

    # Process C attempts to acquire the same lock while A still holds it
    $resultC = Try-AcquireLock $lockFilePath
    if ($resultC.Success) {
        $resultC.Stream.Dispose()
        throw "Phase 1 FAIL: Process C acquired lock while Process A was holding it!"
    }
    if ($resultC.Error -ne "PUBLICATION LOCK CONTENTION") {
        throw "Phase 1 FAIL: Expected PUBLICATION LOCK CONTENTION for Process C, got: $($resultC.Error)"
    }
    Write-Host "  Step 3: Process C attempted lock acquisition and was correctly rejected ($($resultC.Error))." -ForegroundColor Green

    # Signal Process A to orderly release the lock
    Set-Content -LiteralPath $sigARelease -Value "RELEASE_NOW"
    $procA.WaitForExit(10000) | Out-Null
    Write-Host "  Step 4: Process A orderly released lock via Dispose() (Lock file sentinel preserved on disk: $(Test-Path -LiteralPath $lockFilePath))." -ForegroundColor Green

    # Verify lock file sentinel still exists on disk
    if (-not (Test-Path -LiteralPath $lockFilePath)) {
        throw "Phase 1 FAIL: Lock file sentinel was deleted on release! It must remain on disk."
    }

    # Process Next (D) attempts acquisition after Process A released
    $resultD = Try-AcquireLock $lockFilePath
    if (-not $resultD.Success) {
        throw "Phase 1 FAIL: Subsequent process failed to acquire lock after A released! Error: $($resultD.Error)"
    }
    $resultD.Stream.Dispose()
    Write-Host "  Step 5: Subsequent process successfully acquired lock after Process A released." -ForegroundColor Green

    # -------------------------------------------------------------
    # Phase 2: Abrupt Termination / OS Crash Recovery
    # -------------------------------------------------------------
    Write-Host "`n[Phase 2] Abrupt Termination / OS Crash Recovery..." -ForegroundColor Yellow

    $procCrashScript = @"
`$ErrorActionPreference = 'Stop'
`$stream = [System.IO.FileStream]::new('$lockFilePath', [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
Set-Content -LiteralPath '$sigCrashHolding' -Value 'CRASH_ACQUIRED'
# Intentionally hang holding the lock until killed abruptly by OS
while (`$true) {
    Start-Sleep -Seconds 1
}
"@

    $procCrash = Start-Process -FilePath $psExe -ArgumentList @("-NoProfile", "-NonInteractive", "-Command", $procCrashScript) -PassThru

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 15 -and -not (Test-Path -LiteralPath $sigCrashHolding)) {
        Start-Sleep -Milliseconds 50
    }
    if (-not (Test-Path -LiteralPath $sigCrashHolding)) {
        throw "Phase 2 FAIL: Crash process failed to acquire lock within timeout!"
    }
    Write-Host "  Step 1: Background process (PID $($procCrash.Id)) acquired lock and is active." -ForegroundColor Green

    # Verify lock is held
    $preKillCheck = Try-AcquireLock $lockFilePath
    if ($preKillCheck.Success) {
        $preKillCheck.Stream.Dispose()
        throw "Phase 2 FAIL: Lock was not held by crash process!"
    }
    Write-Host "  Step 2: Verified lock is exclusively held by background process." -ForegroundColor Green

    # Abruptly kill the process without orderly Dispose
    Write-Host "  Step 3: Abruptly terminating process (PID $($procCrash.Id)) via Stop-Process -Force..." -ForegroundColor Yellow
    Stop-Process -Id $procCrash.Id -Force
    Start-Sleep -Milliseconds 500

    # Test acquisition after abrupt termination
    $postKillCheck = Try-AcquireLock $lockFilePath
    if (-not $postKillCheck.Success) {
        throw "Phase 2 FAIL: Operating system failed to release lock after abrupt process termination! Error: $($postKillCheck.Error)"
    }
    $postKillCheck.Stream.Dispose()
    Write-Host "  Step 4: Operating system successfully released lock handle; next run acquired lock immediately." -ForegroundColor Green
    Write-Host "  Step 5: Lock file sentinel preserved on disk: $(Test-Path -LiteralPath $lockFilePath)." -ForegroundColor Green

    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host "  ALL LOCK LIFECYCLE & OS RECOVERY TESTS PASSED!" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

} finally {
    if ($procA -and -not $procA.HasExited) {
        Stop-Process -Id $procA.Id -Force -ErrorAction SilentlyContinue
    }
    if ($procCrash -and -not $procCrash.HasExited) {
        Stop-Process -Id $procCrash.Id -Force -ErrorAction SilentlyContinue
    }
    if ([string]::IsNullOrWhiteSpace($TestDirectory)) {
        Remove-Item -LiteralPath $testDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
