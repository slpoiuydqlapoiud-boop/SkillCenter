import test from "node:test";
import assert from "node:assert/strict";
import { pendingReviewCount, removeReviewById, reviewIdOf, securityEvidenceLabel } from "../src/reviewQueue.js";

test("pending review count only includes pending_review tasks", () => {
  assert.equal(pendingReviewCount([
    { reviewId: "pending-1", status: "pending_review" },
    { reviewId: "security-1", status: "security_review" },
    { reviewId: "approved-1", status: "approved" },
    { reviewId: "pending-2", status: "pending_review" },
    { reviewId: "rejected-1", status: "rejected" },
  ]), 3);
});

test("review removal handles API reviewId and legacy id shapes", () => {
  const reviews = [{ reviewId: "review-1" }, { id: "review-2" }, { reviewId: "review-3" }];
  assert.equal(reviewIdOf(reviews[0]), "review-1");
  assert.equal(reviewIdOf(reviews[1]), "review-2");
  assert.deepEqual(removeReviewById(reviews, "review-2"), [{ reviewId: "review-1" }, { reviewId: "review-3" }]);
});

test("review queue summarizes persisted security evidence without exposing content", () => {
  assert.equal(securityEvidenceLabel({ securityEvidence: {
    status: "PASSED", scannerId: "local-package-security", scannerVersion: "1", findings: [],
  } }), "安全扫描：已通过 · local-package-security v1");
  assert.equal(securityEvidenceLabel({ securityEvidence: {
    status: "BLOCKED", scannerId: "local-package-security", scannerVersion: "1", findings: [{ code: "SECRET_PATTERN" }],
  } }), "安全扫描：已阻断 · 1 个发现");
  assert.equal(securityEvidenceLabel({ securityEvidence: { status: "NOT_SCANNED", findings: [] } }), "安全扫描：历史未提供");
});
