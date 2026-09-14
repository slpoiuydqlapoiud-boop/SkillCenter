[CmdletBinding()]
param(
    [switch]$Json,
    [switch]$FailOnMissing
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot "deploy\local\.env"
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), "Process")
        }
    }
}

function Check([string]$id, [string]$name, [string]$kind, [bool]$ready, [string]$detail, [string]$hint) {
    [pscustomobject]@{ Id = $id; Name = $name; Kind = $kind; Status = if ($ready) { "READY" } else { "MISSING" }; Detail = $detail; InstallHint = $hint }
}

function PortReady([int]$port) {
    try { return Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue } catch { return $false }
}

function HttpReady([string]$uri) {
    try { return (Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5).StatusCode -eq 200 } catch { return $false }
}

function ApiReady {
    try {
        $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/auth/guest" -Body "{}" -ContentType "application/json" -TimeoutSec 5
        if (-not $login.data.token) { return $false }
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8080/api/v1/skills" -Headers @{ Authorization = "Bearer $($login.data.token)" } -TimeoutSec 5
        return $response.StatusCode -eq 200
    } catch { return $false }
}

$checks = @(
    (Check "java" "JDK 21" "runtime" ($null -ne (Get-Command java -ErrorAction SilentlyContinue)) "java command" "Install JDK 21"),
    (Check "maven" "Maven 3.9+" "runtime" ($null -ne (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) "mvn.cmd command" "Install Maven 3.9+"),
    (Check "node" "Node.js 20+" "runtime" ($null -ne (Get-Command node -ErrorAction SilentlyContinue)) "node command" "Install Node.js 20+"),
    (Check "local-auth" "Local admin password hash" "configuration" ($env:SKILL_CENTER_LOCAL_ADMIN_PASSWORD_HASH -match '^pbkdf2-sha256\$[0-9]+\$[^\$]+\$[^\$]+$') "PBKDF2 hash in deploy/local/.env" "Run scripts/hash-local-password.ps1 and set SKILL_CENTER_LOCAL_ADMIN_PASSWORD_HASH"),
    (Check "mysql" "MySQL 8.0+" "database" (PortReady 3306) "127.0.0.1:3306" "Start MySQL and create the skillcenter database"),
    (Check "api" "SkillCenter API + local auth" "application" (ApiReady) "http://127.0.0.1:8080 + /api/v1/auth/guest" "Run scripts/start-department-local.ps1"),
    (Check "web" "SkillCenter Web" "application" (HttpReady "http://127.0.0.1:5173/") "http://127.0.0.1:5173" "Run scripts/start-department-local.ps1")
)

$missing = @($checks | Where-Object Status -ne "READY")
if ($Json) {
    $checks | ConvertTo-Json -Depth 3
} else {
    $checks | Format-Table Id, Status, Kind, Detail -AutoSize | Out-Host
    Write-Host (if ($missing.Count -eq 0) { "Department local status: READY" } else { "Department local status: $($missing.Count) check(s) require attention" })
}
if ($FailOnMissing -and $missing.Count -gt 0) { exit 1 }
exit 0
