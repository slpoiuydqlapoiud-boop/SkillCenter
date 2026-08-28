$scriptPath = Join-Path $PSScriptRoot "verify-environment.ps1"

Describe "verify-environment contract" {
    It "parses as a PowerShell script" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        { [scriptblock]::Create($content) } | Should Not Throw
    }

    It "emits machine-readable checks for the development toolchain" {
        $json = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Json
        $LASTEXITCODE | Should Be 0

        $checks = @($json | ConvertFrom-Json)
        ($checks.Id -contains "jdk") | Should Be $true
        ($checks.Id -contains "maven") | Should Be $true
        ($checks.Id -contains "node") | Should Be $true
        ($checks.Id -contains "npm") | Should Be $true
        ($checks.Id -contains "python") | Should Be $true
        ($checks.Id -contains "python-jsonschema") | Should Be $true
        ($checks.Id -contains "docker-engine") | Should Be $true
        ($checks.Id -contains "docker-compose") | Should Be $true
        $checks | ForEach-Object {
            $_.Status | Should Match "^(READY|MISSING|UNAVAILABLE)$"
            $_.Requirement | Should Match "^(development|contract-tests|integration)$"
            ($_.PSObject.Properties.Name -contains "InstallHint") | Should Be $true
        }
    }

    It "can include local service reachability checks without exposing credentials" {
        $json = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Json -CheckServices
        $LASTEXITCODE | Should Be 0

        $checks = @($json | ConvertFrom-Json)
        ($checks.Id -contains "postgres") | Should Be $true
        ($checks.Id -contains "redis") | Should Be $true
        ($checks.Id -contains "minio") | Should Be $true
        ($checks.Id -contains "opensearch") | Should Be $true
        ($json -join "") | Should Not Match "(?i)(password|secret|access[_-]?key|token)\s*[:=]"
    }

    It "is non-destructive and supports a fail-closed mode" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        $content | Should Match '\[switch\]\$FailOnMissing'
        $content | Should Not Match "(?im)\bgit\s+(reset|clean|checkout|commit|push)\b"
        $content | Should Not Match "(?im)\b(Remove-Item|del|erase|rm)\b"
    }
}
