$scriptPath = Join-Path $PSScriptRoot 'verify-skill-lifecycle-projection.ps1'

Describe 'verify-skill-lifecycle-projection contract' {
    It 'parses as a PowerShell script' {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ''
        }

        { [scriptblock]::Create($content) } | Should Not Throw
    }

    It 'requires lifecycle, persistence, authorization, web and diff gates' {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ''
        }

        $content | Should Match '\$ErrorActionPreference\s*=\s*["'']Stop["'']'
        $content | Should Match 'SkillLifecycleProjectionHasherTest'
        $content | Should Match 'SkillLifecycleProjectionServiceTest'
        $content | Should Match 'SkillLifecycleProjectionControllerTest'
        $content | Should Match 'PostgresSkillLifecycleProjectionStoreTest'
        $content | Should Match 'PersistenceBackendConfigurationTest'
        $content | Should Match 'SkillAuthorizationBoundaryTest'
        $content | Should Match 'ApiErrorContractTest'
        $content | Should Match 'SensitiveResponseContractTest'
        $content | Should Match 'npm\.cmd.*test'
        $content | Should Match 'npm\.cmd.*run.*build'
        $content | Should Match 'git.*diff.*--check'
    }

    It 'is non-destructive and does not contain credentials' {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ''
        }

        $content | Should Not Match '(?im)\bgit\s+(reset|clean|checkout|commit|push)\b'
        $content | Should Not Match '(?im)\b(Remove-Item|del|erase|rm)\b'
        $content | Should Not Match '(?im)(password|secret|credential|access[_-]?token)\s*[:=]'
    }

    It 'requires named Docker capability skip handling and rejects arbitrary skips' {
        $content = if (Test-Path -LiteralPath $scriptPath) {
            Get-Content -Raw -LiteralPath $scriptPath
        } else {
            ''
        }

        $content | Should Match 'CAPABILITY_SKIP: Docker is unavailable'
        $content | Should Match 'PostgresSkillLifecycleProjectionStoreTest'
        $content | Should Match 'skipped'
        $content | Should Match 'arbitrary|unexpected|non-capability|not.*explicit'
    }

    It 'fails closed when lifecycle configuration semantics are mutated' {
        $validConfig = Join-Path $PSScriptRoot '..\apps\api\src\main\resources\application.yml'
        $migration = Join-Path $PSScriptRoot '..\apps\api\src\main\resources\db\migration\V2__create_skill_lifecycle_projection.sql'
        $mutations = @(
            @{ Name = 'persistence selector'; Text = (Get-Content -Raw $validConfig) -replace '(?m)(^ {2}persistence:\r?\n(?:^ {4}.*\r?\n)*?^ {4}backend:) json', '$1 postgresql' },
            @{ Name = 'projection fallback'; Text = (Get-Content -Raw $validConfig) -replace '(?m)^ {4}allow-json-fallback: false$', '    allow-json-fallback: true' },
            @{ Name = 'import mode'; Text = (Get-Content -Raw $validConfig) -replace '(?m)^ {4}import-mode: explicit-admin$', '    import-mode: online' },
            @{ Name = 'freshness bound'; Text = (Get-Content -Raw $validConfig) -replace '(?m)^ {4}max-source-age-seconds: 900$', '    max-source-age-seconds: 59' }
        )

        foreach ($mutation in $mutations) {
            $candidate = Join-Path $TestDrive "$($mutation.Name).yml"
            Set-Content -LiteralPath $candidate -Value $mutation.Text -Encoding UTF8
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
                -ConfigPathOverride $candidate -MigrationPathOverride $migration -ValidateConfigurationOnly
            $LASTEXITCODE | Should Not Be 0
        }
    }

    It 'exercises the named Docker skip policy behavior' {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ValidateSkipPolicyOnly -SkipSuiteName PostgresSkillLifecycleProjectionStoreTest `
            -SkippedCount 1 -SkipBody 'CAPABILITY_SKIP: Docker is unavailable'
        $LASTEXITCODE | Should Be 0

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ValidateSkipPolicyOnly -SkipSuiteName PersistenceBackendConfigurationTest `
            -SkippedCount 1 -SkipBody 'CAPABILITY_SKIP: Docker is unavailable'
        $LASTEXITCODE | Should Not Be 0

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ValidateSkipPolicyOnly -SkipSuiteName PostgresQualityEvidenceIntegrationTest `
            -SkippedCount 1 -SkipBody 'arbitrary skip'
        $LASTEXITCODE | Should Not Be 0
    }
}
