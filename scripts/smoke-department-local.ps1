[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$SkillId = "",
    [switch]$IncludeEvaluation,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot "deploy\local\.env"

function Import-EnvFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), "Process")
        }
    }
}

function Invoke-ApiJson([string]$method, [string]$path, [string]$token, [object]$body) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($token)) {
        $headers["Authorization"] = "Bearer $token"
    }
    $uri = "$script:apiBaseUrl$path"
    try {
        if ($null -eq $body) {
            return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -TimeoutSec 10
        }
        $json = $body | ConvertTo-Json -Depth 10 -Compress
        return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body $json `
            -ContentType "application/json" -TimeoutSec 10
    } catch {
        throw "部门 smoke 请求失败：$method $path"
    }
}

function Require-ResponseData([object]$response, [string]$operation) {
    if ($null -eq $response -or $null -eq $response.data) {
        throw "部门 smoke 未返回有效数据：$operation"
    }
    return $response.data
}

function Get-AccessToken([string]$path, [object]$body, [string]$operation) {
    $response = Invoke-ApiJson "POST" $path "" $body
    $data = Require-ResponseData $response $operation
    if ([string]::IsNullOrWhiteSpace([string]$data.token)) {
        throw "部门 smoke 未取得会话令牌：$operation"
    }
    return [string]$data.token
}

function Read-LocalAdminPassword {
    $configured = [Environment]::GetEnvironmentVariable("SKILL_CENTER_LOCAL_ADMIN_PASSWORD", "Process")
    if (-not [string]::IsNullOrWhiteSpace($configured)) { return $configured }
    $secure = Read-Host -Prompt "请输入本地管理员密码（仅用于 smoke 登录，不会写入仓库）" -AsSecureString
    return [System.Net.NetworkCredential]::new("", $secure).Password
}

Import-EnvFile $envFile
$script:apiBaseUrl = $BaseUrl.TrimEnd('/')
if ($TimeoutSeconds -lt 5 -or $TimeoutSeconds -gt 600) {
    throw "TimeoutSeconds 必须在 5 到 600 之间。"
}

$guestToken = Get-AccessToken "/api/v1/auth/guest" @{} "guest 登录"
$listResponse = Invoke-ApiJson "GET" "/api/v1/skills?page=1&pageSize=100" $guestToken $null
$page = Require-ResponseData $listResponse "Skill 目录"
$items = @($page.items)
if ($items.Count -eq 0) { throw "部门 smoke 找不到可验证的 Skill。" }

$selected = if ([string]::IsNullOrWhiteSpace($SkillId)) {
    $items[0]
} else {
    $encodedSkillId = [uri]::EscapeDataString($SkillId)
    $detailResponse = Invoke-ApiJson "GET" "/api/v1/skills/$encodedSkillId" $guestToken $null
    Require-ResponseData $detailResponse "Skill 详情"
}
$selectedId = [string]$selected.id
$selectedVersion = [string]$selected.version
if ([string]::IsNullOrWhiteSpace($selectedId) -or [string]::IsNullOrWhiteSpace($selectedVersion)) {
    throw "部门 smoke 选择的 Skill 缺少版本身份。"
}

$encodedSelectedId = [uri]::EscapeDataString($selectedId)
$detail = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/skills/$encodedSelectedId" $guestToken $null) "Skill 详情"
$content = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/skills/$encodedSelectedId/content" $guestToken $null) "Skill 内容"
$quality = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/skills/$encodedSelectedId/quality?version=$([uri]::EscapeDataString($selectedVersion))" $guestToken $null) "Skill 质量视图"
if ($null -eq $detail -or $null -eq $content -or $null -eq $quality) {
    throw "部门 smoke 的 Skill 读取链路返回空数据。"
}
Write-Host "部门 Skill 读取 smoke 通过：$selectedId@$selectedVersion（目录/详情/内容/质量）" -ForegroundColor Green

if ($IncludeEvaluation) {
    $adminUser = [Environment]::GetEnvironmentVariable("SKILL_CENTER_LOCAL_ADMIN_USERNAME", "Process")
    if ([string]::IsNullOrWhiteSpace($adminUser)) { $adminUser = "admin" }
    $adminPassword = Read-LocalAdminPassword
    if ([string]::IsNullOrWhiteSpace($adminPassword)) { throw "本地管理员密码不能为空。" }
    $adminToken = Get-AccessToken "/api/v1/auth/login" @{
        username = $adminUser
        password = $adminPassword
    } "管理员登录"

    $evaluation = Require-ResponseData (Invoke-ApiJson "POST" "/api/v1/admin/quality/evaluations" $adminToken @{
        skillId = $selectedId
        skillVersion = $selectedVersion
        suiteId = "smoke"
        suiteVersion = "smoke-v1"
        scenario = "success"
        timeoutMs = 10000
        runtimeId = ""
        mcpServerId = ""
        llmProviderId = ""
        experimentId = ""
    }) "提交 smoke 评测"
    $runId = [string]$evaluation.id
    if ([string]::IsNullOrWhiteSpace($runId)) { throw "部门 smoke 评测未返回 runId。" }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $status = ""
    while ((Get-Date) -lt $deadline) {
        $run = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/admin/quality/evaluations/$([uri]::EscapeDataString($runId))" $adminToken $null) "读取 smoke 评测"
        $status = [string]$run.status
        if ($status -in @("COMPLETED", "FAILED", "CANCELLED")) { break }
        Start-Sleep -Seconds 2
    }
    if ($status -ne "COMPLETED") { throw "部门 smoke 评测未成功完成：$status" }

    $snapshot = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/admin/quality/evaluations/$([uri]::EscapeDataString($runId))/snapshot" $adminToken $null) "读取质量证据快照"
    $reloaded = Require-ResponseData (Invoke-ApiJson "GET" "/api/v1/admin/quality/evaluations/$([uri]::EscapeDataString($runId))" $adminToken $null) "验证质量证据持久化"
    if ([string]$reloaded.id -ne $runId -or $null -eq $snapshot) {
        throw "部门 smoke 质量证据持久化校验失败。"
    }
    Write-Host "部门质量证据 smoke 通过：runId=$runId，状态=$status；评测写入和重读成功" -ForegroundColor Green
}

Write-Host "部门版功能 smoke 完成：只读链路已验证；IncludeEvaluation=$IncludeEvaluation" -ForegroundColor Green
