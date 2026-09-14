Describe 'CCE CSMS Kubernetes deployment assets' {
    BeforeAll {
        $repoRoot = Split-Path -Parent $PSScriptRoot
        $chartRoot = Join-Path $repoRoot 'deploy\k8s\skillcenter'
        $requiredFiles = @(
            'Chart.yaml',
            'values.yaml',
            'values-prod.example.yaml',
            'Dockerfile.api',
            'Dockerfile.web',
            'templates\_helpers.tpl',
            'templates\serviceaccount.yaml',
            'templates\secretproviderclass.yaml',
            'templates\configmap.yaml',
            'templates\api-deployment.yaml',
            'templates\web-deployment.yaml',
            'templates\service.yaml',
            'templates\ingress.yaml',
            'templates\hpa.yaml',
            'templates\pdb.yaml',
            'templates\networkpolicy.yaml',
            'templates\migration-job.yaml',
            'templates\servicemonitor.yaml'
        )
    }

    It 'contains the complete chart and image build contract' {
        foreach ($relativePath in $requiredFiles) {
            Test-Path (Join-Path $chartRoot $relativePath) | Should Be $true
        }
    }

    It 'uses CCE CSMS with workload identity' {
        $source = Get-Content -Raw (Join-Path $chartRoot 'templates\secretproviderclass.yaml')
        $productionValues = Get-Content -Raw (Join-Path $chartRoot 'values-prod.example.yaml')
        $source | Should Match 'objectType:\s+"csms"'
        $source | Should Match 'useWorkloadCred:'
        $source | Should Match 'objectVersion:'
        $productionValues | Should Match 'useWorkloadCred:\s+true'
        $productionValues | Should Match 'version:\s+latest'
    }

    It 'starts the API in the production Spring profile' {
        $source = Get-Content -Raw (Join-Path $chartRoot 'templates\api-deployment.yaml')
        $source | Should Match 'SPRING_PROFILES_ACTIVE'
        $source | Should Match 'prod'
        $source | Should Match '/actuator/health/readiness'
        $source | Should Match '/actuator/health/liveness'
    }

    It 'keeps secrets out of chart values and ConfigMaps' {
        $values = Get-Content -Raw (Join-Path $chartRoot 'values-prod.example.yaml')
        $config = Get-Content -Raw (Join-Path $chartRoot 'templates\configmap.yaml')
        $combined = "$values`n$config"
        $combined | Should Not Match '(?i)(password|token|private.?key|access.?key)\s*:\s*[^$\{\s][^\r\n]*'
        $combined | Should Not Match '(?i)(AKIA|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|secret-value|CHANGE-ME-secret)'
    }

    It 'keeps production endpoints explicit placeholders until the target is supplied' {
        $values = Get-Content -Raw (Join-Path $chartRoot 'values-prod.example.yaml')
        $values | Should Match 'CHANGE-ME'
        $values | Should Match 'SKILL_CENTER_ENVIRONMENT'
        $values | Should Match 'SKILL_CENTER_AUTHENTICATION_MODE'
    }

    It 'uses non-root containers and bounded availability policies' {
        $api = Get-Content -Raw (Join-Path $chartRoot 'templates\api-deployment.yaml')
        $web = Get-Content -Raw (Join-Path $chartRoot 'templates\web-deployment.yaml')
        $pdb = Get-Content -Raw (Join-Path $chartRoot 'templates\pdb.yaml')
        $api | Should Match 'runAsNonRoot:\s+true'
        $web | Should Match 'runAsNonRoot:\s+true'
        $pdb | Should Match 'maxUnavailable|minAvailable'
    }
}
