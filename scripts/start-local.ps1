[CmdletBinding()]
param(
    [ValidateSet("integration", "default")]
    [string]$Profile = "integration",
    [switch]$SkipDependencies,
    [switch]$SkipWeb,
    [switch]$SkipApi,
    [switch]$WithObservability,
    [switch]$WithMessageBus,
    [switch]$DryRun,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "deploy\local\compose.yaml"
$observabilityComposeFile = Join-Path $repoRoot "deploy\local\observability.compose.yaml"
$envFile = Join-Path $repoRoot "deploy\local\.env"
$integrationConfig = Join-Path $repoRoot "deploy\local\application-integration.yml"
$webRoot = Join-Path $repoRoot "apps\web"
$apiRoot = Join-Path $repoRoot "apps\api"
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) "skillcenter-local"
$apiPort = if ($Profile -eq "integration") { 8081 } else { 8080 }
$webPort = 5173

function Require-Command([string]$Command) {
    if ($null -eq (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$Command。请先运行 scripts/verify-environment.ps1。"
    }
}

function Invoke-Checked([string]$Command, [string[]]$Arguments, [string]$WorkingDirectory = $repoRoot) {
    Write-Host "> $Command $($Arguments -join ' ')" -ForegroundColor Cyan
    if ($DryRun) { return }
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "命令失败，退出码 $LASTEXITCODE：$Command"
        }
    } finally {
        Pop-Location
    }
}

function Import-LocalEnv([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
                $name = $Matches[1]
                $value = $Matches[2].Trim()
                if ($value.StartsWith('"') -and $value.EndsWith('"')) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($name, $value, "Process")
            }
        }
    }
    if (-not $env:SKILL_CENTER_POSTGRES_URL) {
        $database = if ($env:SKILL_CENTER_POSTGRES_DB) { $env:SKILL_CENTER_POSTGRES_DB } else { "skillcenter" }
        $env:SKILL_CENTER_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5432/$database"
    }
}

