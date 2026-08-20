import test from "node:test";
import assert from "node:assert/strict";
import { pendingReviewCount, removeReviewById, reviewIdOf } from "../src/reviewQueue.js";

test("pending review count only includes pending_review tasks", () => {
  assert.equal(pendingReviewCount([
    { reviewId: "pending-1", status: "pending_review" },
    { reviewId: "approved-1", status: "approved" },
    { reviewId: "pending-2", status: "pending_review" },
    { reviewId: "rejected-1", status: "rejected" },
  ]), 2);
});

test("review removal handles API reviewId and legacy id shapes", () => {
  const reviews = [{ reviewId: "review-1" }, { id: "review-2" }, { reviewId: "review-3" }];
  assert.equal(reviewIdOf(reviews[0]), "review-1");
  assert.equal(reviewIdOf(reviews[1]), "review-2");
  assert.deepEqual(removeReviewById(reviews, "review-2"), [{ reviewId: "review-1" }, { reviewId: "review-3" }]);
});
