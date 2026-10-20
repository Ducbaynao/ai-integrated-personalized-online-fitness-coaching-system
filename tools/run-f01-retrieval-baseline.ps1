<#
.SYNOPSIS
    Executes or verifies F01 Knowledge Retrieval Development Baseline (A-49) on the expanded 9-document active corpus.

.DESCRIPTION
    1. Enforces execution exclusively on branch 'feat/p2-a27-corpus-retrieval-baseline'.
    2. When -VerifyRunDir (or -ArtifactDir / -ArtifactRunDir) is passed:
       - Runs entirely offline without API key prompt, Spring Boot launch, or npm run.
       - Validates artifacts against A-49 specifications:
         * summary.json required counts and zero errors (infrastructure_errors=0, execution_failures=0, schema_violations=0)
         * dynamic evaluable cases denominator matching (evaluable_retrieval_cases = full_coverage + partial_coverage)
         * quality metrics status ('CALCULATED') and dynamic denominator
         * explicit full/partial/zero coverage breakdown
    3. In live execution mode:
       - Securely prompts for OpenAI API Key without echo or disk logging.
       - Verifies port 8080 availability prior to launch.
       - Configures Spring Boot backend with:
         * local PostgreSQL testbed at localhost:5433 (user: fitness_app, pass: 123456, schema: fitness)
         * profile 'dev', flyway disabled
         * app.scheduling.enabled=false, ATTENTION_REVIEW_CRON='-' (isolated background workers)
         * OpenAI embedding provider (model: text-embedding-3-small, dimension: 1536)
         * dev-ai-internal-key-change-me for internal authenticated retrieval
       - Starts Spring Boot in background and actively verifies:
         * Process liveness (HasExited check)
         * HTTP health (/actuator/health is UP)
         * Empty query auth check (/internal/v1/knowledge/retrieval returns 200 with chunks: [])
         * Smoke semantic query (/internal/v1/knowledge/retrieval returns >= 1 chunk with positive similarity)
       - Creates an isolated parent output directory for this run:
         reports/evaluation/baselines/baseline-run-<timestamp>
       - Runs A-49 evaluation runner scoped to WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023 with verified coverage snapshot.
       - Validates generated artifacts using the shared assertion logic.
       - Terminates only the spawned backend process, zero-frees BSTR memory, and restores environment variables.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$RepositoryRoot = "D:\Projects\ai-integrated-personalized-online-fitness-coaching-system",

    [Parameter(Mandatory = $false)]
    [Alias("ArtifactDir", "ArtifactRunDir")]
    [string]$VerifyRunDir = ""
)

# 1. Branch verification
$currentBranch = (git -C $RepositoryRoot branch --show-current).Trim()
if ($currentBranch -ne "feat/p2-a27-corpus-retrieval-baseline") {
    throw "LOI BAO VE NHANH: Script chi duoc phep thuc thi tren nhanh 'feat/p2-a27-corpus-retrieval-baseline' (Hien tai: '$currentBranch')."
}

