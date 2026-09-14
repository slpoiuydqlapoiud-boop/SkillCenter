[CmdletBinding()]
param(
    [switch]$SkipApi,
    [switch]$SkipWeb,
    [switch]$DryRun,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$apiRoot = Join-Path $repoRoot "apps\api"
$webRoot = Join-Path $repoRoot "apps\web"
$configFile = Join-Path $repoRoot "deploy\local\application-department.yml"
$envFile = Join-Path $repoRoot "deploy\local\.env"
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) "skillcenter-department-local"

function Require-Command([string]$name) {
    if ($null -eq (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$name。请先安装 Java 21、Maven 3.9+ 和 Node.js 20+。"
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

function Test-Port([int]$port) {
    try {
        return Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
    } catch { return $false }
}

function Wait-Http([string]$uri, [string]$name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 3).StatusCode -eq 200) {
                Write-Host "$name 已就绪：$uri" -ForegroundColor Green
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "$name 在 $TimeoutSeconds 秒内未就绪：$uri"
}

function Wait-ApiReady {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-DepartmentApiReady) {
            Write-Host "SkillCenter API 已就绪并通过基础登录：http://127.0.0.1:8080" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "SkillCenter API 在 $TimeoutSeconds 秒内未通过登录和基础 API 检查"
}

function Test-DepartmentApiReady {
    try {
        $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/auth/guest" -Body "{}" -ContentType "application/json" -TimeoutSec 3
        $token = $login.data.token
        if (-not $token) { return $false }
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8080/api/v1/skills" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Start-Background([string]$command, [string[]]$arguments, [string]$workingDirectory, [string]$name) {
    if ($DryRun) {
        Write-Host "[dry-run] $command $($arguments -join ' ')" -ForegroundColor Yellow
        return
    }
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    $safe = $name.ToLowerInvariant().Replace(' ', '-')
    $process = Start-Process -FilePath $command -ArgumentList $arguments -WorkingDirectory $workingDirectory `
        -RedirectStandardOutput (Join-Path $logRoot "$safe.out.log") `
        -RedirectStandardError (Join-Path $logRoot "$safe.err.log") -WindowStyle Hidden -PassThru
    Write-Host "$name 启动中，PID=$($process.Id)，日志目录：$logRoot" -ForegroundColor Yellow
}

Require-Command "java"
Require-Command "mvn.cmd"
Require-Command "node"
Require-Command "npm.cmd"
Import-EnvFile $envFile

if (-not $DryRun -and [string]::IsNullOrWhiteSpace($env:SKILL_CENTER_LOCAL_ADMIN_PASSWORD_HASH)) {
    throw "缺少本地管理员密码哈希：请在 deploy/local/.env 中设置 SKILL_CENTER_LOCAL_ADMIN_PASSWORD_HASH（可用 scripts/hash-local-password.ps1 生成）。"
}
if (-not $DryRun -and -not (Test-Port 3306)) {
    throw "MySQL 未就绪：127.0.0.1:3306。请启动 MySQL 8.0+ 并创建 skillcenter 数据库。"
}
if (-not (Test-Path -LiteralPath $configFile)) { throw "找不到部门配置：$configFile" }

$configUri = "file:///" + ($configFile -replace '\\', '/')
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = $configUri

if (-not $SkipApi) {
    if (-not (Test-Port 8080)) {
        Start-Background "mvn.cmd" @("-q", "spring-boot:run") $apiRoot "SkillCenter API"
    } elseif (Test-DepartmentApiReady) {
        Write-Host "SkillCenter API 已运行，复用 127.0.0.1:8080" -ForegroundColor Green
    } else {
        throw "127.0.0.1:8080 已被占用，但响应不是部门版 API；请停止占用该端口的旧进程后重试。"
    }
    if (-not $DryRun) { Wait-ApiReady }
}

if (-not $SkipWeb) {
    if (-not (Test-Port 5173)) {
        Start-Background "npm.cmd" @("run", "dev", "--", "--host", "0.0.0.0") $webRoot "SkillCenter Web"
    } else {
        Write-Host "SkillCenter Web 已运行，复用 127.0.0.1:5173" -ForegroundColor Green
    }
    if (-not $DryRun) { Wait-Http "http://127.0.0.1:5173/" "SkillCenter Web" }
}

if (-not $DryRun) {
    Write-Host "部门级本地环境已启动：Web=http://127.0.0.1:5173，API=http://127.0.0.1:8080" -ForegroundColor Green
}
