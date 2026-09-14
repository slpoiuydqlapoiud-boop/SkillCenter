$repoRoot = Split-Path -Parent $PSScriptRoot

Describe "department local functional smoke contract" {
    It "uses local authenticated Skill lifecycle and quality endpoints" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\smoke-department-local.ps1")

        $script | Should Match "auth/guest"
        $script | Should Match "api/v1/skills"
        $script | Should Match "content"
        $script | Should Match "quality"
        $script | Should Match "IncludeEvaluation"
        $script | Should Not Match "(?i)docker|docker compose|kubectl|helm|postgres|redis|opensearch|minio"
    }

    It "keeps evaluation writes explicit and validates persisted terminal state" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\smoke-department-local.ps1")

        $script | Should Match "POST"
        $script | Should Match "admin/quality/evaluations"
        $script | Should Match "while"
        $script | Should Match "COMPLETED|FAILED"
        $script | Should Match "Read-Host.*AsSecureString"
    }

    It "does not print raw HTTP response bodies on failure" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\smoke-department-local.ps1")

        $script | Should Not Match "Write-Host.*response|Write-Output.*response"
    }
}
