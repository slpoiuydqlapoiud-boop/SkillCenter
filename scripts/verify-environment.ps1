param(
    [switch]$CheckServices,
    [switch]$CheckObservability,
    [switch]$Json,
    [switch]$FailOnMissing
)

$ErrorActionPreference = "Stop"

function New-Check {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Category,
        [ValidateSet("development", "contract-tests", "integration")]
        [string]$Requirement,
        [ValidateSet("READY", "MISSING", "UNAVAILABLE")]
        [string]$Status,
        [string]$Detected,
        [string]$Detail,
        [string]$InstallHint
    )

    [pscustomobject]@{
        Id = $Id
        Name = $Name
        Category = $Category
        Requirement = $Requirement
        Status = $Status
        Detected = $Detected
        Detail = $Detail
        InstallHint = $InstallHint
    }
}

function Get-CommandCheck {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Command,
        [string[]]$Arguments,
        [string]$Category,
        [string]$Requirement,
        [string]$InstallHint
    )

    $commandInfo = Get-Command $Command -ErrorAction SilentlyContinue
    if ($null -eq $commandInfo) {
        return New-Check $Id $Name $Category $Requirement "MISSING" "" "$Command is not available on PATH" $InstallHint
    }

    try {
        # java -version writes its version to stderr. Keep stderr in the
        # captured diagnostic output, but do not let PowerShell's Stop policy
        # turn that valid version report into an invocation exception.
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = @(& $commandInfo.Source @Arguments 2>&1 | Out-String).Trim()
            $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $firstLine = (($output -split "`r?`n") | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1).Trim()
        if ($exitCode -ne 0) {
            return New-Check $Id $Name $Category $Requirement "UNAVAILABLE" $firstLine "$Command was found, but the command failed with exit code $exitCode" $InstallHint
        }
        return New-Check $Id $Name $Category $Requirement "READY" $firstLine "$Command is executable" $InstallHint
    } catch {
        return New-Check $Id $Name $Category $Requirement "UNAVAILABLE" "" "$Command could not be executed: $($_.Exception.Message)" $InstallHint
    }
}

function Get-PythonCheck {
    $candidates = @(
        @{ Command = "py"; Arguments = @("-3.11", "--version") },
        @{ Command = "python"; Arguments = @("--version") },
        @{ Command = "python3"; Arguments = @("--version") }
    )

    foreach ($candidate in $candidates) {
        $commandInfo = Get-Command $candidate.Command -ErrorAction SilentlyContinue
        if ($null -eq $commandInfo) { continue }

        try {
            $output = @(& $commandInfo.Source @($candidate.Arguments) 2>&1 | Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and $output -match "Python 3\.(1[1-9]|[2-9][0-9])") {
                return New-Check "python" "Python 3.11+" "Contract test runtime" "contract-tests" "READY" $output "A supported Python interpreter is executable" "Install Python 3.11+ and add the launcher to PATH"
            }
        } catch {
            continue
        }
    }

    New-Check "python" "Python 3.11+" "Contract test runtime" "contract-tests" "MISSING" "" "Python 3.11+ was not found" "Install Python 3.11+ and add the launcher to PATH"
}

function Get-PythonJsonSchemaCheck {
    $candidates = @(
        @{ Command = "py"; Arguments = @("-3.11", "-c", "import importlib.metadata as m; print(m.version('jsonschema'))") },
        @{ Command = "python"; Arguments = @("-c", "import importlib.metadata as m; print(m.version('jsonschema'))") },
        @{ Command = "python3"; Arguments = @("-c", "import importlib.metadata as m; print(m.version('jsonschema'))") }
    )

    foreach ($candidate in $candidates) {
        $commandInfo = Get-Command $candidate.Command -ErrorAction SilentlyContinue
        if ($null -eq $commandInfo) { continue }

        try {
            $output = @(& $commandInfo.Source @($candidate.Arguments) 2>&1 | Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and $output -match "\d+\.\d+") {
                return New-Check "python-jsonschema" "Python jsonschema" "Contract test dependency" "contract-tests" "READY" $output "jsonschema can be imported" "Run: py -3.11 -m pip install -r requirements-dev.txt"
            }
        } catch {
            continue
        }
    }

    New-Check "python-jsonschema" "Python jsonschema" "Contract test dependency" "contract-tests" "MISSING" "" "Python jsonschema is not importable" "Run: py -3.11 -m pip install -r requirements-dev.txt"
}

