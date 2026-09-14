# SkillCenter on Huawei CCE

This chart is a production deployment skeleton for SkillCenter. It is intentionally
provider-agnostic for databases and external AI systems, while using the Huawei CCE
DEW/CSMS integration for runtime secrets.

## Prerequisites

1. A CCE cluster with the Secrets Store CSI integration enabled.
2. CCE Workload Identity enabled; the cluster must meet the CCE version prerequisite
   documented by Huawei Cloud (1.19.16 or later).
3. An IAM identity provider and a least-privilege IAM delegation bound to the API
   ServiceAccount. The delegation needs read access only to the listed CSMS objects.
4. CSMS objects created for the names in `values-prod.example.yaml`, or a copied values
   file with the real object names supplied by the platform team.
5. A private SWR image repository, an Ingress controller, TLS certificate Secret,
   PostgreSQL/Redis/OpenSearch endpoints, and the approved external Provider endpoints.

The chart does not create the CCE cluster, IAM delegation, CSMS objects, data services,
DNS, TLS certificates, or external Provider services.

## Build images

Run from the repository root:

```powershell
docker build -f deploy/k8s/skillcenter/Dockerfile.api -t <swr-repository>/skillcenter/api:<git-sha> .
docker build -f deploy/k8s/skillcenter/Dockerfile.web -t <swr-repository>/skillcenter/web:<git-sha> .
docker push <swr-repository>/skillcenter/api:<git-sha>
docker push <swr-repository>/skillcenter/web:<git-sha>
```

Replace `<swr-repository>` and `<git-sha>` outside the repository; do not put registry
credentials in this file.

## Configure CSMS

Copy `values-prod.example.yaml` to a deployment-system-owned file and replace only the
explicit `CHANGE-ME` values. Each `csms.objects[].csmsName` is the CSMS object name,
not a secret value. The resulting Kubernetes Secret is named by `csms.secretName` and
contains only the keys listed in `csms.objects[].key`.

The API reads sensitive values through `envFrom.secretRef`; non-sensitive selectors and
endpoints are rendered into the ConfigMap. A CSMS rotation updates the CSI material and
the synced Secret, but existing environment variables do not change inside a running
JVM. Execute a controlled API rollout after validating the new CSMS version.

## Render and install

```powershell
helm lint .\deploy\k8s\skillcenter
helm template skillcenter .\deploy\k8s\skillcenter `
  -n skillcenter `
  -f .\deploy\k8s\skillcenter\values-prod.example.yaml
helm upgrade --install skillcenter .\deploy\k8s\skillcenter `
  -n skillcenter --create-namespace `
  -f .\deploy\k8s\skillcenter\values-prod.example.yaml
```

Run `verify-production-config.ps1` in the same environment that supplies the final
values before installation. Do not use the example values as a production release.

## Post-deploy gates

```powershell
kubectl -n skillcenter get pods
kubectl -n skillcenter get secretproviderclass
kubectl -n skillcenter describe pod -l app.kubernetes.io/component=api
kubectl -n skillcenter rollout status deployment/skillcenter-skillcenter-api

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-production-config.ps1 -Json -FailOnNotReady
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-production-handoff.ps1 `
  -BaseUrl https://<api-host> -Json
```

The deployment layer being rendered successfully is not production acceptance. The
external evidence IDs, UAT, HA/failover, backup/PITR, capacity/SLO and rollback gates
remain required.
# 历史参考：不属于当前部门版默认部署

当前目标已收敛为 Windows 单机 + MySQL + 本地文件，默认入口见 `scripts/start-local.ps1` 和 `scripts/start-department-local.ps1`。本目录仅保留此前企业级 CCE/Kubernetes 方案，除非后续重新提出云原生部署需求，否则不纳入部门版任务和验收。
