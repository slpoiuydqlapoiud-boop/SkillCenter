$scriptPath = Join-Path $PSScriptRoot "start-local.ps1"

Describe "start-local contract" {
    It "parses as a PowerShell script" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        { [scriptblock]::Create($content) } | Should Not Throw
    }

    It "supports an integration profile and safe dry-run mode" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        $content | Should Match '\[ValidateSet\("integration", "default"\)\]'
        $content | Should Match '\[switch\]\$DryRun'
        $content | Should Match 'application-integration\.yml'
        $content | Should Match '"compose"'
        $content | Should Match 'admin/search/index/probe'
        $content | Should Match 'artifact-storage/probe'
        $content | Should Match 'X-User-Role'
        $content | Should Match 'SPRING_CONFIG_ADDITIONAL_LOCATION'
        $content | Should Match 'SKILL_CENTER_SEARCH_INDEX_BACKEND.*opensearch'
        $content | Should Match 'SKILL_CENTER_SEARCH_INDEX_EVENTS_ENABLED.*true'
        $content | Should Match 'SKILL_CENTER_PACKAGE_UPLOAD_BACKEND.*distributed'
        $content | Should Match '\[switch\]\$WithObservability'
        $content | Should Match '\[switch\]\$WithMessageBus'
        $content | Should Match 'observability\.compose\.yaml'
        $content | Should Match 'SKILL_CENTER_METRICS_TOKEN_FILE'
        $content | Should Match 'SKILL_CENTER_SEARCH_INDEX_EVENTS_BUS_TRANSPORT'
    }

    It "does not contain destructive cleanup or production deployment commands" {
        $content = Get-Content -Raw -LiteralPath $scriptPath

        $content | Should Not Match '(?im)\b(Remove-Item|del|erase|rm)\b'
        $content | Should Not Match '(?im)\bgit\s+(reset|clean|checkout|commit|push)\b'
        $content | Should Not Match '(?im)\b(kubectl|helm)\b'
    }

    It "keeps local-only integration selectors explicit" {
        $content = Get-Content -Raw -LiteralPath $scriptPath
        $config = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "..\deploy\local\application-integration.yml")
        $combined = "$content`n$config"

        $combined | Should Match 'SKILL_CENTER_LOCAL_ONLY'
        $combined | Should Match 'search-index-backend:\s*opensearch'
        $combined | Should Match 'package-upload-backend:\s*distributed'
        $combined | Should Match 'artifact-storage-backend:\s*object-storage'
    }
}
