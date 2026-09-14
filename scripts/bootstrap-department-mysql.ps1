[CmdletBinding()]
param(
    [string]$MySqlHost = "127.0.0.1",
    [int]$MySqlPort = 3306,
    [string]$DatabaseName = "skillcenter",
    [string]$AppUser = "skillcenter",
    [string]$RootUser = "root",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot "deploy\local\.env"

function Require-Command([string]$name) {
    if ($null -eq (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "缺少 mysql.exe。请先安装 MySQL Server/Client 8.0+，或使用 -DryRun 查看初始化动作。"
    }
}

function Import-EnvFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), "Process")
        }
    }
}

function Require-Identifier([string]$value, [string]$name) {
    if ([string]::IsNullOrWhiteSpace($value) -or $value -notmatch '^[A-Za-z0-9_]+$') {
        throw "$name 只能包含 ASCII 字母、数字和下划线。"
    }
}

function Quote-Identifier([string]$value) {
    return ([char]0x60) + $value + ([char]0x60)
}

function Quote-String([string]$value) {
    $escaped = $value.Replace("\", "\\").Replace("'", "''")
    $escaped = $escaped.Replace([string][char]0, "\0").Replace([string][char]26, "\Z")
    return "'" + $escaped + "'"
}

function Read-SecretText([string]$environmentName, [string]$prompt) {
    $configured = [Environment]::GetEnvironmentVariable($environmentName, "Process")
    if (-not [string]::IsNullOrWhiteSpace($configured)) { return $configured }
    $secure = Read-Host -Prompt $prompt -AsSecureString
    return [System.Net.NetworkCredential]::new("", $secure).Password
}

function Invoke-MySql([string]$username, [string]$password, [string]$sql) {
    $previousPassword = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
    try {
        # MYSQL_PWD avoids putting either password in the mysql.exe command line.
        [Environment]::SetEnvironmentVariable("MYSQL_PWD", $password, "Process")
        $sql | & mysql.exe --protocol=TCP --host=$MySqlHost --port=$MySqlPort --user=$username --batch --skip-column-names 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL 操作失败，请确认账号、密码、端口和权限。"
        }
    } finally {
        if ($null -eq $previousPassword) {
            [Environment]::SetEnvironmentVariable("MYSQL_PWD", $null, "Process")
        } else {
            [Environment]::SetEnvironmentVariable("MYSQL_PWD", $previousPassword, "Process")
        }
    }
}

Import-EnvFile $envFile
Require-Identifier $DatabaseName "DatabaseName"
Require-Identifier $AppUser "AppUser"
Require-Identifier $RootUser "RootUser"

if ($DryRun) {
    Write-Host "[dry-run] 将使用 mysql.exe 连接 $MySqlHost`:$MySqlPort" -ForegroundColor Yellow
    Write-Host "[dry-run] 创建数据库 $DatabaseName、应用账号 $AppUser，并仅授予该数据库权限" -ForegroundColor Yellow
    Write-Host "[dry-run] 不会写入 .env，也不会安装、启动或删除任何服务" -ForegroundColor Yellow
    return
}

Require-Command "mysql.exe"
$appPassword = [Environment]::GetEnvironmentVariable("SKILL_CENTER_MYSQL_PASSWORD", "Process")
if ([string]::IsNullOrWhiteSpace($appPassword) -or $appPassword -eq "CHANGE-ME") {
    throw "请先在 deploy/local/.env 或进程环境中设置 SKILL_CENTER_MYSQL_PASSWORD。"
}
$rootPassword = Read-SecretText "SKILL_CENTER_MYSQL_ROOT_PASSWORD" "请输入 MySQL 管理员密码（不会写入仓库）"
if ([string]::IsNullOrWhiteSpace($rootPassword)) {
    throw "MySQL 管理员密码不能为空。"
}

$database = Quote-Identifier $DatabaseName
$user = Quote-String $AppUser
$escapedPassword = Quote-String $appPassword
$sql = @"
CREATE DATABASE IF NOT EXISTS $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS $user@'127.0.0.1' IDENTIFIED BY $escapedPassword;
ALTER USER $user@'127.0.0.1' IDENTIFIED BY $escapedPassword;
GRANT ALL PRIVILEGES ON $database.* TO $user@'127.0.0.1';
FLUSH PRIVILEGES;
"@

Invoke-MySql $RootUser $rootPassword $sql
Invoke-MySql $AppUser $appPassword "USE $database; SELECT 1;"
Write-Host "部门版 MySQL 已初始化：$DatabaseName / $AppUser@$MySqlHost" -ForegroundColor Green
