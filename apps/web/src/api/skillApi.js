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
    uploadPackage(file) {
      const body = new FormData();
      body.append("file", file);
      return request(`${API_PREFIX}/skill-packages`, { method: "POST", body });
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
    ingestInvocation(event) {
      return request(`${API_PREFIX}/events/invocations`, { method: "POST", body: JSON.stringify(event) });
    },
    listReviews(status = "pending_review") {
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
