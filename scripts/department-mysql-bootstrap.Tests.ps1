$repoRoot = Split-Path -Parent $PSScriptRoot

Describe "department MySQL bootstrap contract" {
    It "is a PowerShell-only helper that uses mysql.exe" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\bootstrap-department-mysql.ps1")

        $script | Should Match "mysql\.exe"
        $script | Should Match "127\.0\.0\.1"
        $script | Should Match "3306"
        $script | Should Not Match "(?i)docker|docker compose|kubectl|helm|postgres|redis|opensearch|minio"
    }

    It "keeps credentials out of command-line arguments and grants only the application schema" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\bootstrap-department-mysql.ps1")

        $script | Should Match "MYSQL_PWD"
        $script | Should Not Match "--password=|ArgumentList.*password"
        $script | Should Match "GRANT ALL PRIVILEGES ON"
        $script | Should Match "CREATE DATABASE IF NOT EXISTS"
    }

    It "supports a dry run and does not write the repository env file" {
        $script = Get-Content -Raw (Join-Path $repoRoot "scripts\bootstrap-department-mysql.ps1")

        $script | Should Match "DryRun"
        $script | Should Not Match "Set-Content.*\.env|Out-File.*\.env|Add-Content.*\.env"
    }
}
