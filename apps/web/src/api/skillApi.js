function queryString(params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "" && value !== "all") search.set(key, value);
  });
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

const API_PREFIX = "/api/v1";

export function createSkillApi(request) {
  return {
    listSkills(params = {}) {
      return request(`${API_PREFIX}/skills${queryString(params)}`);
    },
    getSkill(skillId) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}`);
    },
    getSkillContent(skillId) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/content`);
    },
    getSkillQuality(skillId, params = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/quality${queryString(params)}`);
    },
    compareSkillQuality(skillId, params = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/quality/compare${queryString(params)}`);
    },
    getSkillQualitySuggestions(skillId, params = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/quality/suggestions${queryString(params)}`);
    },
    updateSkillQualitySuggestionDisposition(skillId, suggestionId, input = {}) {
      const { version, window = "24h", dataSource, runtimeId, mcpServerId, llmProviderId, status, note, evidenceType = "NONE", evidenceId = "" } = input;
      const body = { status, note: note || "" };
      if (evidenceType !== "NONE" && evidenceId) Object.assign(body, { evidenceType, evidenceId });
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/quality/suggestions/${encodeURIComponent(suggestionId)}/disposition${queryString({ version, window, dataSource, runtimeId, mcpServerId, llmProviderId })}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
    },
    uploadPackage(file) {
      const body = new FormData();
      body.append("file", file);
      return request(`${API_PREFIX}/skill-packages`, { method: "POST", body });
    },
    startResumableUpload(fileName, totalBytes) {
      return request(`${API_PREFIX}/skill-packages/uploads`, {
        method: "POST",
        body: JSON.stringify({ fileName, totalBytes }),
      });
    },
    getResumableUpload(uploadId) {
      return request(`${API_PREFIX}/skill-packages/uploads/${encodeURIComponent(uploadId)}`);
    },
    uploadResumableChunk(uploadId, start, end, totalBytes, chunk) {
      return request(`${API_PREFIX}/skill-packages/uploads/${encodeURIComponent(uploadId)}`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/octet-stream",
          "Content-Range": `bytes ${start}-${end}/${totalBytes}`,
        },
        body: chunk,
      });
    },
    completeResumableUpload(uploadId) {
      return request(`${API_PREFIX}/skill-packages/uploads/${encodeURIComponent(uploadId)}/complete`, { method: "POST" });
    },
    async uploadPackageResumable(file, options = {}) {
      const { uploadId: existingUploadId = "", chunkSize = 1024 * 1024,
        onSessionCreated = () => {}, onProgress = () => {} } = options;
      if (!file || !Number.isSafeInteger(file.size) || file.size <= 0) {
        throw new Error("上传文件为空或大小无效");
      }
      let sessionResponse = existingUploadId
        ? await this.getResumableUpload(existingUploadId)
        : await this.startResumableUpload(file.name, file.size);
      const session = sessionResponse?.data || {};
      const uploadId = session.uploadId || existingUploadId;
      if (!uploadId) throw new Error("上传会话创建失败");
      const totalBytes = Number(session.totalBytes || file.size);
      if (totalBytes !== file.size) throw new Error("上传文件与断点会话不匹配");
      const serverChunkSize = Number(session.chunkSize);
      const effectiveChunkSize = Number.isSafeInteger(serverChunkSize) && serverChunkSize > 0
        ? serverChunkSize : chunkSize;
      let receivedBytes = Number(session.receivedBytes || 0);
      if (!Number.isSafeInteger(receivedBytes) || receivedBytes < 0 || receivedBytes > totalBytes) {
        throw new Error("上传断点进度无效");
      }
      onSessionCreated({ ...session, uploadId, totalBytes, receivedBytes });
      onProgress({ uploadId, totalBytes, receivedBytes,
        percent: Math.floor((receivedBytes / totalBytes) * 100) });
      while (receivedBytes < totalBytes) {
        const end = Math.min(totalBytes, receivedBytes + effectiveChunkSize) - 1;
        const response = await this.uploadResumableChunk(uploadId, receivedBytes, end, totalBytes,
          file.slice(receivedBytes, end + 1));
        const nextReceivedBytes = Number(response?.data?.receivedBytes);
        if (nextReceivedBytes !== end + 1) throw new Error("上传服务端点进度异常");
        receivedBytes = nextReceivedBytes;
        onProgress({ uploadId, totalBytes, receivedBytes,
          percent: Math.floor((receivedBytes / totalBytes) * 100) });
      }
      return this.completeResumableUpload(uploadId);
    },
    cancelResumableUpload(uploadId) {
      return request(`${API_PREFIX}/skill-packages/uploads/${encodeURIComponent(uploadId)}`, { method: "DELETE" });
    },
    createInstallation(skillId, input = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/installations`, {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    getAnalyticsOverview(params = {}) {
      return request(`${API_PREFIX}/analytics/overview${queryString(params)}`);
    },
    getOperationsMetrics(window = "15m") {
      return request(`${API_PREFIX}/admin/operations/metrics${queryString({ window })}`);
    },
    getOperationsAlerts(window = "15m") {
      return request(`${API_PREFIX}/admin/operations/alerts${queryString({ window })}`);
    },
    getSkillLifecycleProjectionReconciliation() {
      return request(`${API_PREFIX}/admin/skill-lifecycle/projection/reconciliation`);
    },
    getPlatformReadiness() {
      return request(`${API_PREFIX}/admin/platform/readiness`);
    },
    probeReleaseTarget() {
      return request(`${API_PREFIX}/admin/platform/release-target/probe`, { method: "POST" });
    },
    listReleaseTargetProbes(limit = 20) {
      return request(`${API_PREFIX}/admin/platform/release-target/probes${queryString({ limit })}`);
    },
    probeArtifactStorage() {
      return request(`${API_PREFIX}/admin/platform/artifact-storage/probe`, { method: "POST" });
    },
    probeSearchIndex() {
      return request(`${API_PREFIX}/admin/search/index/probe`, { method: "POST" });
    },
    listProductionEvidence() {
      return request(`${API_PREFIX}/admin/platform/evidence`);
    },
    updateProductionEvidence(evidenceId, input) {
      return request(`${API_PREFIX}/admin/platform/evidence/${encodeURIComponent(evidenceId)}`, {
        method: "PUT", body: JSON.stringify(input),
      });
    },
    getSkillRuntimeMetrics(params = {}) {
      return request(`${API_PREFIX}/admin/operations/skill-runtime${queryString(params)}`);
    },
    getTraces(params = {}) {
      return request(`${API_PREFIX}/admin/operations/traces${queryString(params)}`);
    },
    ingestRuntimeSummary(event) {
      return request(`${API_PREFIX}/events/runtime-summaries`, { method: "POST", body: JSON.stringify(event) });
    },
    listQualityEvaluations(skillId = "", params = {}) {
      return request(`${API_PREFIX}/admin/quality/evaluations${queryString({ skillId, ...params })}`);
    },
    submitQualityEvaluation(input) {
      return request(`${API_PREFIX}/admin/quality/evaluations`, { method: "POST", body: JSON.stringify(input) });
    },
    executeSkill(input) {
      return request(`${API_PREFIX}/admin/runner/executions`, { method: "POST", body: JSON.stringify(input) });
    },
    getSkillExecution(executionId) {
      return request(`${API_PREFIX}/admin/runner/executions/${encodeURIComponent(executionId)}`);
    },
    listSkillExecutions(skillId = "", params = {}) {
      return request(`${API_PREFIX}/admin/runner/executions${queryString({ skillId, ...params })}`);
    },
    getQualityEvaluation(runId) {
      return request(`${API_PREFIX}/admin/quality/evaluations/${encodeURIComponent(runId)}`);
    },
    cancelQualityEvaluation(runId) {
      return request(`${API_PREFIX}/admin/quality/evaluations/${encodeURIComponent(runId)}/cancel`, { method: "POST" });
    },
    getQualitySnapshot(runId) {
      return request(`${API_PREFIX}/admin/quality/evaluations/${encodeURIComponent(runId)}/snapshot`);
    },
    getQualityEvaluationResults(runId) {
      return request(`${API_PREFIX}/admin/quality/evaluations/${encodeURIComponent(runId)}/results`);
    },
    listQualityCompatibilityMatrices(params = {}) {
      return request(`${API_PREFIX}/admin/quality/compatibility-matrices${queryString(params)}`);
    },
    createQualityCompatibilityMatrix(input) {
      return request(`${API_PREFIX}/admin/quality/compatibility-matrices`, { method: "POST", body: JSON.stringify(input) });
    },
    getQualityCompatibilityMatrix(matrixRunId) {
      return request(`${API_PREFIX}/admin/quality/compatibility-matrices/${encodeURIComponent(matrixRunId)}`);
    },
    getQualityCompatibilityMatrixCases(matrixRunId) {
      return request(`${API_PREFIX}/admin/quality/compatibility-matrices/${encodeURIComponent(matrixRunId)}/cases`);
    },
    cancelQualityCompatibilityMatrix(matrixRunId) {
      return request(`${API_PREFIX}/admin/quality/compatibility-matrices/${encodeURIComponent(matrixRunId)}/cancel`, { method: "POST" });
    },
    listQualitySuites() {
      return request(`${API_PREFIX}/admin/quality/suites`);
    },
    getSkillScope(skillId) {
      return request(`${API_PREFIX}/admin/skill-access/scopes?skillId=${encodeURIComponent(skillId)}`);
    },
    updateSkillScope(skillId, input = {}) {
      const { visibility, ownerTeamId, maintainerUserIds, revision } = input;
      return request(`${API_PREFIX}/admin/skill-access/scopes/${encodeURIComponent(skillId)}`, {
        method: "PUT",
        body: JSON.stringify({ visibility, ownerTeamId, maintainerUserIds, revision }),
      });
    },
    createQualitySuite(input) {
      return request(`${API_PREFIX}/admin/quality/suites`, { method: "POST", body: JSON.stringify(input) });
    },
    getQualityRules() {
      return request(`${API_PREFIX}/admin/quality/rules`);
    },
    listQualityProviderContracts() {
      return request(`${API_PREFIX}/admin/quality/provider-contracts`);
    },
    getQualityProviderContractVerification() {
      return request(`${API_PREFIX}/admin/quality/provider-contract-verification`);
    },
    listExecutionEnvironments(params = {}) {
      return request(`${API_PREFIX}/admin/execution-environments${queryString(params)}`);
    },
    listQualityProviders() {
      return request(`${API_PREFIX}/admin/quality/providers`);
    },
    getQualityProviderReadiness() {
      return request(`${API_PREFIX}/admin/quality/provider-readiness`);
    },
    getPackageSecurityReadiness() {
      return request(`${API_PREFIX}/admin/package-security/readiness`);
    },
    probeQualityProviders(providerId = "") {
      return request(`${API_PREFIX}/admin/quality/provider-readiness/probe${providerId ? queryString({ providerId }) : ""}`, { method: "POST" });
    },
    updateQualityRules(input) {
      return request(`${API_PREFIX}/admin/quality/rules`, { method: "PUT", body: JSON.stringify(input) });
    },
    getOptimizationSuggestionThresholds() {
      return request(`${API_PREFIX}/admin/quality/suggestion-thresholds`);
    },
    updateOptimizationSuggestionThresholds(input) {
      return request(`${API_PREFIX}/admin/quality/suggestion-thresholds`, { method: "PUT", body: JSON.stringify(input) });
    },
    listOptimizationWorkItems(params = {}) {
      return request(`${API_PREFIX}/admin/quality/optimization-work-items${queryString(params)}`);
    },
    createOptimizationWorkItem(input) {
      return request(`${API_PREFIX}/admin/quality/optimization-work-items`, { method: "POST", body: JSON.stringify(input) });
    },
    updateOptimizationWorkItemStatus(workItemId, input) {
      return request(`${API_PREFIX}/admin/quality/optimization-work-items/${encodeURIComponent(workItemId)}/status`, { method: "PATCH", body: JSON.stringify(input) });
    },
    bindOptimizationWorkItemEvidence(workItemId, input) {
      return request(`${API_PREFIX}/admin/quality/optimization-work-items/${encodeURIComponent(workItemId)}/evidence`, { method: "PUT", body: JSON.stringify(input) });
    },
    getOptimizationWorkItemHealth() {
      return request(`${API_PREFIX}/admin/quality/optimization-work-items/health`);
    },
    listOptimizationExperiments(params = {}) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments${queryString(params)}`);
    },
    createOptimizationExperiment(input) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments`, { method: "POST", body: JSON.stringify(input) });
    },
    reconcileOptimizationExperiment(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/reconcile`, { method: "POST" });
    },
    cancelOptimizationExperiment(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/cancel`, { method: "POST" });
    },
    benchmarkOptimizationExperiment(experimentId, input = {}) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/benchmark`, { method: "POST", body: JSON.stringify(input) });
    },
    decideOptimizationExperiment(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/decision`, { method: "POST" });
    },
    getOptimizationExperimentDecision(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/decision`);
    },
    listOptimizationExperimentObservations(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/observations`);
    },
    captureOptimizationExperimentObservation(experimentId, input = {}) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/observations`, {
        method: "POST", body: JSON.stringify(input),
      });
    },
    listOptimizationExperimentAssessments(experimentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/assessments`);
    },
    createOptimizationExperimentAssessment(experimentId, input) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/assessments`, {
        method: "POST", body: JSON.stringify(input),
      });
    },
    getOptimizationExperimentAssessment(experimentId, assessmentId) {
      return request(`${API_PREFIX}/admin/quality/optimization-experiments/${encodeURIComponent(experimentId)}/assessments/${encodeURIComponent(assessmentId)}`);
    },
    listQualityBenchmarks(skillId = "", params = {}) {
      const filters = typeof params === "string" ? { dataSource: params } : (params || {});
      return request(`${API_PREFIX}/admin/quality/benchmarks${queryString({ skillId, ...filters })}`);
    },
    createQualityBenchmark(input) {
      return request(`${API_PREFIX}/admin/quality/benchmarks`, { method: "POST", body: JSON.stringify(input) });
    },
    getSkillQualityBenchmarks(skillId, params = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/quality/benchmarks${queryString(params)}`);
    },
    listReleases(params = {}) {
      return request(`${API_PREFIX}/admin/releases${queryString(params)}`);
    },
    getReleaseAdmission(skillId, version) {
      return request(`${API_PREFIX}/admin/releases/admission?skillId=${encodeURIComponent(skillId)}&version=${encodeURIComponent(version)}`);
    },
    getRelease(releaseId) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}`);
    },
    createRelease(input) {
      return request(`${API_PREFIX}/admin/releases`, { method: "POST", body: JSON.stringify(input) });
    },
    approveRelease(releaseId) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}/approve`, { method: "POST" });
    },
    rejectRelease(releaseId, reason) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}/reject`, {
        method: "POST", body: JSON.stringify({ reason }),
      });
    },
    promoteRelease(releaseId) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}/promote`, { method: "POST" });
    },
    requestReleaseRollbackReview(releaseId, input) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}/rollback-review`, {
        method: "POST", body: JSON.stringify(input),
      });
    },
    rollbackRelease(releaseId) {
      return request(`${API_PREFIX}/admin/releases/${encodeURIComponent(releaseId)}/rollback`, { method: "POST" });
    },
    ingestInvocation(event) {
      return request(`${API_PREFIX}/events/invocations`, { method: "POST", body: JSON.stringify(event) });
    },
    listReviews(status = "") {
      return request(`${API_PREFIX}/admin/reviews${queryString({ status })}`);
    },
    approveReview(reviewId) {
      return request(`${API_PREFIX}/admin/reviews/${encodeURIComponent(reviewId)}/approve`, { method: "POST" });
    },
    rejectReview(reviewId, reason) {
      return request(`${API_PREFIX}/admin/reviews/${encodeURIComponent(reviewId)}/reject`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      });
    },
    listInstallations() {
      return request(`${API_PREFIX}/installations`);
    },
    getInstallation(installationId) {
      return request(`${API_PREFIX}/installations/${encodeURIComponent(installationId)}`);
    },
    consumeDistributionToken(tokenId, token) {
      return request(`${API_PREFIX}/distribution/authorizations/${encodeURIComponent(tokenId)}/consume`, {
        method: "POST",
        body: JSON.stringify({ token }),
      });
    },
    downloadArtifact(skillId, version, token) {
      const params = new URLSearchParams({ token });
      return `${API_PREFIX}/distribution/artifacts/${encodeURIComponent(skillId)}/${encodeURIComponent(version)}?${params}`;
    },
    listAudit(params = {}) {
      return request(`${API_PREFIX}/audit${queryString(params)}`);
    },
    listVersions(skillId) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/versions`);
    },
    deprecateVersion(skillId, version, input = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}/deprecate`, {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    withdrawVersion(skillId, version, input = {}) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}/withdraw`, {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    getVersionImpact(skillId, version) {
      return request(`${API_PREFIX}/skills/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}/impact`);
    },
    getSkillRelationImpact(skillId, version, params = {}) {
      const filters = queryString(params).replace("?", "&");
      return request(`${API_PREFIX}/admin/skill-relations/impact?skillId=${encodeURIComponent(skillId)}&version=${encodeURIComponent(version)}${filters}`);
    },
    listSkillRelations(params = {}) {
      return request(`${API_PREFIX}/admin/skill-relations${queryString(params)}`);
    },
    createSkillRelation(input) {
      return request(`${API_PREFIX}/admin/skill-relations`, { method: "POST", body: JSON.stringify(input) });
    },
    retireSkillRelation(relationId, reason) {
      return request(`${API_PREFIX}/admin/skill-relations/${encodeURIComponent(relationId)}/retire`, {
        method: "POST", body: JSON.stringify({ reason }),
      });
    },
    listMyInstallations(params = {}) {
      return request(`${API_PREFIX}/me/installations${queryString(params)}`);
    },
    listMySkills() {
      return request(`${API_PREFIX}/me/skills`);
    },
    listFavorites() {
      return request(`${API_PREFIX}/me/favorites`);
    },
    addFavorite(skillId) {
      return request(`${API_PREFIX}/me/favorites/${encodeURIComponent(skillId)}`, { method: "PUT" });
    },
    removeFavorite(skillId) {
      return request(`${API_PREFIX}/me/favorites/${encodeURIComponent(skillId)}`, { method: "DELETE" });
    },
    listMyInvocations(params = {}) {
      return request(`${API_PREFIX}/me/invocations${queryString(params)}`);
    },
    listNotifications() {
      return request(`${API_PREFIX}/me/notifications`);
    },
    readNotification(notificationId) {
      return request(`${API_PREFIX}/me/notifications/${encodeURIComponent(notificationId)}/read`, { method: "PUT" });
    },
    readAllNotifications() {
      return request(`${API_PREFIX}/me/notifications/read-all`, { method: "POST" });
    },
    listTeams(params = {}) {
      return request(`${API_PREFIX}/admin/teams${queryString(params)}`);
    },
    saveTeam(teamId, input) {
      const path = teamId ? `${API_PREFIX}/admin/teams/${encodeURIComponent(teamId)}` : `${API_PREFIX}/admin/teams`;
      return request(path, { method: teamId ? "PUT" : "POST", body: JSON.stringify(input) });
    },
    deactivateTeam(teamId) {
      return request(`${API_PREFIX}/admin/teams/${encodeURIComponent(teamId)}`, { method: "DELETE" });
    },
    listRoleBindings(params = {}) {
      return request(`${API_PREFIX}/admin/role-bindings${queryString(params)}`);
    },
    saveRoleBinding(userId, input) {
      return request(`${API_PREFIX}/admin/role-bindings/${encodeURIComponent(userId)}`, { method: "PUT", body: JSON.stringify(input) });
    },
    deactivateRoleBinding(userId) {
      return request(`${API_PREFIX}/admin/role-bindings/${encodeURIComponent(userId)}`, { method: "DELETE" });
    },
    listCategories(params = {}) {
      return request(`${API_PREFIX}/admin/taxonomy/categories${queryString(params)}`);
    },
    saveCategory(code, input) {
      const path = code ? `${API_PREFIX}/admin/taxonomy/categories/${encodeURIComponent(code)}` : `${API_PREFIX}/admin/taxonomy/categories`;
      return request(path, { method: code ? "PUT" : "POST", body: JSON.stringify(input) });
    },
    deactivateCategory(code) {
      return request(`${API_PREFIX}/admin/taxonomy/categories/${encodeURIComponent(code)}`, { method: "DELETE" });
    },
    listTags(params = {}) {
      return request(`${API_PREFIX}/admin/taxonomy/tags${queryString(params)}`);
    },
    saveTag(code, input) {
      const path = code ? `${API_PREFIX}/admin/taxonomy/tags/${encodeURIComponent(code)}` : `${API_PREFIX}/admin/taxonomy/tags`;
      return request(path, { method: code ? "PUT" : "POST", body: JSON.stringify(input) });
    },
    deactivateTag(code) {
      return request(`${API_PREFIX}/admin/taxonomy/tags/${encodeURIComponent(code)}`, { method: "DELETE" });
    },
    listCollections(params = {}) {
      return request(`${API_PREFIX}/admin/collections${queryString(params)}`);
    },
    saveCollection(collectionId, input) {
      const path = collectionId ? `${API_PREFIX}/admin/collections/${encodeURIComponent(collectionId)}` : `${API_PREFIX}/admin/collections`;
      return request(path, { method: collectionId ? "PUT" : "POST", body: JSON.stringify(input) });
    },
    deactivateCollection(collectionId) {
      return request(`${API_PREFIX}/admin/collections/${encodeURIComponent(collectionId)}`, { method: "DELETE" });
    },
    addCollectionSkill(collectionId, skillId) {
      return request(`${API_PREFIX}/admin/collections/${encodeURIComponent(collectionId)}/skills/${encodeURIComponent(skillId)}`, { method: "PUT" });
    },
    removeCollectionSkill(collectionId, skillId) {
      return request(`${API_PREFIX}/admin/collections/${encodeURIComponent(collectionId)}/skills/${encodeURIComponent(skillId)}`, { method: "DELETE" });
    },
    getPolicy() {
      return request(`${API_PREFIX}/admin/policies`);
    },
    savePolicy(input) {
      return request(`${API_PREFIX}/admin/policies`, { method: "PUT", body: JSON.stringify(input) });
    },
    getTaxonomy() {
      return request(`${API_PREFIX}/governance/taxonomy`);
    },
    listPublicCollections(params = {}) {
      return request(`${API_PREFIX}/collections${queryString(params)}`);
    },
    getPublicCollection(collectionId) {
      return request(`${API_PREFIX}/collections/${encodeURIComponent(collectionId)}`);
    },
    createExport(input) {
      return request(`${API_PREFIX}/admin/exports`, { method: "POST", body: JSON.stringify(input) });
    },
    listExports(params = {}) {
      return request(`${API_PREFIX}/admin/exports${queryString(params)}`);
    },
    getExport(jobId) {
      return request(`${API_PREFIX}/admin/exports/${encodeURIComponent(jobId)}`);
    },
    issueExportDownloadUrl(jobId) {
      return request(`${API_PREFIX}/admin/exports/${encodeURIComponent(jobId)}/download-url`, { method: "POST" });
    },
    retryExport(jobId) {
      return request(`${API_PREFIX}/admin/exports/${encodeURIComponent(jobId)}/retry`, { method: "POST" });
    },
    getRetentionPolicy() {
      return request(`${API_PREFIX}/admin/retention`);
    },
    updateRetentionPolicy(input) {
      return request(`${API_PREFIX}/admin/retention`, { method: "PUT", body: JSON.stringify(input) });
    },
    previewRetention() {
      return request(`${API_PREFIX}/admin/retention/preview`, { method: "POST" });
    },
    executeRetention(input) {
      return request(`${API_PREFIX}/admin/retention/execute`, { method: "POST", body: JSON.stringify(input) });
    },
  };
}