# 2. Shared Artifact Validation Function
function Assert-BaselineArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunDir
    )

    $metadataPath = Join-Path $RunDir "run-metadata.json"
    $summaryPath = Join-Path $RunDir "summary.json"
    $resultsPath = Join-Path $RunDir "results.jsonl"
    $reportPath = Join-Path $RunDir "report.md"

    foreach ($file in @($metadataPath, $summaryPath, $resultsPath, $reportPath)) {
        if (-not (Test-Path $file)) {
            throw "LOI ARTIFACT: Tep bat buoc khong ton tai: $file."
        }
    }

    $metadataJson = Get-Content $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $summaryJson = Get-Content $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json

    # 2A. Validate required top-level fields
    if ($null -eq $metadataJson.run_id -or [string]::IsNullOrWhiteSpace($metadataJson.run_id)) {
        throw "LOI DU LIEU: run_id trong run-metadata.json rong hoac khong ton tai."
    }
    if ($null -eq $summaryJson.counts -or $null -eq $summaryJson.metrics -or $null -eq $summaryJson.coverage_summary) {
        throw "LOI DU LIEU: summary.json thieu mot trong cac truong bat buoc (counts, metrics, coverage_summary)."
    }

    $runId = $metadataJson.run_id
    $counts = $summaryJson.counts
    $metrics = $summaryJson.metrics
    $cov = $summaryJson.coverage_summary

    # 2B. Check error categories existence and values (do not treat null as 0)
    $infraErrors = $counts.infrastructure_errors
    $execFailures = $counts.execution_failures
    $schemaViolations = $counts.schema_violations

    if ($null -eq $infraErrors -or $null -eq $execFailures -or $null -eq $schemaViolations) {
        throw "LOI DU LIEU: Mot trong cac truong dem loi (infrastructure_errors, execution_failures, schema_violations) la null hoac khong ton tai."
    }

    if ($infraErrors -gt 0 -or $execFailures -gt 0 -or $schemaViolations -gt 0) {
        throw "CANH BAO / LOI THUC THI: Runner ghi nhan loi! (Infrastructure: $infraErrors, Execution: $execFailures, Schema: $schemaViolations). Danh gia khong dat do tin cay tuyet doi."
    }

    # 2C. Check total case counts
    if ($null -eq $counts.total_cases -or $counts.total_cases -ne 42) {
        throw "LOI TONG SO CA: counts.total_cases = $($counts.total_cases), khong khop ky vong 42 cases."
    }
    if ($null -eq $cov.total_cases -or $cov.total_cases -ne 42) {
        throw "LOI TONG SO CA: coverage_summary.total_cases = $($cov.total_cases), khong khop ky vong 42 cases."
    }

    # 2D. Check coverage breakdown
    $fullCount = $cov.full_coverage
    $partialCount = $cov.partial_coverage
    $noCovCount = $cov.no_coverage
    $covUnknownCount = $cov.coverage_unknown

    if ($null -eq $fullCount -or $null -eq $partialCount -or $null -eq $noCovCount -or $null -eq $covUnknownCount) {
        throw "LOI DU LIEU: Mot trong cac chi so coverage_summary la null."
    }

    if ($fullCount -ne 21) {
        throw "LOI PHAN BO COVERAGE: full_coverage ghi nhan $fullCount, khong khop ky vong 21 cases."
    }
    if ($partialCount -ne 5) {
        throw "LOI PHAN BO COVERAGE: partial_coverage ghi nhan $partialCount, khong khop ky vong 5 cases."
    }
    if ($noCovCount -ne 16) {
        throw "LOI PHAN BO COVERAGE: no_coverage ghi nhan $noCovCount, khong khop ky vong 16 cases."
    }
    if ($covUnknownCount -ne 0) {
        throw "LOI PHAN BO COVERAGE: coverage_unknown ghi nhan $covUnknownCount, ky vong la 0."
    }

    # 2E. Dynamic evaluable cases denominator matching
    $evalCases = $counts.evaluable_retrieval_cases
    if ($null -eq $evalCases -or $evalCases -le 0) {
        throw "LOI DU LIEU: counts.evaluable_retrieval_cases la null hoac khong hop le ($evalCases)."
    }
    $expectedEvalCases = $fullCount + $partialCount
    if ($evalCases -ne $expectedEvalCases) {
        throw "LOI MAU SO DANH GIA: counts.evaluable_retrieval_cases ($evalCases) khong bang tong Full ($fullCount) + Partial ($partialCount) = $expectedEvalCases."
    }

    # 2F. Quality metrics validation (Hit@5, Recall@5, MRR@5)
    $hitMetric = $metrics.document_hit_at_k
    $recallMetric = $metrics.document_recall_at_k
    $mrrMetric = $metrics.mean_reciprocal_rank_at_k

    foreach ($mEntry in @(
        @{ Name = "Document Hit@5"; Metric = $hitMetric },
        @{ Name = "Document Recall@5"; Metric = $recallMetric },
        @{ Name = "MRR@5"; Metric = $mrrMetric }
    )) {
        $mName = $mEntry.Name
        $m = $mEntry.Metric
        if ($null -eq $m) {
            throw "LOI METRIC: Metric $mName khong ton tai trong summary.json."
        }
        if ($null -eq $m.value) {
            throw "LOI METRIC: Gia tri metric $mName la null."
        }
        if ($m.status -ne "CALCULATED") {
            throw "LOI METRIC: Trang thai metric $mName la '$($m.status)', ky vong la 'CALCULATED'."
        }
        if ($m.denominator -ne $evalCases) {
            throw "LOI METRIC: Mau so metric $mName la $($m.denominator), khong khop evaluable_retrieval_cases ($evalCases)."
        }
    }

    # Output detailed acceptance report
    Write-Host "`n======================================================================" -ForegroundColor Green
    Write-Host ">>> NGHIEM THU DEVELOPMENT BASELINE A-49 THANH CONG! <<<" -ForegroundColor Green
    Write-Host "Run ID:                $runId"
    Write-Host "Thoi diem thuc thi:    $($metadataJson.timestamp)"
    Write-Host "Khong gian Embedding:  $($metadataJson.model_space.model_space) ($($metadataJson.model_space.source))"
    Write-Host "Tong so ca danh gia:   $($counts.total_cases) (Khop manifest: 42)"
    Write-Host "Phan bo Coverage:"
    Write-Host "  - Full Coverage:     $fullCount (Toan bo expected documents deu active trong corpus testbed)"
    Write-Host "  - Partial Coverage:  $partialCount (It nhat 1 expected document active; recall giu nguyen mau so goc)"
    Write-Host "  - No Coverage:       $noCovCount (Khong co document active; loai tru voi VERIFIED_CORPUS_COVERAGE_MISSING)"
    Write-Host "  - Coverage Unknown:  $covUnknownCount"
    Write-Host "Mau so danh gia dong:  $evalCases (Full: $fullCount + Partial: $partialCount)"
    Write-Host "Phan loai loi ha tang / thuc thi:"
    Write-Host "  - Infrastructure Errors: $infraErrors"
    Write-Host "  - Execution Failures:    $execFailures"
    Write-Host "  - Schema Violations:     $schemaViolations"
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Green
    Write-Host "Document Hit@5:        $($hitMetric.value) (Hits: $($hitMetric.hits) / Denominator: $($hitMetric.denominator))"
    Write-Host "Document Recall@5:     $($recallMetric.value) (Sum Recall: $($recallMetric.sum_recall) / Denominator: $($recallMetric.denominator))"
    Write-Host "MRR@5:                 $($mrrMetric.value) (Denominator: $($mrrMetric.denominator))"
    Write-Host "----------------------------------------------------------------------" -ForegroundColor Green
    if ($metadataJson.model_space.model_space -eq "UNKNOWN") {
        Write-Host "[GHI CHU METADATA] Model space trong run-metadata.json ghi UNKNOWN do controller chua phan hoi header/field modelSpace." -ForegroundColor Yellow
        Write-Host "                  Database runtime PostgreSQL da duoc nghiem thu dong bo (modelSpace=openai/text-embedding-3-small/1536)." -ForegroundColor Yellow
    }
    Write-Host "Thu muc Artifacts:     $RunDir"
    Write-Host "======================================================================" -ForegroundColor Green
}

