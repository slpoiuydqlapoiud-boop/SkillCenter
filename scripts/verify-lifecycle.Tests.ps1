$scriptPath = Join-Path $PSScriptRoot 'verify-lifecycle.ps1'

Describe 'verify-lifecycle execution contract' {
    It 'runs the full API suite with forkCount zero for deterministic Windows cleanup' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match 'Invoke-Checked "mvn\.cmd" @\("-q", "-DforkCount=0", "test"\) \$apiRoot'
    }

    It 'supports an explicit integration readiness gate for local shared backends' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[switch\]\$CheckIntegration'
        $source | Should Match 'PERSISTENCE_CONTROL_PLANE'
        $source | Should Match 'SKILL_SEARCH_INDEX'
        $source | Should Match 'Assert-IntegrationReadiness'
    }

    It 'supports an explicit fail-closed production handoff gate without changing the default path' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[switch\]\$CheckProductionHandoff'
        $source | Should Match '\[string\]\$ProductionHandoffBaseUrl'
        $source | Should Match 'verify-production-handoff\.ps1'
        $source | Should Match 'FailOnNotReady'
        $source | Should Match 'Assert-ProductionHandoff'
    }

    It 'preserves the production handoff not-ready exit code and parses in Windows PowerShell' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\$exitCode -eq 2'
        $source | Should Match 'exit 2'

        $parseCommand = '$source = Get-Content -Raw -LiteralPath "' + $scriptPath + '"; [scriptblock]::Create($source) | Out-Null'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $parseCommand
        $LASTEXITCODE | Should Be 0
    }

    It 'supports an explicit production configuration preflight gate' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[switch\]\$CheckProductionConfig'
        $source | Should Match 'verify-production-config\.ps1'
        $source | Should Match 'Assert-ProductionConfig'
    }
}
