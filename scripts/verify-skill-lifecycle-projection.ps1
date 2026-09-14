param(
    [string]$ConfigPathOverride = '',
    [string]$MigrationPathOverride = '',
    [string]$SurefireRootOverride = '',
    [switch]$ValidateConfigurationOnly,
    [switch]$ValidateSkipPolicyOnly,
    [string]$SkipSuiteName = '',
    [int]$SkippedCount = 0,
    [string[]]$SkipBody = @()
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apiPom = Join-Path $repoRoot 'apps\api\pom.xml'
$webRoot = Join-Path $repoRoot 'apps\web'
$configPath = if ($ConfigPathOverride) { $ConfigPathOverride } else { Join-Path $repoRoot 'apps\api\src\main\resources\application.yml' }
$migrationPath = if ($MigrationPathOverride) { $MigrationPathOverride } else { Join-Path $repoRoot 'apps\api\src\main\resources\db\migration\V2__create_skill_lifecycle_projection.sql' }
$surefireRoot = if ($SurefireRootOverride) { $SurefireRootOverride } else { Join-Path $repoRoot 'apps\api\target\surefire-reports' }
$failures = [System.Collections.Generic.List[string]]::new()
$passedChecks = [System.Collections.Generic.List[string]]::new()
$capabilitySkips = [System.Collections.Generic.List[string]]::new()
$lifecycleSuites = @(
    'SkillLifecycleProjectionHasherTest',
    'SkillLifecycleProjectionServiceTest',
    'SkillLifecycleProjectionControllerTest',
    'PostgresSkillLifecycleProjectionStoreTest',
    'PersistenceBackendConfigurationTest',
    'SkillAuthorizationBoundaryTest',
    'SkillRelationServiceTest',
    'ReleaseAdmissionServiceTest',
    'ReleaseAdmissionDistributionTest',
    'ApiErrorContractTest',
    'SensitiveResponseContractTest',
    'PersistenceArtifactCatalogTest',
    'JdbcQualityEvidenceStoreTest',
    'PostgresQualityEvidenceIntegrationTest'
)
$dockerCapabilitySuites = @(
    'PostgresSkillLifecycleProjectionStoreTest',
    'PostgresQualityEvidenceIntegrationTest'
)
$dockerSkipMarker = 'CAPABILITY_SKIP: Docker is unavailable'

function Invoke-Native([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$FilePath exited with code $exitCode"
    }
}

function Invoke-Verification([string]$Name, [scriptblock]$Action) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    try {
        & $Action
        $passedChecks.Add($Name)
        Write-Host "$Name passed" -ForegroundColor Green
    } catch {
        $failures.Add("$Name failed: $($_.Exception.Message)")
        Write-Host "$Name failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Get-YamlSection([string]$Content, [string]$SectionName) {
    $escapedName = [regex]::Escape($SectionName)
    $match = [regex]::Match($Content, "(?ms)^ {2}${escapedName}:\s*\r?\n(?<body>(?:(?!^ {2}\S).)*)")
    if (-not $match.Success) { throw "application.yml section '$SectionName' is missing or has unexpected indentation" }
    return $match.Groups['body'].Value
}

function Assert-YamlValue([string]$Section, [string]$Key, [string]$ExpectedValue) {
    $escapedKey = [regex]::Escape($Key)
    $escapedValue = [regex]::Escape($ExpectedValue)
    if ($Section -notmatch "(?m)^ {4}${escapedKey}:\s*${escapedValue}\s*$") {
        throw "application.yml must set $Key to '$ExpectedValue' in the expected section"
    }
}

function Assert-LifecycleConfiguration([string]$Config, [string]$Migration) {
    $persistence = Get-YamlSection $Config 'persistence'
    $projection = Get-YamlSection $Config 'lifecycle-projection'
    Assert-YamlValue $persistence 'backend' 'json'
    Assert-YamlValue $persistence 'startup-mode' 'fail-closed'
    Assert-YamlValue $projection 'backend' 'json'
    Assert-YamlValue $projection 'source-mode' 'offline-snapshot'
    Assert-YamlValue $projection 'import-mode' 'explicit-admin'
    Assert-YamlValue $projection 'startup-mode' 'fail-closed'
    Assert-YamlValue $projection 'allow-json-fallback' 'false'
    $freshnessMatch = [regex]::Match($projection, '(?m)^ {4}max-source-age-seconds:\s*(?<value>\d+)\s*$')
    if (-not $freshnessMatch.Success) {
        throw 'application.yml must set max-source-age-seconds in the lifecycle-projection section'
    }
    $maxSourceAgeSeconds = [int64]$freshnessMatch.Groups['value'].Value
    if ($maxSourceAgeSeconds -lt 60 -or $maxSourceAgeSeconds -gt 86400) {
        throw 'max-source-age-seconds must be between 60 and 86400'
    }
    foreach ($line in ($Config -split "`r?`n")) {
        if ($line -match '(?im)^\s*(password|secret|token)\s*:\s*(?<value>.*)$') {
            $value = $Matches.value.Trim()
            if ($value -ne '""' -and $value -notmatch '^\$\{[^}]+\}$') {
                throw 'application.yml contains an inline credential value'
            }
        }
    }
    if (-not (Test-Path -LiteralPath $Migration)) { throw 'lifecycle projection V2 migration is missing' }
}

function Assert-SurefireSkipPolicy([string]$SuiteName, [int]$Skipped, [string[]]$SkipBodies) {
    if ($Skipped -le 0) { return }
    if ($dockerCapabilitySuites -notcontains $SuiteName) {
        throw "$SuiteName has arbitrary skipped tests; only named Docker capability skips are allowed"
    }
    if ($SkipBodies.Count -ne $Skipped -or
        @($SkipBodies | Where-Object { $_ -notmatch [regex]::Escape($dockerSkipMarker) }).Count -gt 0) {
        throw "$SuiteName skips are not the explicit Docker capability skip"
    }
}

function Get-FreshSurefireReports([datetime]$PhaseStartUtc) {
    if (-not (Test-Path -LiteralPath $surefireRoot)) {
        throw "Surefire report directory is missing: $surefireRoot"
    }

    $reports = @(Get-ChildItem -LiteralPath $surefireRoot -Filter 'TEST-*.xml' -File |
        Where-Object { $_.LastWriteTimeUtc -ge $PhaseStartUtc })
    if ($reports.Count -eq 0) {
        throw 'No fresh Surefire reports were produced for the verification phase'
    }
    return $reports
}

function Assert-SurefireEvidence([datetime]$PhaseStartUtc) {
    $reports = Get-FreshSurefireReports $PhaseStartUtc
    foreach ($suiteName in $lifecycleSuites) {
        $matches = @($reports | Where-Object { $_.Name -like "*$suiteName.xml" })
        if ($matches.Count -ne 1) {
            throw "Fresh Surefire report is missing or ambiguous for $suiteName"
        }

        [xml]$document = Get-Content -Raw -LiteralPath $matches[0].FullName
        $suite = $document.testsuite
        $failuresCount = [int]$suite.failures
        $errorsCount = [int]$suite.errors
        $skippedCount = [int]$suite.skipped
        if ($failuresCount -gt 0 -or $errorsCount -gt 0) {
            throw "$suiteName has failures=$failuresCount errors=$errorsCount"
        }

        if ($skippedCount -gt 0) {
            $skipBodies = @($suite.testcase | ForEach-Object {
                    if ($null -ne $_.skipped) { [string]$_.skipped.InnerText }
                })
            Assert-SurefireSkipPolicy $suiteName $skippedCount $skipBodies

            $capabilitySkips.Add("$suiteName skipped $skippedCount test(s): $dockerSkipMarker")
        }
    }
}

Invoke-Verification 'Lifecycle configuration and migration boundary' {
    $config = Get-Content -Raw -LiteralPath $configPath
    Assert-LifecycleConfiguration $config $migrationPath
}

if ($ValidateSkipPolicyOnly) {
    try { Assert-SurefireSkipPolicy $SkipSuiteName $SkippedCount $SkipBody; exit 0 }
    catch { Write-Error $_.Exception.Message; exit 1 }
}

if ($ValidateConfigurationOnly) {
    if ($failures.Count -gt 0) { exit 1 }
    exit 0
}

Invoke-Verification 'Focused API lifecycle and authorization tests' {
    $phaseStartUtc = [datetime]::UtcNow
    $testSelector = $lifecycleSuites -join ','
    Invoke-Native 'mvn.cmd' @('-q', '-f', $apiPom, '-DforkCount=0', "-Dtest=$testSelector", 'test')
    Assert-SurefireEvidence $phaseStartUtc
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
