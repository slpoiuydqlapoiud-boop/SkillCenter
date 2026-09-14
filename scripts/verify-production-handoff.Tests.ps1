$scriptPath = Join-Path $PSScriptRoot 'verify-production-handoff.ps1'

Describe 'verify-production-handoff contract' {
    It 'exists and parses as a PowerShell script' {
        Test-Path -LiteralPath $scriptPath | Should Be $true
        $source = Get-Content -LiteralPath $scriptPath -Raw

        { [scriptblock]::Create($source) } | Should Not Throw
    }

    It 'supports safe read-only handoff verification parameters' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[string\]\$BaseUrl'
        $source | Should Match '\[switch\]\$FailOnNotReady'
        $source | Should Match '\[switch\]\$Json'
        $source | Should Match 'Invoke-WebRequest'
        $source | Should Match 'api/v1/admin/platform/readiness'
        $source | Should Match 'api/v1/admin/platform/evidence'
        $source | Should Not Match '(?im)-Method\s+(POST|PUT|PATCH|DELETE)'
        $source | Should Not Match '(?im)\b(Invoke-RestMethod|Invoke-WebRequest)[^\r\n]*-Body\b'
    }

    It 'covers every production evidence item in the catalog' {
        $source = Get-Content -LiteralPath $scriptPath -Raw
        @(
            'BACKUP_PITR',
            'DATABASE_CAPACITY_SLO',
            'OBJECT_STORAGE',
            'PROVIDER_SECURITY',
            'REDIS_HA',
            'RELEASE_APPROVAL',
            'ROLLBACK_DRILL',
            'SLO_UAT',
            'SSO_ORGANIZATION'
        ) | ForEach-Object {
            $source | Should Match $_
        }
    }

    It 'uses stable fail-closed exit codes and emits only safe summary fields' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match 'exit 0'
        $source | Should Match 'exit 1'
        $source | Should Match 'exit 2'
        $source | Should Match 'evidenceId'
        $source | Should Match 'status'
        $source | Should Match 'revision'
        $source | Should Match 'expiresAt'
        $source | Should Not Match '(?im)\b(ownerUserId|updatedBy|evidenceRef|summary)\s*='
        $source | Should Not Match '(?im)\b(password|secret|credential|bearer|private\s+key)\s*[:=]'
    }

    It 'is non-destructive and does not mutate readiness or evidence state' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Not Match '(?im)\b(git\s+(reset|clean|checkout|commit|push)|Remove-Item|Set-Content|Out-File|ConvertTo-Json\s+.*\|\s*Set-)\b'
    }

    It 'renders the safe evidence summary as table rows in human-readable output' {
        $source = Get-Content -LiteralPath $scriptPath -Raw

        $source | Should Match '\[pscustomobject\]\$_'
        $source | Should Match 'Format-Table evidenceId, status, revision, expiresAt'
    }
}
