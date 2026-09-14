Set-StrictMode -Version Latest

$script:ProductionConfigDefinitions = @(
    [ordered]@{ id = 'deployment.environment'; variable = 'SKILL_CENTER_ENVIRONMENT'; kind = 'equals'; expected = 'production'; hint = 'set the deployment environment to production' }
    [ordered]@{ id = 'persistence.backend'; variable = 'SKILL_CENTER_PERSISTENCE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use the shared PostgreSQL persistence backend' }
    [ordered]@{ id = 'persistence.governance'; variable = 'SKILL_CENTER_GOVERNANCE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for governance state' }
    [ordered]@{ id = 'persistence.quality'; variable = 'SKILL_CENTER_QUALITY_EVIDENCE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for quality evidence' }
    [ordered]@{ id = 'persistence.benchmark'; variable = 'SKILL_CENTER_BENCHMARK_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for Benchmark evidence' }
    [ordered]@{ id = 'persistence.release'; variable = 'SKILL_CENTER_RELEASE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for release records' }
    [ordered]@{ id = 'persistence.optimization-work-item'; variable = 'SKILL_CENTER_OPTIMIZATION_WORK_ITEM_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for optimization work items' }
    [ordered]@{ id = 'persistence.optimization-experiment'; variable = 'SKILL_CENTER_OPTIMIZATION_EXPERIMENT_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for optimization experiments' }
    [ordered]@{ id = 'persistence.production-evidence'; variable = 'SKILL_CENTER_PRODUCTION_EVIDENCE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for production evidence' }
    [ordered]@{ id = 'persistence.execution-environment'; variable = 'SKILL_CENTER_EXECUTION_ENVIRONMENT_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for execution environment assets' }
    [ordered]@{ id = 'persistence.skill-scope'; variable = 'SKILL_CENTER_SKILL_SCOPE_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for Skill scope state' }
    [ordered]@{ id = 'persistence.skill-relation'; variable = 'SKILL_CENTER_SKILL_RELATION_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use PostgreSQL for Skill relations' }
    [ordered]@{ id = 'persistence.lifecycle-projection'; variable = 'SKILL_CENTER_LIFECYCLE_PROJECTION_BACKEND'; kind = 'equals'; expected = 'postgresql'; hint = 'use the approved PostgreSQL lifecycle projection' }
    [ordered]@{ id = 'persistence.postgres-url'; variable = 'SKILL_CENTER_POSTGRES_URL'; kind = 'postgres-url'; hint = 'set a PostgreSQL JDBC URL without embedded credentials' }
    [ordered]@{ id = 'persistence.postgres-username'; variable = 'SKILL_CENTER_POSTGRES_USERNAME'; kind = 'required'; hint = 'set the managed database username' }
    [ordered]@{ id = 'persistence.postgres-password'; variable = 'SKILL_CENTER_POSTGRES_PASSWORD'; kind = 'required'; hint = 'inject the database password through Secret Manager' }
    [ordered]@{ id = 'operations.runtime-summary'; variable = 'SKILL_CENTER_RUNTIME_SUMMARY_BACKEND'; kind = 'equals'; expected = 'redis'; hint = 'use shared Redis for runtime summaries' }
    [ordered]@{ id = 'operations.alert-state'; variable = 'SKILL_CENTER_OPERATIONS_ALERT_STATE_BACKEND'; kind = 'equals'; expected = 'redis'; hint = 'use shared Redis for alert state' }
    [ordered]@{ id = 'operations.metrics'; variable = 'SKILL_CENTER_OPERATIONS_METRICS_STORAGE'; kind = 'equals'; expected = 'redis'; hint = 'use shared Redis for operations metrics' }
    [ordered]@{ id = 'operations.alert-scheduler'; variable = 'SKILL_CENTER_OPERATIONS_ALERT_SCHEDULER_ENABLED'; kind = 'equals'; expected = 'true'; hint = 'enable the production alert evaluation scheduler' }
    [ordered]@{ id = 'operations.notification-url'; variable = 'SKILL_CENTER_OPERATIONS_ALERT_NOTIFICATION_URL'; kind = 'https-uri'; hint = 'set an HTTPS enterprise notification endpoint' }
    [ordered]@{ id = 'artifact-storage.backend'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_BACKEND'; kind = 'equals'; expected = 'object-storage'; hint = 'use the approved shared object storage backend' }
    [ordered]@{ id = 'artifact-storage.mode'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_MODE'; kind = 'equals'; expected = 'http'; hint = 'enable the real object storage adapter explicitly' }
    [ordered]@{ id = 'artifact-storage.endpoint'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS object storage endpoint' }
    [ordered]@{ id = 'artifact-storage.bucket'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_BUCKET'; kind = 'required'; hint = 'set the production artifact bucket name' }
    [ordered]@{ id = 'artifact-storage.access-key-ref'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_ACCESS_KEY_ID_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'artifact-storage.secret-key-ref'; variable = 'SKILL_CENTER_ARTIFACT_STORAGE_SECRET_ACCESS_KEY_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'package-upload.backend'; variable = 'SKILL_CENTER_PACKAGE_UPLOAD_BACKEND'; kind = 'equals'; expected = 'distributed'; hint = 'use the distributed upload backend' }
    [ordered]@{ id = 'package-security.mode'; variable = 'SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_MODE'; kind = 'equals'; expected = 'required'; hint = 'make external security scanning a required gate' }
    [ordered]@{ id = 'package-security.endpoint'; variable = 'SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS security scanner endpoint' }
    [ordered]@{ id = 'package-security.credential-ref'; variable = 'SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'package-security.capabilities'; variable = 'SKILL_CENTER_PACKAGE_SECURITY_EXTERNAL_CAPABILITIES'; kind = 'capabilities'; expected = 'MALWARE,SENSITIVE_INFORMATION,DEPENDENCY_VULNERABILITY,LICENSE'; hint = 'enable all four required scanner capability classes' }
    [ordered]@{ id = 'provider.runner'; variable = 'SKILL_CENTER_PROVIDERS_RUNNER'; kind = 'equals'; expected = 'openclaw'; hint = 'select the production OpenClaw runner' }
    [ordered]@{ id = 'provider.evaluation'; variable = 'SKILL_CENTER_PROVIDERS_EVALUATION'; kind = 'equals'; expected = 'deepeval'; hint = 'select the production DeepEval evaluator' }
    [ordered]@{ id = 'provider.observability'; variable = 'SKILL_CENTER_PROVIDERS_OBSERVABILITY'; kind = 'equals'; expected = 'langfuse'; hint = 'select the production Langfuse observability provider' }
    [ordered]@{ id = 'provider.trace'; variable = 'SKILL_CENTER_PROVIDERS_TRACE'; kind = 'equals'; expected = 'langfuse'; hint = 'select the production Langfuse trace provider' }
    [ordered]@{ id = 'provider.openclaw-mode'; variable = 'SKILL_CENTER_OPENCLAW_MODE'; kind = 'equals'; expected = 'http'; hint = 'enable the OpenClaw HTTP adapter' }
    [ordered]@{ id = 'provider.deepeval-mode'; variable = 'SKILL_CENTER_DEEPEVAL_MODE'; kind = 'equals'; expected = 'http'; hint = 'enable the DeepEval HTTP adapter' }
    [ordered]@{ id = 'provider.langfuse-mode'; variable = 'SKILL_CENTER_LANGFUSE_MODE'; kind = 'equals'; expected = 'http'; hint = 'enable the Langfuse HTTP adapter' }
    [ordered]@{ id = 'provider.openclaw-endpoint'; variable = 'SKILL_CENTER_OPENCLAW_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS OpenClaw endpoint' }
    [ordered]@{ id = 'provider.openclaw-credential-ref'; variable = 'SKILL_CENTER_OPENCLAW_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'provider.deepeval-endpoint'; variable = 'SKILL_CENTER_DEEPEVAL_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS DeepEval endpoint' }
    [ordered]@{ id = 'provider.deepeval-credential-ref'; variable = 'SKILL_CENTER_DEEPEVAL_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'provider.langfuse-endpoint'; variable = 'SKILL_CENTER_LANGFUSE_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS Langfuse endpoint' }
    [ordered]@{ id = 'provider.langfuse-trace-endpoint'; variable = 'SKILL_CENTER_LANGFUSE_TRACE_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS Langfuse trace endpoint' }
    [ordered]@{ id = 'provider.langfuse-credential-ref'; variable = 'SKILL_CENTER_LANGFUSE_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'release-target.mode'; variable = 'SKILL_CENTER_RELEASE_TARGET_MODE'; kind = 'equals'; expected = 'http'; hint = 'select the real release target adapter' }
    [ordered]@{ id = 'release-target.endpoint'; variable = 'SKILL_CENTER_RELEASE_TARGET_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS release target endpoint' }
    [ordered]@{ id = 'release-target.credential-ref'; variable = 'SKILL_CENTER_RELEASE_TARGET_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'release.admission'; variable = 'SKILL_CENTER_RELEASE_ADMISSION_MODE'; kind = 'equals'; expected = 'CONTROLLED'; hint = 'enable controlled release admission in production' }
    [ordered]@{ id = 'search.backend'; variable = 'SKILL_CENTER_SEARCH_INDEX_BACKEND'; kind = 'equals'; expected = 'opensearch'; hint = 'select the shared OpenSearch backend' }
    [ordered]@{ id = 'search.mode'; variable = 'SKILL_CENTER_SEARCH_INDEX_MODE'; kind = 'equals'; expected = 'http'; hint = 'enable the HTTP search adapter' }
    [ordered]@{ id = 'search.endpoint'; variable = 'SKILL_CENTER_SEARCH_INDEX_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS OpenSearch endpoint' }
    [ordered]@{ id = 'search.name'; variable = 'SKILL_CENTER_SEARCH_INDEX_NAME'; kind = 'required'; hint = 'set the production search index name' }
    [ordered]@{ id = 'search.events'; variable = 'SKILL_CENTER_SEARCH_INDEX_EVENTS_ENABLED'; kind = 'equals'; expected = 'true'; hint = 'enable the approved cross-instance refresh path' }
    [ordered]@{ id = 'search.consumer-id'; variable = 'SKILL_CENTER_SEARCH_INDEX_EVENTS_CONSUMER_ID'; kind = 'required'; hint = 'set a stable unique consumer ID per API instance' }
    [ordered]@{ id = 'organization-directory.mode'; variable = 'SKILL_CENTER_ORGANIZATION_DIRECTORY_MODE'; kind = 'equals'; expected = 'http'; hint = 'select the enterprise organization directory' }
    [ordered]@{ id = 'organization-directory.endpoint'; variable = 'SKILL_CENTER_ORGANIZATION_DIRECTORY_ENDPOINT'; kind = 'https-uri'; hint = 'set the HTTPS organization directory endpoint' }
    [ordered]@{ id = 'organization-directory.credential-ref'; variable = 'SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF'; kind = 'secret-ref'; hint = 'set a Secret Manager reference, not a secret value' }
    [ordered]@{ id = 'authentication.mode'; variable = 'SKILL_CENTER_AUTHENTICATION_MODE'; kind = 'equals'; expected = 'jwt'; hint = 'enable enterprise JWT/JWKS authentication' }
    [ordered]@{ id = 'authentication.jwks-uri'; variable = 'SKILL_CENTER_JWT_JWKS_URI'; kind = 'https-uri'; hint = 'set the HTTPS enterprise JWKS endpoint' }
    [ordered]@{ id = 'authentication.issuer'; variable = 'SKILL_CENTER_JWT_ISSUER'; kind = 'required'; hint = 'set the configured JWT issuer' }
    [ordered]@{ id = 'authentication.audience'; variable = 'SKILL_CENTER_JWT_AUDIENCE'; kind = 'required'; hint = 'set the configured JWT audience' }
    [ordered]@{ id = 'artifact.public-base-url'; variable = 'SKILL_CENTER_ARTIFACT_BASE_URL'; kind = 'https-uri'; hint = 'set the HTTPS public artifact base URL' }
)

function Get-ProductionConfigDefinitions {
    return @($script:ProductionConfigDefinitions)
}

function Get-EnvironmentValue {
    param(
        [hashtable]$Environment,
        [string]$Name
    )

    if ($null -ne $Environment -and $Environment.ContainsKey($Name) -and $null -ne $Environment[$Name]) {
        return ([string]$Environment[$Name]).Trim()
    }

    return ''
}

function Test-HttpsValue {
    param([string]$Value)

    try {
        $uri = [System.Uri]$Value
        return $uri.IsAbsoluteUri -and $uri.Scheme -eq 'https' -and [string]::IsNullOrWhiteSpace($uri.UserInfo)
    } catch {
        return $false
    }
}

function Test-SecretReference {
    param([string]$Value)

    return $Value -match '^secret://[A-Za-z0-9._/-]+$'
}

function New-ProductionConfigCheck {
    param(
        [hashtable]$Definition,
        [hashtable]$Environment
    )

    $value = Get-EnvironmentValue $Environment ([string]$Definition.variable)
    $status = 'READY'
    $reasonCode = 'PRODUCTION_CONFIG_OK'

    if ([string]::IsNullOrWhiteSpace($value)) {
        $status = 'MISSING'
        $reasonCode = 'PRODUCTION_CONFIG_MISSING'
    } else {
        switch ([string]$Definition.kind) {
            'equals' {
                if ($value -cne [string]$Definition.expected) {
                    $status = 'INVALID'
                    $reasonCode = 'PRODUCTION_CONFIG_INVALID'
                }
            }
            'https-uri' {
                if (-not (Test-HttpsValue $value)) {
                    $status = 'INVALID'
                    $reasonCode = 'PRODUCTION_CONFIG_HTTPS_REQUIRED'
                }
            }
            'secret-ref' {
                if (-not (Test-SecretReference $value)) {
                    $status = 'INVALID'
                    $reasonCode = 'PRODUCTION_CONFIG_SECRET_REF_REQUIRED'
                }
            }
            'postgres-url' {
                if ($value -notmatch '^jdbc:postgresql://[^\s/@?#]+(?:/[^\s?#]*)?(?:\?[^\s]*)?$' -or $value -match '(?i)(password|user|username)=') {
                    $status = 'INVALID'
                    $reasonCode = 'PRODUCTION_CONFIG_POSTGRES_URL_INVALID'
                }
            }
            'capabilities' {
                $actual = @($value -split ',' | ForEach-Object { $_.Trim().ToUpperInvariant() } | Where-Object { $_ })
                $expected = @(([string]$Definition.expected) -split ',' | ForEach-Object { $_.Trim().ToUpperInvariant() })
                $missing = @($expected | Where-Object { $_ -notin $actual })
                if ($missing.Count -gt 0) {
                    $status = 'INVALID'
                    $reasonCode = 'PRODUCTION_CONFIG_CAPABILITIES_INCOMPLETE'
                }
            }
            'required' { }
            default {
                $status = 'INVALID'
                $reasonCode = 'PRODUCTION_CONFIG_KIND_UNSUPPORTED'
            }
        }
    }

    [pscustomobject][ordered]@{
        id = [string]$Definition.id
        variable = [string]$Definition.variable
        status = $status
        reasonCode = $reasonCode
        hint = [string]$Definition.hint
    }
}

function Get-ProductionConfigReport {
    param([hashtable]$Environment = @{})

    $checks = @(
        foreach ($definition in $script:ProductionConfigDefinitions) {
            New-ProductionConfigCheck $definition $Environment
        }
    )
    $notReady = @($checks | Where-Object { $_.status -ne 'READY' })

    [pscustomobject][ordered]@{
        profile = 'production'
        passed = ($notReady.Count -eq 0)
        checkCount = $checks.Count
        ready = $checks.Count - $notReady.Count
        notReady = $notReady.Count
        checks = $checks
    }
}

Export-ModuleMember -Function Get-ProductionConfigDefinitions, Get-ProductionConfigReport
