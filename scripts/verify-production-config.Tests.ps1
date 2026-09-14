$scriptPath = Join-Path $PSScriptRoot 'verify-production-config.ps1'
$modulePath = Join-Path $PSScriptRoot 'ProductionConfigCheck.psm1'

Import-Module $modulePath -Force

function New-CompleteProductionEnvironment {
    $environment = @{}
    foreach ($definition in Get-ProductionConfigDefinitions) {
        switch ([string]$definition.kind) {
            'equals' { $environment[[string]$definition.variable] = [string]$definition.expected }
            'https-uri' { $environment[[string]$definition.variable] = 'https://production.example.test/service' }
            'secret-ref' { $environment[[string]$definition.variable] = 'secret://production/skill-center' }
            'postgres-url' { $environment[[string]$definition.variable] = 'jdbc:postgresql://postgres.example.test:5432/skillcenter' }
            'capabilities' { $environment[[string]$definition.variable] = [string]$definition.expected }
            'required' { $environment[[string]$definition.variable] = 'configured-value' }
        }
    }
    return $environment
}

Describe 'verify-production-config contract' {
    It 'provides a production configuration preflight script' {
        Test-Path -LiteralPath $scriptPath | Should Be $true
    }

    It 'fails closed with safe missing checks when no production variables are present' {
        $report = Get-ProductionConfigReport -Environment @{}

        $report.passed | Should Be $false
        $report.notReady | Should Be $report.checkCount
        @($report.checks | Where-Object status -eq 'MISSING').Count | Should BeGreaterThan 0
        ($report | ConvertTo-Json -Depth 8) | Should Not Match '(?i)super-secret|password=value|jdbc:postgresql://'
    }

    It 'accepts a complete production contract without exposing secret values' {
        $environment = New-CompleteProductionEnvironment
        $environment['SKILL_CENTER_POSTGRES_PASSWORD'] = 'super-secret-database-value'

        $report = Get-ProductionConfigReport -Environment $environment

        $report.passed | Should Be $true
        $report.notReady | Should Be 0
        ($report | ConvertTo-Json -Depth 8) | Should Not Match 'super-secret-database-value'
    }

    It 'rejects plaintext credentials and non-HTTPS endpoints' {
        $environment = New-CompleteProductionEnvironment
        $environment['SKILL_CENTER_LANGFUSE_CREDENTIAL_REF'] = 'plain-token'
        $environment['SKILL_CENTER_SEARCH_INDEX_ENDPOINT'] = 'http://search.example.test'

        $report = Get-ProductionConfigReport -Environment $environment

        $report.passed | Should Be $false
        @($report.checks | Where-Object { $_.variable -eq 'SKILL_CENTER_LANGFUSE_CREDENTIAL_REF' }).status | Should Be 'INVALID'
        @($report.checks | Where-Object { $_.variable -eq 'SKILL_CENTER_SEARCH_INDEX_ENDPOINT' }).reasonCode | Should Be 'PRODUCTION_CONFIG_HTTPS_REQUIRED'
    }

    It 'requires all external scanner capability classes' {
        $environment = New-CompleteProductionEnvironment
        $environment['SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CAPABILITIES'] = 'MALWARE,LICENSE'

        $report = Get-ProductionConfigReport -Environment $environment

        @($report.checks | Where-Object { $_.variable -eq 'SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CAPABILITIES' }).reasonCode |
            Should Be 'PRODUCTION_CONFIG_CAPABILITIES_INCOMPLETE'
    }

    It 'rejects JDBC URLs with embedded user information' {
        $environment = New-CompleteProductionEnvironment
        $environment['SKILL_CENTER_POSTGRES_URL'] = 'jdbc:postgresql://db-user:db-password@postgres.example.test:5432/skillcenter'

        $report = Get-ProductionConfigReport -Environment $environment

        @($report.checks | Where-Object { $_.variable -eq 'SKILL_CENTER_POSTGRES_URL' }).reasonCode |
            Should Be 'PRODUCTION_CONFIG_POSTGRES_URL_INVALID'
    }

    It 'exposes safe JSON and fail-closed CLI controls' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[switch\]\$Json'
        $source | Should Match '\[switch\]\$FailOnNotReady'
        $source | Should Match 'ProductionConfigCheck\.psm1'
        $source | Should Match 'exit 2'
        $source | Should Not Match '(?im)\b(password|secret|token)\s*[:=]\s*\$environment'
    }

    It 'keeps every checked runtime variable wired into Spring configuration' {
        $applicationYaml = Get-Content (Join-Path $PSScriptRoot '..\apps\api\src\main\resources\application.yml') -Raw
        $missing = @(
            foreach ($definition in Get-ProductionConfigDefinitions) {
                $variable = [string]$definition.variable
                if ($variable -ne 'SKILL_CENTER_ENVIRONMENT' -and $applicationYaml -notmatch [regex]::Escape($variable)) {
                    $variable
                }
            }
        )

        $missing.Count | Should Be 0
    }
}
