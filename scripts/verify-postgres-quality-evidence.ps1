param()

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apiPom = Join-Path $repoRoot 'apps\api\pom.xml'
$webRoot = Join-Path $repoRoot 'apps\web'
$surefireRoot = Join-Path $repoRoot 'apps\api\target\surefire-reports'
$helperPath = Join-Path $PSScriptRoot 'VerifyPostgresQualityEvidence.psm1'
$failures = [System.Collections.Generic.List[string]]::new()
$passedChecks = [System.Collections.Generic.List[string]]::new()
$capabilitySkips = [System.Collections.Generic.List[string]]::new()

function Invoke-Verification([string]$Name, [scriptblock]$Action) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    try {
        & $Action
        $passedChecks.Add($Name)
        Write-Host "$Name passed" -ForegroundColor Green
    } catch {
        $message = "$Name failed: $($_.Exception.Message)"
        $failures.Add($message)
        Write-Host $message -ForegroundColor Red
    }
}

function Invoke-Native([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "$FilePath exited with code $exitCode" }
}

Import-Module $helperPath -Force

Invoke-Verification 'Focused API persistence and quality tests' {
    $phaseStartUtc = [datetime]::UtcNow
    Invoke-Native 'mvn.cmd' @('-q', '-f', $apiPom, '-DforkCount=0', '-Dtest=PersistenceBackendConfigurationTest,PersistenceStartupGateTest,PersistenceSnapshotServiceTest,JdbcQualityEvidenceStoreTest,PostgresQualityEvidenceIntegrationTest', 'test')
    $evidence = Get-PhaseSurefireEvidence -SurefireRoot $surefireRoot -PhaseStartUtc $phaseStartUtc -Phase 'Focused API tests'
    Write-Host "Focused API tests Surefire: tests=$($evidence.Totals.Tests), failures=$($evidence.Totals.Failures), errors=$($evidence.Totals.Errors), skips=$($evidence.Totals.Skipped)"
    if ($null -ne $evidence.CapabilitySkip) { $capabilitySkips.Add($evidence.CapabilitySkip) }
}

Invoke-Verification 'Full API tests' {
    $phaseStartUtc = [datetime]::UtcNow
    Invoke-Native 'mvn.cmd' @('-q', '-f', $apiPom, '-DforkCount=0', 'test')
    $evidence = Get-PhaseSurefireEvidence -SurefireRoot $surefireRoot -PhaseStartUtc $phaseStartUtc -Phase 'Full API tests'
    Write-Host "Full API tests Surefire: tests=$($evidence.Totals.Tests), failures=$($evidence.Totals.Failures), errors=$($evidence.Totals.Errors), skips=$($evidence.Totals.Skipped)"
    if ($null -ne $evidence.CapabilitySkip) { $capabilitySkips.Add($evidence.CapabilitySkip) }
}

Invoke-Verification 'Web dependencies' {
    if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'node_modules'))) {
        throw 'apps/web/node_modules is missing; npm test and npm run build cannot be verified'
    }
}

Invoke-Verification 'Web tests' {
    Push-Location $webRoot
    try { Invoke-Native 'npm.cmd' @('test') } finally { Pop-Location }
}

Invoke-Verification 'Web build' {
    Push-Location $webRoot
    try { Invoke-Native 'npm.cmd' @('run', 'build') } finally { Pop-Location }
}

Invoke-Verification 'Git whitespace check' {
    Push-Location $repoRoot
    try { Invoke-Native 'git' @('diff', '--check') } finally { Pop-Location }
}

Write-Host "`n=== Verification summary ===" -ForegroundColor Cyan
Write-Host "Passed checks: $($passedChecks.Count)"
$passedChecks | ForEach-Object { Write-Host "- $_" -ForegroundColor Green }
Write-Host "Capability skips: $($capabilitySkips.Count)"
$capabilitySkips | ForEach-Object { Write-Host "- $_" -ForegroundColor Yellow }
Write-Host "Failures: $($failures.Count)"
$failures | ForEach-Object { Write-Host "- $_" -ForegroundColor Red }

if ($failures.Count -gt 0) { exit 1 }
exit 0
