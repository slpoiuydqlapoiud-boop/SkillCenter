$scriptPath = Join-Path $PSScriptRoot "verify-postgres-quality-evidence.ps1"
$helperPath = Join-Path $PSScriptRoot "VerifyPostgresQualityEvidence.psm1"

function Write-SurefireFixture([string]$Path, [string]$SuiteName, [int]$Skipped, [string[]]$SkipBodies) {
    $cases = foreach ($body in $SkipBodies) {
        '<testcase name="case"><skipped><![CDATA[{0}]]></skipped></testcase>' -f $body
    }
    $xml = '<testsuite name="{0}" tests="{1}" failures="0" errors="0" skipped="{2}">{3}</testsuite>' -f $SuiteName, $SkipBodies.Count, $Skipped, ($cases -join '')
    Set-Content -LiteralPath $Path -Value $xml -Encoding UTF8
}

Describe "verify-postgres-quality-evidence" {
    It "parses as a PowerShell script" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        { [scriptblock]::Create($content) } | Should Not Throw
        $content | Should Not Match '\$Phase:'
    }

    It "requires every verification gate and treats missing web dependencies as a failure" {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ""
        }

        $content | Should Match '\$ErrorActionPreference\s*=\s*["'']Stop["'']'
        $content | Should Match 'PersistenceBackendConfigurationTest,PersistenceStartupGateTest,PersistenceSnapshotServiceTest,JdbcQualityEvidenceStoreTest,PostgresQualityEvidenceIntegrationTest'
        $content | Should Match 'npm\.cmd.*test'
        $content | Should Match 'npm\.cmd.*run.*build'
        $content | Should Match 'node_modules.*(missing|Missing|unavailable)'
        $content | Should Match 'git.*diff.*--check'
    }

    It "requires Surefire evidence and does not contain destructive repository commands" {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ""
        }

        $helperContent = Get-Content -Raw -LiteralPath $helperPath
        $content + $helperContent | Should Match 'PostgresQualityEvidenceIntegrationTest'
        $content | Should Match 'surefire-reports'
        $helperContent | Should Match 'CAPABILITY_SKIP'
        $helperContent | Should Match '\.InnerText'
        $content | Should Not Match '(?im)\bgit\s+(reset|clean|checkout|commit|push)\b'
        $content | Should Not Match '(?im)\b(Remove-Item|del|rm)\b'
    }

    It "rejects stale-only PostgreSQL reports rather than using prior evidence" {
        Import-Module $helperPath -Force
        $reports = Join-Path $TestDrive 'surefire-reports'
        New-Item -ItemType Directory -Path $reports -Force | Out-Null
        $report = Join-Path $reports 'TEST-com.example.PostgresQualityEvidenceIntegrationTest.xml'
        Write-SurefireFixture $report 'com.example.PostgresQualityEvidenceIntegrationTest' 0 @()
        (Get-Item $report).LastWriteTimeUtc = [datetime]::UtcNow.AddMinutes(-2)

        try { Get-PhaseSurefireEvidence -SurefireRoot $reports -PhaseStartUtc ([datetime]::UtcNow.AddMinutes(-1)) -Phase 'fixture'; throw 'expected stale report rejection' } catch { $_.Exception.Message | Should Match 'PostgresQualityEvidenceIntegrationTest Surefire report is missing or stale' }
    }

    It "rejects mixed PostgreSQL skip reasons" {
        Import-Module $helperPath -Force
        $reports = Join-Path $TestDrive 'surefire-reports'
        New-Item -ItemType Directory -Path $reports -Force | Out-Null
        $report = Join-Path $reports 'TEST-com.example.PostgresQualityEvidenceIntegrationTest.xml'
        Write-SurefireFixture $report 'com.example.PostgresQualityEvidenceIntegrationTest' 2 @(
            'CAPABILITY_SKIP: Docker is unavailable',
            'Assumption failed: external database disabled')

        try { Get-PhaseSurefireEvidence -SurefireRoot $reports -PhaseStartUtc ([datetime]::UtcNow.AddMinutes(-1)) -Phase 'fixture'; throw 'expected mixed skip rejection' } catch { $_.Exception.Message | Should Match 'not the explicit Docker capability skip' }
    }
}
