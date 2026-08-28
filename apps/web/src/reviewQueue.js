export function reviewIdOf(review) {
  return String(review?.reviewId ?? review?.id ?? "");
}

export function pendingReviewCount(reviews = []) {
  return reviews.filter((review) => ["pending_review", "security_review"].includes(review?.status)).length;
}

export function removeReviewById(reviews = [], reviewId) {
  const target = String(reviewId ?? "");
  return reviews.filter((review) => reviewIdOf(review) !== target);
}

export function securityEvidenceLabel(review) {
  const evidence = review?.securityEvidence;
  const status = evidence?.status;
  if (status === "PASSED") {
    const scannerId = String(evidence.scannerId ?? "").trim();
    const scannerVersion = String(evidence.scannerVersion ?? "").trim();
    const provenance = scannerId ? ` · ${scannerId}${scannerVersion ? ` v${scannerVersion}` : ""}` : "";
    return `安全扫描：已通过${provenance}`;
  }
  if (status === "BLOCKED") {
    const findingCount = Array.isArray(evidence.findings) ? evidence.findings.length : 0;
    return `安全扫描：已阻断 · ${findingCount} 个发现`;
  }
  return "安全扫描：历史未提供";
}
