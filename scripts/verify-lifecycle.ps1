param(
    [switch]$SkipBuild,
    [switch]$SkipSmoke,
    [switch]$CheckIntegration,
    [switch]$CheckProductionConfig,
    [switch]$CheckProductionHandoff,
    [string]$ProductionHandoffBaseUrl = "http://127.0.0.1:8081"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repoRoot "apps\web"
$apiRoot = Join-Path $repoRoot "apps\api"

function Invoke-Checked([string]$Command, [string[]]$Arguments, [string]$WorkingDirectory) {
    Write-Host "`n> $Command $($Arguments -join ' ')" -ForegroundColor Cyan
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

function Assert-IntegrationReadiness([string]$BaseUrl = "http://127.0.0.1:8081") {
    $headers = @{
        "X-User-Role" = "admin"
        "X-Request-Id" = "lifecycle-integration-readiness"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing `
            -Uri "$BaseUrl/api/v1/admin/platform/readiness" -Headers $headers -TimeoutSec 10
        $body = $response.Content | ConvertFrom-Json
    } catch {
        throw "集成 profile readiness 检查失败：无法读取本地 integration API。"
    }

    $requiredComponents = @(
        "PERSISTENCE_CONTROL_PLANE",
        "SKILL_ASSET_STORE",
        "BENCHMARK_STORE",
        "OPTIMIZATION_EXPERIMENT_STORE",
        "OPTIMIZATION_WORK_ITEM_STORE",
        "RUNTIME_SUMMARY_STORE",
        "OPERATIONS_METRICS_STORE",
        "OPERATIONS_ALERT_STATE",
        "RESUMABLE_UPLOAD_STORE",
        "SKILL_SEARCH_INDEX",
        "ARTIFACT_STORAGE"
    )
    $components = @($body.data.components)
    $failed = foreach ($componentId in $requiredComponents) {
        $component = $components | Where-Object { $_.componentId -eq $componentId } | Select-Object -First 1
        if ($null -eq $component) {
            [pscustomobject]@{ ComponentId = $componentId; Status = "MISSING"; ReasonCode = "COMPONENT_NOT_REPORTED" }
        } elseif ($component.status -ne "READY") {
            [pscustomobject]@{ ComponentId = $componentId; Status = $component.status; ReasonCode = $component.reasonCode }
        }
    }
    if (@($failed).Count -gt 0) {
        $summary = (@($failed) | ForEach-Object { "$($_.ComponentId)=$($_.Status)/$($_.ReasonCode)" }) -join ", "
        throw "集成 profile 共享后端未就绪：$summary"
    }

    try {
        $skills = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/api/v1/skills" -TimeoutSec 10
        if ($skills.StatusCode -ne 200) { throw "HTTP $($skills.StatusCode)" }
    } catch {
        throw "集成 profile Skill API 冒烟失败。"
    }
    Write-Host "集成 profile 共享后端 readiness 通过；平台整体状态保留为 $($body.data.overall)。" -ForegroundColor Green
}

function Assert-ProductionHandoff([string]$BaseUrl) {
    $handoffScript = Join-Path $PSScriptRoot "verify-production-handoff.ps1"
    if (-not (Test-Path -LiteralPath $handoffScript)) {
        throw "生产交付验收脚本不存在。"
    }

    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $handoffScript `
        -BaseUrl $BaseUrl -Json -FailOnNotReady
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }

    if ($exitCode -eq 2) {
        Write-Error -ErrorAction Continue "生产交付验收未就绪；请查看 verify-production-handoff.ps1 的安全摘要。"
        exit 2
    }
    if ($exitCode -ne 0) {
        Write-Error -ErrorAction Continue "生产交付验收执行失败。"
        exit 1
    }

    try {
        $summary = ($output -join [Environment]::NewLine) | ConvertFrom-Json
        Write-Host "生产交付验收通过：Readiness=$($summary.readiness.overall)，证据=$($summary.evidence.accepted)/$($summary.evidence.required)" -ForegroundColor Green
    } catch {
        throw "生产交付验收返回了无效摘要。"
    }
}

function Assert-ProductionConfig {
    $configScript = Join-Path $PSScriptRoot "verify-production-config.ps1"
    if (-not (Test-Path -LiteralPath $configScript)) {
        throw "生产配置前置检查脚本不存在。"
    }

    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $configScript -Json -FailOnNotReady
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }

    if ($exitCode -eq 2) {
        Write-Error -ErrorAction Continue "生产配置前置检查未就绪；请查看 verify-production-config.ps1 的安全摘要。"
        exit 2
    }
    if ($exitCode -ne 0) {
        Write-Error -ErrorAction Continue "生产配置前置检查执行失败。"
        exit 1
    }

    try {
        $summary = ($output -join [Environment]::NewLine) | ConvertFrom-Json
        Write-Host "生产配置前置检查通过：$($summary.ready)/$($summary.checkCount) 项就绪" -ForegroundColor Green
    } catch {
        throw "生产配置前置检查返回了无效摘要。"
    }
}

Invoke-Checked "npm.cmd" @("test") $webRoot
if (-not $SkipBuild) {
    Invoke-Checked "npm.cmd" @("run", "build") $webRoot
}
Invoke-Checked "mvn.cmd" @("-q", "-DforkCount=0", "test") $apiRoot

if ($CheckIntegration) {
    Assert-IntegrationReadiness
}

if ($CheckProductionConfig) {
    Assert-ProductionConfig
}

if ($CheckProductionHandoff) {
    Assert-ProductionHandoff $ProductionHandoffBaseUrl
}

if (-not $SkipSmoke) {
    $web = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:5173/"
    if ($web.StatusCode -ne 200) { throw "前端冒烟失败：HTTP $($web.StatusCode)" }
    $readiness = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8080/api/v1/admin/quality/provider-readiness" -Headers @{ "X-User-Role" = "admin" }
    if ($readiness.StatusCode -ne 200) { throw "Provider readiness 冒烟失败：HTTP $($readiness.StatusCode)" }
    Write-Host "`n生命周期平台冒烟通过：Web=$($web.StatusCode)，Provider readiness=$($readiness.StatusCode)" -ForegroundColor Green
}

Write-Host "生命周期平台回归验证完成。" -ForegroundColor Green