function Get-PortCheck {
    param(
        [string]$Id,
        [string]$Name,
        [int]$Port,
        [string]$InstallHint
    )

    try {
        $reachable = Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($reachable) {
            return New-Check $Id $Name "Local service" "integration" "READY" "127.0.0.1:$Port" "TCP port is reachable" $InstallHint
        }
        return New-Check $Id $Name "Local service" "integration" "UNAVAILABLE" "127.0.0.1:$Port" "TCP port is not reachable; start the local dependency stack" $InstallHint
    } catch {
        New-Check $Id $Name "Local service" "integration" "UNAVAILABLE" "127.0.0.1:$Port" "Port check failed: $($_.Exception.Message)" $InstallHint
    }
}

$checks = @(
    (Get-CommandCheck "jdk" "JDK 21" "java" @("-version") "Java toolchain" "development" "Install JDK 21 and set JAVA_HOME"),
    (Get-CommandCheck "maven" "Maven 3.9+" "mvn" @("-version") "Java build tool" "development" "Install Maven 3.9+ and add mvn to PATH"),
    (Get-CommandCheck "node" "Node.js 20+" "node" @("--version") "Web toolchain" "development" "Install Node.js 20 LTS or newer"),
    (Get-CommandCheck "npm" "npm" "npm" @("--version") "Web package manager" "development" "Install npm with Node.js"),
    (Get-PythonCheck),
    (Get-PythonJsonSchemaCheck),
    (Get-CommandCheck "docker-engine" "Docker Engine" "docker" @("version") "Container runtime" "integration" "Install Docker Desktop and start the Docker Engine"),
    (Get-CommandCheck "docker-compose" "Docker Compose v2" "docker" @("compose", "version") "Container orchestration" "integration" "Install Docker Desktop with Compose v2")
)

if ($CheckServices) {
    $checks += Get-PortCheck "postgres" "PostgreSQL" 5432 "Start PostgreSQL 16+ via deploy/local/compose.yaml"
    $checks += Get-PortCheck "redis" "Redis" 6379 "Start Redis 7+ via deploy/local/compose.yaml"
    $checks += Get-PortCheck "minio" "MinIO / S3-compatible storage" 9000 "Start MinIO via deploy/local/compose.yaml"
    $checks += Get-PortCheck "opensearch" "OpenSearch" 9200 "Start OpenSearch 2.17.1 via deploy/local/compose.yaml"
}

if ($CheckObservability) {
    $checks += Get-PortCheck "prometheus" "Prometheus" 9090 "Start the local observability stack with scripts/start-local.ps1 -WithObservability"
    $checks += Get-PortCheck "grafana" "Grafana" 3000 "Start the local observability stack with scripts/start-local.ps1 -WithObservability"
    $checks += Get-PortCheck "alertmanager" "Alertmanager" 9093 "Start the local observability stack with scripts/start-local.ps1 -WithObservability"
}

$checks = @($checks)
$missing = @($checks | Where-Object { $_.Status -ne "READY" })

if ($Json) {
    $checks | ConvertTo-Json -Depth 4
} else {
    Write-Host "SkillCenter environment verification" -ForegroundColor Cyan
    $checks | Format-Table Id, Status, Requirement, Detected, Detail -AutoSize | Out-Host
    if ($missing.Count -eq 0) {
        Write-Host "Environment status: READY" -ForegroundColor Green
    } else {
        Write-Host "Environment status: $($missing.Count) check(s) require attention" -ForegroundColor Yellow
        $missing | ForEach-Object { Write-Host "- [$($_.Id)] $($_.InstallHint)" -ForegroundColor Yellow }
    }
}

if ($FailOnMissing -and $missing.Count -gt 0) {
    exit 1
}

exit 0
