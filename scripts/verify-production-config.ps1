param(
    [switch]$Json,
    [switch]$FailOnNotReady
)

$ErrorActionPreference = 'Stop'

try {
    Import-Module (Join-Path $PSScriptRoot 'ProductionConfigCheck.psm1') -Force
    $environment = @{}
    foreach ($definition in Get-ProductionConfigDefinitions) {
        $environment[[string]$definition.variable] = [System.Environment]::GetEnvironmentVariable([string]$definition.variable)
    }
    $report = Get-ProductionConfigReport -Environment $environment

    if ($Json) {
        $report | ConvertTo-Json -Depth 8
    } else {
        Write-Host 'SkillCenter production configuration preflight' -ForegroundColor Cyan
        Write-Host "Result: $(if ($report.passed) { 'READY' } else { 'NOT_READY' }); checks: $($report.ready)/$($report.checkCount) ready"
        @($report.checks | ForEach-Object { [pscustomobject]$_ }) |
            Format-Table id, variable, status, reasonCode, hint -AutoSize | Out-Host
    }

    if ($FailOnNotReady -and -not $report.passed) {
        exit 2
    }

    exit 0
} catch {
    if ($Json) {
        [pscustomobject][ordered]@{
            passed = $false
            errorCode = 'PRODUCTION_CONFIG_CHECK_FAILED'
            errorMessage = 'Unable to evaluate production configuration preflight'
        } | ConvertTo-Json -Depth 4
    } else {
        Write-Error 'Unable to evaluate production configuration preflight'
    }
    exit 1
}