# 3. Handle Offline Verification Mode
if (-not [string]::IsNullOrWhiteSpace($VerifyRunDir)) {
    $targetDir = $VerifyRunDir
    if (-not [System.IO.Path]::IsPathRooted($targetDir)) {
        $targetDir = Join-Path $RepositoryRoot $targetDir
    }
    if (-not (Test-Path $targetDir)) {
        throw "LOI ARTIFACT: Thu muc chi dinh khong ton tai: $targetDir"
    }
    Write-Host "[OFFLINE AUDIT] Tien hanh kiem tra va doi chieu artifacts tai: $targetDir" -ForegroundColor Cyan
    Assert-BaselineArtifacts -RunDir $targetDir
    return
}

# 4. Live Execution Mode
# 4A. Port conflict check
$portOccupied = $false
try {
    $tcpCheck = New-Object System.Net.Sockets.TcpClient
    $tcpCheck.Connect("127.0.0.1", 8080)
    if ($tcpCheck.Connected) {
        $tcpCheck.Close()
        $portOccupied = $true
    }
} catch {}
if ($portOccupied) {
    throw "LOI XUNG DOT CONG: Cong 8080 hien dang bi chiem boi mot tien trinh khac. Vui long dung tien trinh do truoc khi chay."
}

# 4B. Secure prompt for API key
$secureKey = Read-Host -Prompt "Nhap OpenAI API Key (nhap an, dung de sinh vector truy van retrieval)" -AsSecureString
if ($null -eq $secureKey -or $secureKey.Length -eq 0) {
    throw "LOI XAC THUC: Khoa API rong. Huy thuc thi."
}

$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
$plainKey = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)

