export function reviewIdOf(review) {
  return String(review?.reviewId ?? review?.id ?? "");
}

export function pendingReviewCount(reviews = []) {
  return reviews.filter((review) => review?.status === "pending_review").length;
}

export function removeReviewById(reviews = [], reviewId) {
  const target = String(reviewId ?? "");
  return reviews.filter((review) => reviewIdOf(review) !== target);
}
