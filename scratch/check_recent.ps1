$files = Get-ChildItem -Path "services/backend/target/surefire-reports/TEST-*.xml" | Where-Object { $_.LastWriteTime -ge (Get-Date '2026-11-14 16:30:00') }
$res = foreach ($f in $files) {
    [xml]$x = Get-Content $f.FullName
    [PSCustomObject]@{
        Name = $f.BaseName
        Tests = [int]$x.testsuite.tests
        Failures = [int]$x.testsuite.failures
        Errors = [int]$x.testsuite.errors
        Skipped = [int]$x.testsuite.skipped
        Time = $x.testsuite.time
        LastWrite = $f.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
    }
}
$res | Format-Table -AutoSize
$sum = ($res | Measure-Object -Property Tests -Sum).Sum
Write-Host "Total tests in this run: $sum"
