param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repoRoot "apps\web"
$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-Verification([string]$Name, [scriptblock]$Action) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) {
            throw "exit code $LASTEXITCODE"
        }
        Write-Host "$Name passed" -ForegroundColor Green
    } catch {
        $message = "$Name failed: $($_.Exception.Message)"
        $failures.Add($message)
        Write-Host $message -ForegroundColor Red
    }
}

Invoke-Verification "Focused persistence tests" {
    & "mvn.cmd" "-q" "-f" (Join-Path $repoRoot "apps\api\pom.xml") "-DforkCount=0" "-Dtest=PersistenceStartupGateTest,PersistenceControllerTest,PersistenceArtifactCatalogTest,PersistenceIntegrityServiceTest,PersistenceMigrationRegistryTest,PersistenceSnapshotServiceTest" "test"
}

Invoke-Verification "Full API tests" {
    & "mvn.cmd" "-q" "-f" (Join-Path $repoRoot "apps\api\pom.xml") "-DforkCount=0" "test"
}

if (Test-Path -LiteralPath (Join-Path $webRoot "node_modules")) {
    Invoke-Verification "Web tests" {
        Push-Location $webRoot
        try {
            & "npm.cmd" "test"
        } finally {
            Pop-Location
        }
    }
    Invoke-Verification "Web build" {
        Push-Location $webRoot
        try {
            & "npm.cmd" "run" "build"
        } finally {
            Pop-Location
        }
    }
} else {
    Write-Warning "Web dependencies are unavailable; skipped npm test and npm run build."
}

Invoke-Verification "Git whitespace check" {
    Push-Location $repoRoot
    try {
        & "git" "diff" "--check"
    } finally {
        Pop-Location
    }
}

if ($failures.Count -gt 0) {
    Write-Host "`nPersistence verification failed ($($failures.Count) checks):" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "- $_" -ForegroundColor Red }
    exit 1
}

Write-Host "`nPersistence verification completed successfully." -ForegroundColor Green
exit 0
