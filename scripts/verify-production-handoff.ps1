param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [switch]$FailOnNotReady,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

$requiredEvidenceIds = @(
    "BACKUP_PITR",
    "DATABASE_CAPACITY_SLO",
    "OBJECT_STORAGE",
    "PROVIDER_SECURITY",
    "REDIS_HA",
    "RELEASE_APPROVAL",
    "ROLLBACK_DRILL",
    "SLO_UAT",
    "SSO_ORGANIZATION"
)

function Get-ValidatedBaseUri {
    param([string]$Value)

    try {
        $uri = [System.Uri]$Value.TrimEnd("/")
    } catch {
        throw "BaseUrl must be an absolute HTTP(S) URL without credentials"
    }

    if (-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @("http", "https") -or -not [string]::IsNullOrWhiteSpace($uri.UserInfo)) {
        throw "BaseUrl must be an absolute HTTP(S) URL without credentials"
    }

    return $uri.AbsoluteUri.TrimEnd("/")
}

function Get-JsonPayload {
    param(
        [string]$Uri,
        [hashtable]$Headers
    )

    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 10 -Headers $Headers
    if ($response.StatusCode -ne 200) {
        throw "The verification endpoint did not return HTTP 200"
    }

    $payload = $response.Content | ConvertFrom-Json
    if ($null -eq $payload -or $null -eq $payload.data) {
        throw "The verification endpoint returned an invalid response"
    }

    return $payload.data
}

function Get-SafeEvidenceItem {
    param([object]$Evidence)

    [ordered]@{
        evidenceId = [string]$Evidence.evidenceId
        status = [string]$Evidence.status
        revision = [int]$Evidence.revision
        expiresAt = if ($null -eq $Evidence.expiresAt) { $null } else { [string]$Evidence.expiresAt }
    }
}

function New-HandoffSummary {
    param(
        [object]$Readiness,
        [object[]]$Evidence
    )

    $evidenceById = @{}
    foreach ($item in @($Evidence)) {
        if ($null -ne $item.evidenceId) {
            $evidenceById[[string]$item.evidenceId] = $item
        }
    }

    $safeItems = @(
        foreach ($evidenceId in $requiredEvidenceIds) {
            if ($evidenceById.ContainsKey($evidenceId)) {
                Get-SafeEvidenceItem $evidenceById[$evidenceId]
            } else {
                [ordered]@{
                    evidenceId = $evidenceId
                    status = "MISSING"
                    revision = 0
                    expiresAt = $null
                }
            }
        }
    )

    $acceptedCount = @($safeItems | Where-Object { $_.status -eq "ACCEPTED" }).Count
    $readinessOverall = [string]$Readiness.overall
    $externalComponent = @($Readiness.components | Where-Object { $_.componentId -eq "PRODUCTION_EXTERNAL_EVIDENCE" }) | Select-Object -First 1

    [ordered]@{
        checkedAt = [DateTime]::UtcNow.ToString("o")
        passed = ($readinessOverall -eq "READY" -and $acceptedCount -eq $requiredEvidenceIds.Count)
        readiness = [ordered]@{
            overall = $readinessOverall
            externalEvidenceStatus = if ($null -eq $externalComponent) { "UNKNOWN" } else { [string]$externalComponent.status }
            externalEvidenceReasonCode = if ($null -eq $externalComponent) { "MISSING_COMPONENT" } else { [string]$externalComponent.reasonCode }
        }
        evidence = [ordered]@{
            required = $requiredEvidenceIds.Count
            accepted = $acceptedCount
            notReady = $requiredEvidenceIds.Count - $acceptedCount
            items = $safeItems
        }
    }
}

try {
    $validatedBaseUrl = Get-ValidatedBaseUri $BaseUrl
    $headers = @{ "Accept" = "application/json"; "X-User-Role" = "admin" }
    $readiness = Get-JsonPayload "$validatedBaseUrl/api/v1/admin/platform/readiness" $headers
    $evidence = @(Get-JsonPayload "$validatedBaseUrl/api/v1/admin/platform/evidence" $headers)
    $result = New-HandoffSummary $readiness $evidence

    if ($Json) {
        $result | ConvertTo-Json -Depth 8
    } else {
        Write-Host "SkillCenter production handoff verification" -ForegroundColor Cyan
        Write-Host "Readiness: $($result.readiness.overall); external evidence: $($result.readiness.externalEvidenceStatus)"
        Write-Host "Evidence: $($result.evidence.accepted)/$($result.evidence.required) accepted"
        @($result.evidence.items | ForEach-Object { [pscustomobject]$_ }) |
            Format-Table evidenceId, status, revision, expiresAt -AutoSize | Out-Host
        if ($result.passed) {
            Write-Host "Production handoff status: READY" -ForegroundColor Green
        } else {
            Write-Host "Production handoff status: NOT_READY" -ForegroundColor Yellow
        }
    }

    if ($FailOnNotReady -and -not $result.passed) {
        exit 2
    }

    exit 0
} catch {
    $errorResult = [ordered]@{
        checkedAt = [DateTime]::UtcNow.ToString("o")
        passed = $false
        errorCode = "VERIFICATION_FAILED"
        errorMessage = "Unable to fetch or validate the production handoff evidence"
    }

    if ($Json) {
        $errorResult | ConvertTo-Json -Depth 4
    } else {
        Write-Error $errorResult.errorMessage
    }

    exit 1
}
