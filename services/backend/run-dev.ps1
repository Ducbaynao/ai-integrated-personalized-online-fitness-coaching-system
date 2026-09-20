# Launcher script for local backend development
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

# Locate root .env file (either from repo root or services/backend)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootEnvPath = Join-Path $ScriptDir "..\..\.env"
if (-not (Test-Path $RootEnvPath)) {
    $RootEnvPath = Join-Path (Get-Location) ".env"
}

if (-not (Test-Path $RootEnvPath)) {
    Write-Error "Error: Root .env file not found. Copy .env.example to .env at the repository root and configure your local credentials."
    exit 1
}

# Allowlist of variables to load into backend environment
$Allowlist = @("DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD", "BACKEND_PORT")
$RequiredVars = @("DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD")

$LoadedKeys = @()

# Parse .env line by line safely without Invoke-Expression
Get-Content -Path $RootEnvPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$')) {
        $key = $Matches[1]
        $val = $Matches[2].Trim()
        if (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'"))) {
            $val = $val.Substring(1, $val.Length - 2)
        }
        if ($Allowlist -contains $key) {
            [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
            $LoadedKeys += $key
        }
    }
}

# Verify required variables are non-empty
foreach ($varName in $RequiredVars) {
    $val = [System.Environment]::GetEnvironmentVariable($varName, "Process")
    if ([string]::IsNullOrWhiteSpace($val)) {
        Write-Error "Error: Required variable '$varName' is missing or empty in .env."
        exit 1
    }
}

Write-Host "Loaded allowlisted environment variables from .env: $($LoadedKeys -join ', ')"
# Secrets and passwords are intentionally never printed to the terminal

$env:SPRING_PROFILES_ACTIVE = "dev"

$mvnw = Join-Path $ScriptDir "mvnw.cmd"
if (-not (Test-Path $mvnw)) {
    $mvnw = ".\mvnw.cmd"
}

& $mvnw spring-boot:run