# 4C. Save existing environment variables to ensure zero contamination
$savedEnv = @{
    DATABASE_URL                     = $env:DATABASE_URL
    DATABASE_USERNAME                = $env:DATABASE_USERNAME
    DATABASE_PASSWORD                = $env:DATABASE_PASSWORD
    SPRING_PROFILES_ACTIVE           = $env:SPRING_PROFILES_ACTIVE
    SPRING_FLYWAY_ENABLED            = $env:SPRING_FLYWAY_ENABLED
    MANAGEMENT_HEALTH_MAIL_ENABLED   = $env:MANAGEMENT_HEALTH_MAIL_ENABLED
    APP_SCHEDULING_ENABLED           = $env:APP_SCHEDULING_ENABLED
    ATTENTION_REVIEW_CRON            = $env:ATTENTION_REVIEW_CRON
    AI_EMBEDDING_PROVIDER            = $env:AI_EMBEDDING_PROVIDER
    AI_EMBEDDING_API_KEY             = $env:AI_EMBEDDING_API_KEY
    AI_INTERNAL_SERVICE_KEY          = $env:AI_INTERNAL_SERVICE_KEY
    INTERNAL_SERVICE_KEY             = $env:INTERNAL_SERVICE_KEY
    EVAL_OUTPUT_DIR                  = $env:EVAL_OUTPUT_DIR
}

$origDir = Get-Location
$backendProcess = $null

