# CCE CSMS Kubernetes Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a production-ready Kubernetes deployment layer for SkillCenter on Huawei CCE with CSMS-backed runtime secrets.

**Architecture:** A Helm chart renders API/Web workloads and platform policy resources. CCE's Secrets Store CSI integration reads CSMS using Workload Identity, syncs only named keys into a namespace Secret, and injects them into the API; all production values remain examples until the target environment supplies real identifiers.

**Tech Stack:** Helm templates, Kubernetes v1 resources, CCE Secrets Store CSI `SecretProviderClass`, Docker multi-stage builds, PowerShell verification.

**Spec:** `docs/superpowers/specs/2026-09-08-cce-csms-kubernetes-deployment-design.md`

## Global Constraints

- Use CCE `objectType: csms` and `useWorkloadCred: "true"` for CSMS access.
- Never commit secret values, AK/SK, tokens, private keys, or real production endpoints.
- API listens on port 8080 and Web listens on port 80 inside the cluster.
- Production uses `SPRING_PROFILES_ACTIVE=prod` and `SKILL_CENTER_ENVIRONMENT=production`.
- Keep external provider, SSO, database, Redis, object storage, scanner, and release target values as explicit replacement inputs.

### Task 1: Deployment asset contract tests

**Files:**
- Create: `scripts/verify-k8s-deployment.Tests.ps1`

- [x] **Step 1: Write the failing tests**

  Assert that the chart, both Dockerfiles, the CSMS SecretProviderClass, production values, and all required workload/policy templates exist; assert that manifests contain `useWorkloadCred: "true"`, `objectType: "csms"`, `SPRING_PROFILES_ACTIVE=prod`, and no forbidden literal secrets.

- [x] **Step 2: Run the focused test and verify it fails because the deployment layer is absent**

  Run: `Invoke-Pester .\scripts\verify-k8s-deployment.Tests.ps1 -Output Detailed`

  Expected: FAIL with missing `deploy/k8s/skillcenter` assets.

### Task 2: Helm chart and runtime images

**Files:**
- Create: `deploy/k8s/skillcenter/Chart.yaml`
- Create: `deploy/k8s/skillcenter/values.yaml`
- Create: `deploy/k8s/skillcenter/values-prod.example.yaml`
- Create: `deploy/k8s/skillcenter/templates/_helpers.tpl`
- Create: `deploy/k8s/skillcenter/Dockerfile.api`
- Create: `deploy/k8s/skillcenter/Dockerfile.web`

- [x] **Step 1: Implement chart metadata and safe defaults**

  Set chart API version `v2`, API image port 8080, Web image port 80, non-production defaults disabled for external secret sync, and values for replica/resource/security policies.

- [x] **Step 2: Implement multi-stage images**

  Build the API with Maven/JDK 21 and run it on a JRE 21 image; build Web with Node.js and serve `dist/client` from an unprivileged Nginx-compatible runtime. Expose only the documented container ports.

- [x] **Step 3: Add the production example values**

  Include `production`, `prod` profile, CCE service account settings, CSMS object-name mappings, and non-secret environment keys. Keep endpoint values as `CHANGE-ME` and never include credential values.

- [x] **Step 4: Run the focused test and verify the image/chart contract passes**

  Run: `Invoke-Pester .\scripts\verify-k8s-deployment.Tests.ps1 -Output Detailed`

  Expected: chart/image and placeholder-safety assertions pass; resource template assertions remain red until Task 3.

### Task 3: Workloads, CSMS, networking, and observability templates

**Files:**
- Create: `deploy/k8s/skillcenter/templates/serviceaccount.yaml`
- Create: `deploy/k8s/skillcenter/templates/secretproviderclass.yaml`
- Create: `deploy/k8s/skillcenter/templates/configmap.yaml`
- Create: `deploy/k8s/skillcenter/templates/api-deployment.yaml`
- Create: `deploy/k8s/skillcenter/templates/web-deployment.yaml`
- Create: `deploy/k8s/skillcenter/templates/service.yaml`
- Create: `deploy/k8s/skillcenter/templates/ingress.yaml`
- Create: `deploy/k8s/skillcenter/templates/hpa.yaml`
- Create: `deploy/k8s/skillcenter/templates/pdb.yaml`
- Create: `deploy/k8s/skillcenter/templates/networkpolicy.yaml`
- Create: `deploy/k8s/skillcenter/templates/migration-job.yaml`
- Create: `deploy/k8s/skillcenter/templates/servicemonitor.yaml`

- [x] **Step 1: Render CSMS access resources**

  Render a ServiceAccount, a `secrets-store.csi.k8s.io/v1` SecretProviderClass with `provider: cce`, `objectType: csms`, `objectVersion: latest`, `useWorkloadCred: "true"`, and a Secret object containing only the named runtime keys.

- [x] **Step 2: Render API/Web workloads**

  Inject ConfigMap and Secret values, set `SPRING_PROFILES_ACTIVE=prod`, add probes against `/actuator/health/liveness` and `/actuator/health/readiness`, use non-root security contexts, and mount the CSI volume read-only.

- [x] **Step 3: Render platform policies and jobs**

  Add internal Services, TLS-aware Ingress, bounded HPA/PDB, default-deny NetworkPolicy with API-to-dependency egress, Flyway migration Job, and optional Prometheus ServiceMonitor.

- [x] **Step 4: Run tests and render the chart**

  Run: `Invoke-Pester .\scripts\verify-k8s-deployment.Tests.ps1 -Output Detailed`; if `helm` is available, run `helm lint .\deploy\k8s\skillcenter` and `helm template skillcenter .\deploy\k8s\skillcenter -f .\deploy\k8s\skillcenter\values-prod.example.yaml`.

### Task 4: Deployment documentation and verification integration

**Files:**
- Create: `deploy/k8s/skillcenter/README.md`
- Modify: `docs/project/environment-dependencies.md`
- Modify: `deploy/prod/README.md`

- [x] **Step 1: Document CCE prerequisites and CSMS mapping**

  Document CCE version/Workload Identity prerequisites, IAM least-privilege actions, CSMS object naming, namespace setup, image registry inputs, DNS/TLS inputs, and the controlled rollout/secret-rotation procedure.

- [x] **Step 2: Document validation commands**

  Include Helm lint/template, `verify-production-config`, `verify-production-handoff`, readiness checks, and the explicit distinction between rendered manifests and accepted production evidence.

- [x] **Step 3: Run the full relevant verification**

  Run the focused Pester test, Helm checks when available, `git diff --check`, and the existing production configuration/handoff tests. Report any unavailable external cluster checks as pending rather than passing.
