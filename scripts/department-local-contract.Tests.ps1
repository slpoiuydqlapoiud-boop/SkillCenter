$repoRoot = Split-Path -Parent $PSScriptRoot

Describe "department local Windows contract" {
    It "defines MySQL as the only local database" {
        $envExample = Get-Content -Raw (Join-Path $repoRoot "deploy\local\.env.example")
        $config = Get-Content -Raw (Join-Path $repoRoot "deploy\local\application-department.yml")

        $envExample | Should Match "SKILL_CENTER_MYSQL_URL"
        $config | Should Match "jdbc:mysql://127\.0\.0\.1:3306/skillcenter"
        $config | Should Match "persistence:\s*\r?\n\s*backend: mysql"
        $envExample | Should Not Match "SKILL_CENTER_POSTGRES|SKILL_CENTER_REDIS|SKILL_CENTER_OPENSEARCH|SKILL_CENTER_MINIO"
    }

    It "starts the department profile without Docker or cloud services" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\start-department-local.ps1")

        $script | Should Match "mysql"
        $script | Should Match "mvn\.cmd"
        $script | Should Match "npm\.cmd"
        $script | Should Not Match "(?i)docker|docker compose|kubectl|helm|postgres|redis|opensearch|minio"
    }

    It "verifies MySQL and the local application endpoints" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\verify-department-local.ps1")

        $script | Should Match "3306"
        $script | Should Match "127\.0\.0\.1:8080"
        $script | Should Match "127\.0\.0\.1:5173"
        $script | Should Not Match "(?i)docker|kubectl|helm|postgres|redis|opensearch|minio"
    }

    It "supports a dry run before MySQL is started" {
        { & (Join-Path $repoRoot "scripts\start-department-local.ps1") -DryRun -SkipApi -SkipWeb } | Should Not Throw
    }

    It "does not reuse an occupied port for a non-department API" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\start-department-local.ps1")

        $script | Should Match "Test-DepartmentApiReady"
        $script | Should Match "8080.*部门版 API|部门版 API.*8080"
        $script | Should Match "不是.*部门版|非.*部门版"
    }
}
