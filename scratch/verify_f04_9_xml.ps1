$targetFiles = @(
    "TEST-com.fitnesscoaching.platform.WorkoutProgressAggregationIntegrationTest.xml",
    "TEST-com.fitnesscoaching.platform.MeasurementProgressIntegrationTest.xml",
    "TEST-com.fitnesscoaching.platform.ProgressEngineIntegrationTest.xml",
    "TEST-com.fitnesscoaching.platform.AiProgressSummaryRealFastApiIntegrationTest.xml",
    "TEST-com.fitnesscoaching.platform.modules.workout.SourceWatermarkUnitTest.xml",
    "TEST-com.fitnesscoaching.platform.modules.ai.F04RuntimeOutputValidatorUnitTest.xml",
    "TEST-com.fitnesscoaching.platform.modules.ai.adapter.in.web.AiProgressSummaryControllerUnitTest.xml",
    "TEST-com.fitnesscoaching.platform.modules.ai.AiProgressSummarizationServiceUnitTest.xml",
    "TEST-com.fitnesscoaching.platform.modules.progress.ProgressEngineUnitTest.xml"
)

$rows = foreach ($tf in $targetFiles) {
    $path = Join-Path "services/backend/target/surefire-reports" $tf
    if (Test-Path $path) {
        $file = Get-Item $path
        [xml]$x = Get-Content $path
        $suite = $x.testsuite
        [PSCustomObject]@{
            Class = $suite.name.Split('.')[-1]
            Tests = [int]$suite.tests
            Failures = [int]$suite.failures
            Errors = [int]$suite.errors
            Skipped = [int]$suite.skipped
            Time = [double]$suite.time
            Timestamp = $file.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
        }
    } else {
        [PSCustomObject]@{
            Class = $tf
            Tests = 0
            Failures = 0
            Errors = 0
            Skipped = 0
            Time = 0
            Timestamp = "NOT FOUND"
        }
    }
}

$rows | Format-Table -AutoSize
$totalTests = ($rows | Measure-Object -Property Tests -Sum).Sum
$totalFailures = ($rows | Measure-Object -Property Failures -Sum).Sum
$totalErrors = ($rows | Measure-Object -Property Errors -Sum).Sum
$totalSkipped = ($rows | Measure-Object -Property Skipped -Sum).Sum
$totalTime = ($rows | Measure-Object -Property Time -Sum).Sum
Write-Host "Total F04 9 Suites: Tests=$totalTests, Failures=$totalFailures, Errors=$totalErrors, Skipped=$totalSkipped, TotalTime=$totalTime"