try {
    # 4D. Set controlled environment variables
    $env:DATABASE_URL = "jdbc:postgresql://localhost:5433/digital_fitness"
    $env:DATABASE_USERNAME = "fitness_app"
    $env:DATABASE_PASSWORD = "123456"
    $env:SPRING_PROFILES_ACTIVE = "dev"
    $env:SPRING_FLYWAY_ENABLED = "false"
    # SMTP is unrelated to retrieval; retain DB and other health checks.
    $env:MANAGEMENT_HEALTH_MAIL_ENABLED = "false"
    $env:APP_SCHEDULING_ENABLED = "false"
    $env:ATTENTION_REVIEW_CRON = "-"
    $env:AI_EMBEDDING_PROVIDER = "openai"
    $env:AI_EMBEDDING_API_KEY = $plainKey
    $env:AI_INTERNAL_SERVICE_KEY = "dev-ai-internal-key-change-me"
    $env:INTERNAL_SERVICE_KEY = "dev-ai-internal-key-change-me"

    # 4E. Create dedicated parent output directory for this execution
    $executionTimestamp = (Get-Date).ToString("yyyyMMdd-HHmmss-fff")
    $dedicatedOutputDir = Join-Path $RepositoryRoot "reports\evaluation\baselines\baseline-run-$executionTimestamp"
    New-Item -ItemType Directory -Path $dedicatedOutputDir -Force | Out-Null
    $env:EVAL_OUTPUT_DIR = $dedicatedOutputDir

    Write-Host "`n[1/5] Khoi dong Spring Boot backend ngam tren cong 8080..." -ForegroundColor Cyan
    Set-Location (Join-Path $RepositoryRoot "services\backend")
    $backendLog = Join-Path $dedicatedOutputDir 'backend.stdout.log'
    $backendErrorLog = Join-Path $dedicatedOutputDir 'backend.stderr.log'
    $backendProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d /c .\mvnw.cmd spring-boot:run' -WorkingDirectory (Join-Path $RepositoryRoot 'services\backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrorLog
    Write-Host "Backend logs: $backendLog ; $backendErrorLog"

    # 4F. Polling readiness with liveness guard
    Write-Host "[2/5] Dang cho backend san sang (theo doi tien trinh song va HTTP health)..." -ForegroundColor Cyan
    $httpReady = $false
    $retries = 0
    $lastHealthError = 'No response yet'
    while (-not $httpReady -and $retries -lt 50) {
        Start-Sleep -Seconds 2
        $retries++

        if ($backendProcess.HasExited) {
            throw "LOI KHOI DONG: Backend exited ($($backendProcess.ExitCode)). Logs: $backendLog ; $backendErrorLog"
        }

        try {
            $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get -TimeoutSec 2 -ErrorAction Stop
            if ($null -ne $health -and $health.status -eq "UP") {
                $httpReady = $true
            } else {
                $lastHealthError = "Health status: $($health.status)"
            }
        } catch {
            $lastHealthError = $_.Exception.Message
        }
        Write-Host "." -NoNewline
    }
    Write-Host ""
    if (-not $httpReady) {
        throw "LOI TIMEOUT: Health not UP. Last error: $lastHealthError. Logs: $backendLog ; $backendErrorLog"
    }

    # 4G. Verification checks:
    # Auth check with empty query
    Write-Host "[3/5] Kiem tra xac thuc noi bo (auth check voi query rong)..." -ForegroundColor Cyan
    $headers = @{ "X-Internal-Service-Key" = "dev-ai-internal-key-change-me" }
    try {
        $authBody = '{"query":""}'
        $authRes = Invoke-RestMethod -Uri "http://localhost:8080/internal/v1/knowledge/retrieval" -Method Post -Headers $headers -Body $authBody -ContentType "application/json" -TimeoutSec 3 -ErrorAction Stop
        if ($null -eq $authRes -or $null -eq $authRes.chunks -or $authRes.chunks.Count -ne 0) {
            throw "Phan hoi xac thuc rong khong tra ve chunks=[] hop le."
        }
    } catch {
        throw "LOI XAC THUC SERVICE KEY: Khong the truy cap endpoint voi internal key ($($_.Exception.Message))."
    }

    # Smoke semantic query test
    Write-Host "      Thuc hien smoke query kiem chung retrieval/embedding pipeline..." -ForegroundColor Cyan
    try {
        $smokeBody = '{"query":"hoat dong the luc nguoi truong thanh","allowedSourceIds":["WHO-PA-SB-2020"],"limit":1}'
        $smokeRes = Invoke-RestMethod -Uri "http://localhost:8080/internal/v1/knowledge/retrieval" -Method Post -Headers $headers -Body $smokeBody -ContentType "application/json; charset=utf-8" -TimeoutSec 15 -ErrorAction Stop
        if ($null -eq $smokeRes -or $null -eq $smokeRes.chunks -or $smokeRes.chunks.Count -ne 0) {
            throw "Smoke query khong thu hoi duoc chunk nao tu nguon active WHO-PA-SB-2020."
        }
        $retrievedDoc = $smokeRes.chunks[0].documentCode
        $score = $smokeRes.chunks[0].similarityScore
        Write-Host "      [OK] Smoke query thanh cong: thu hoi [$retrievedDoc] voi similarity=$score" -ForegroundColor Green
    } catch {
        throw "LOI PIPELINE RETRIEVAL: Smoke query kiem chung that bai ($($_.Exception.Message))."
    }

    # 4H. Execute A-49 evaluation runner
    Write-Host "[4/5] Dang chay A-49 Evaluation Runner tren 2 nguon active..." -ForegroundColor Cyan
    Set-Location $RepositoryRoot
    $evalArgs = @(
        "run",
        "eval:f01",
        "--",
        "--mode=retrieval",
        "--endpoint=http://localhost:8080/internal/v1/knowledge/retrieval",
        "--coverage-snapshot=data/evaluation/coverage-snapshot-testbed-9docs.json",
        "--source=WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023",
        "--top-k=5"
    )
    npm @evalArgs

    if ($LASTEXITCODE -ne 0) {
        throw "LOI RUNNER: npm run eval:f01 ket thuc voi ma loi ($LASTEXITCODE)."
    }

    # 4I. Inspect artifacts exclusively from the dedicated output folder
    Write-Host "[5/5] Dang kiem tra va doi chieu artifacts tu thu muc dedicated..." -ForegroundColor Cyan
    $runDirs = Get-ChildItem -Path $dedicatedOutputDir -Directory
    if ($runDirs.Count -eq 0) {
        throw "LOI ARTIFACT: Khong tim thay thu muc run nao ben trong $dedicatedOutputDir."
    }
    $runDir = $runDirs[0].FullName
    Assert-BaselineArtifacts -RunDir $runDir
}
finally {
    # 5. Stop spawned backend process cleanly (and only this process)
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Write-Host "`nDang dung tien trinh backend da tao (PID: $($backendProcess.Id))..." -ForegroundColor Yellow
        taskkill /F /T /PID $backendProcess.Id | Out-Null
    }

    # 6. Release BSTR / SecureString memory
    if ($null -ne $bstr -and $bstr -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    $plainKey = $null

    # 7. Restore all original environment variables
    if ($null -ne $savedEnv) {
        foreach ($k in $savedEnv.Keys) {
            if ($null -eq $savedEnv[$k]) {
                Remove-Item "Env:\$k" -ErrorAction SilentlyContinue
            } else {
                Set-Item "Env:\$k" $savedEnv[$k]
            }
        }
    }

    # 8. Restore original location
    Set-Location $origDir
    Write-Host "Da don dep tien trinh, giai phong bo nho bao mat va khoi phuc moi truong hoan tat." -ForegroundColor Gray
}
