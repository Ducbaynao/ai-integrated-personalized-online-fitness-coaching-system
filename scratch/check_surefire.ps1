$files = Get-ChildItem -Path "services/backend/target/surefire-reports/TEST-*.xml"
$targetNames = @(
    'WorkoutProgressAggregationIntegrationTest',
    'MeasurementProgressIntegrationTest',
    'ProgressEngineIntegrationTest',
    'AiProgressSummaryRealFastApiIntegrationTest',
    'SourceWatermarkUnitTest',
    'F04RuntimeOutputValidatorUnitTest',
    'AiProgressSummaryControllerUnitTest',
    'AiProgressSummarizationServiceUnitTest',
    'ProgressEngineUnitTest',
    'F04ContextBuilderUnitTest'
)

$results = @()
foreach ($file in $files) {
    foreach ($tn in $targetNames) {
        if ($file.Name -like "*$tn*") {
            [xml]$xml = Get-Content $file.FullName
            $suite = $xml.testsuite
            $results += [PSCustomObject]@{
                TestClass = $tn
                Tests = [int]$suite.tests
                Failures = [int]$suite.failures
                Errors = [int]$suite.errors
                Skipped = [int]$suite.skipped
                Time = [double]$suite.time
                ReportDate = $file.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
            }
        }
    }
}
$results | Sort-Object ReportDate -Descending | Format-Table TestClass, Tests, Failures, Errors, Skipped, Time, ReportDate -AutoSize
$total = ($results | Measure-Object -Property Tests -Sum).Sum
Write-Host "Total tests in matching suites: $total"