function Test-TcpPort([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        if (-not $task.Wait(1000)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Test-HttpReady([string]$Uri) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Wait-HttpReady([string]$Uri, [string]$Name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpReady $Uri) {
            Write-Host "$Name 已就绪：$Uri" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name 在 $TimeoutSeconds 秒内未就绪：$Uri"
}

function Invoke-SearchIndexProbe([int]$Port) {
    $headers = @{
        "X-User-Role" = "admin"
        "X-Request-Id" = "local-startup-search-probe"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Post `
            -Uri "http://127.0.0.1:$Port/api/v1/admin/search/index/probe" `
            -Headers $headers -TimeoutSec 10
        if ($response.StatusCode -ne 200) {
            throw "HTTP $($response.StatusCode)"
        }
        Write-Host "OpenSearch 管理探测通过，已刷新本地探测 TTL" -ForegroundColor Green
    } catch {
        throw "OpenSearch 管理探测失败：$($_.Exception.Message)。请确认 OpenSearch 已健康并查看 API 日志。"
    }
}

function Invoke-ArtifactStorageProbe([int]$Port) {
    $headers = @{
        "X-User-Role" = "admin"
        "X-Request-Id" = "local-startup-artifact-probe"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Post `
            -Uri "http://127.0.0.1:$Port/api/v1/admin/platform/artifact-storage/probe" `
            -Headers $headers -TimeoutSec 10
        if ($response.StatusCode -ne 200) {
            throw "HTTP $($response.StatusCode)"
        }
        Write-Host "MinIO 制品存储探测通过，已刷新本地连通性证据" -ForegroundColor Green
    } catch {
        throw "MinIO 制品存储探测失败：$($_.Exception.Message)。请确认 bucket 和本地凭据可用。"
    }
}

function Wait-TcpPort([int]$Port, [string]$Name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort $Port) {
            Write-Host "$Name 已就绪：127.0.0.1:$Port" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name 在 $TimeoutSeconds 秒内未就绪：127.0.0.1:$Port"
}

function Start-Background([string]$Command, [string[]]$Arguments, [string]$WorkingDirectory, [int]$Port, [string]$Name) {
    if (Test-TcpPort $Port) {
        Write-Host "$Name 已运行，复用现有进程：127.0.0.1:$Port" -ForegroundColor Green
        return
    }
    if ($DryRun) {
        Write-Host "[dry-run] 将启动 $Name" -ForegroundColor Yellow
        return
    }
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    $safeName = $Name.ToLowerInvariant().Replace(' ', '-')
    $stdout = Join-Path $logRoot "$safeName.out.log"
    $stderr = Join-Path $logRoot "$safeName.err.log"
    $process = Start-Process -FilePath $Command -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Write-Host "$Name 启动中，PID=$($process.Id)，日志：$stdout" -ForegroundColor Yellow
}

function Prepare-ObservabilitySecret {
    $observabilityRoot = Join-Path $logRoot "observability"
    $tokenFile = Join-Path $observabilityRoot "skill-center-metrics-token"
    $env:SKILL_CENTER_METRICS_TOKEN_FILE = $tokenFile
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force -Path $observabilityRoot | Out-Null
        [System.IO.File]::WriteAllText($tokenFile, "$env:SKILL_CENTER_METRICS_TOKEN`n",
            [System.Text.UTF8Encoding]::new($false))
    }
}

function Test-MetricsReady([int]$Port) {
    try {
        $headers = @{ Authorization = "Bearer $env:SKILL_CENTER_METRICS_TOKEN" }
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/internal/metrics" `
            -Headers $headers -TimeoutSec 5
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

if ($env:SKILL_CENTER_LOCAL_ONLY -and $env:SKILL_CENTER_LOCAL_ONLY -ne "true") {
    throw "SKILL_CENTER_LOCAL_ONLY 必须为 true；该入口只允许启动本地联调环境。"
}
$env:SKILL_CENTER_LOCAL_ONLY = "true"

if ($WithObservability -and $Profile -ne "integration") {
    throw "-WithObservability 需要 -Profile integration，以便 Prometheus 抓取共享 Redis 指标实例。"
}

Require-Command "docker"
Require-Command "npm.cmd"
Require-Command "mvn.cmd"
if (-not (Test-Path -LiteralPath $composeFile)) { throw "找不到本地依赖编排：$composeFile" }
if ($Profile -eq "integration" -and -not (Test-Path -LiteralPath $integrationConfig)) {
    throw "找不到本地 integration 配置：$integrationConfig"
}

Import-LocalEnv $envFile

if ($Profile -eq "integration") {
    # These selectors also exist in .env for the safe default profile. Set them
    # explicitly here because process environment has higher precedence than YAML.
    $env:SKILL_CENTER_SEARCH_INDEX_BACKEND = "opensearch"
    $env:SKILL_CENTER_SEARCH_INDEX_EVENTS_ENABLED = "true"
    $env:SKILL_CENTER_SEARCH_INDEX_EVENTS_BUS_TRANSPORT = if ($WithMessageBus) { "redis" } else { "disabled" }
    $env:SKILL_CENTER_PACKAGE_UPLOAD_BACKEND = "distributed"
    if (-not $env:SKILL_CENTER_METRICS_TOKEN) {
        $env:SKILL_CENTER_METRICS_TOKEN = "local-metrics-token"
    }
}

if ($WithObservability) {
    if (-not (Test-Path -LiteralPath $observabilityComposeFile)) {
        throw "找不到本地观测编排：$observabilityComposeFile"
    }
    Prepare-ObservabilitySecret
}

if (-not $SkipDependencies) {
    Invoke-Checked "docker" @("compose", "--env-file", $envFile, "-f", $composeFile, "up", "-d")
    if (-not $DryRun) {
        Wait-TcpPort 5432 "PostgreSQL"
        Wait-TcpPort 6379 "Redis"
        Wait-TcpPort 9000 "MinIO"
        Wait-TcpPort 9200 "OpenSearch"
    }
}

if (-not $SkipApi) {
    if ($Profile -eq "integration") {
        $configUri = "file:///" + ($integrationConfig -replace '\\', '/')
        # Use the environment binding instead of an ArgumentList item with spaces;
        # Start-Process cannot preserve nested quoting for Maven on Windows.
        $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $configUri
        Start-Background "mvn.cmd" @("-q", "spring-boot:run") $apiRoot $apiPort "SkillCenter API ($Profile)"
    } else {
        [Environment]::SetEnvironmentVariable("SPRING_CONFIG_ADDITIONAL_LOCATION", $null, "Process")
        Start-Background "mvn.cmd" @("-q", "spring-boot:run") $apiRoot $apiPort "SkillCenter API ($Profile)"
    }
        if (-not $DryRun) {
            Wait-TcpPort $apiPort "SkillCenter API ($Profile)"
            if ($Profile -eq "integration") {
                Invoke-SearchIndexProbe $apiPort
                Invoke-ArtifactStorageProbe $apiPort
            }
            if ($WithObservability -and -not (Test-MetricsReady $apiPort)) {
                throw "API 指标端点未启用或 Token 不匹配；请重启 integration API 后再启用观测栈。"
            }
            if (-not (Test-HttpReady "http://127.0.0.1:$apiPort/api/v1/skills")) {
            throw "SkillCenter API 端口已打开，但技能目录接口未返回 HTTP 200；请查看 $logRoot"
        }
    }
}

if (-not $SkipWeb) {
    Start-Background "npm.cmd" @("run", "dev", "--", "--host", "0.0.0.0") $webRoot $webPort "SkillCenter Web"
    if (-not $DryRun) {
        Wait-TcpPort $webPort "SkillCenter Web"
        if (-not (Test-HttpReady "http://127.0.0.1:$webPort/")) {
            throw "SkillCenter Web 端口已打开，但首页未返回 HTTP 200；请查看 $logRoot"
        }
    }
}

if ($WithObservability) {
    Invoke-Checked "docker" @("compose", "--env-file", $envFile, "-f", $observabilityComposeFile, "up", "-d")
    if (-not $DryRun) {
        Wait-HttpReady "http://127.0.0.1:9090/-/ready" "Prometheus"
        Wait-HttpReady "http://127.0.0.1:9093/-/ready" "Alertmanager"
        Wait-HttpReady "http://127.0.0.1:3000/api/health" "Grafana"
    }
}

if (-not $DryRun) {
    Write-Host "本地 $Profile 联调环境已启动：Web=http://127.0.0.1:$webPort，API=http://127.0.0.1:$apiPort" -ForegroundColor Green
    Write-Host "生产 Readiness 仍需外部 SSO、扫描、监控、备份和验收证据，不会因本地容器启动而自动变为 READY。" -ForegroundColor Yellow
}
