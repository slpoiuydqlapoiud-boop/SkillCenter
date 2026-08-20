package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/reviews")
public class ReviewController {
    private final ReviewService reviewService;
    private final ActorResolver actorResolver;

    public ReviewController(ReviewService reviewService, ActorResolver actorResolver) {
        this.reviewService = reviewService;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ReviewTask>>> list(@RequestParam(required = false) String status,
                                                        HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(reviewService.list(status, actor), requestId(request)));
    }

    @PostMapping("/{reviewId}/approve")
    ResponseEntity<ApiResponse<ReviewTask>> approve(@PathVariable String reviewId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        ReviewTask review = reviewService.approve(reviewId, actor, requestId(request));
        return ResponseEntity.ok(new ApiResponse<>(review, requestId(request)));
    }

    @PostMapping("/{reviewId}/reject")
    ResponseEntity<ApiResponse<ReviewTask>> reject(@PathVariable String reviewId,
                                                     @RequestBody RejectRequest body,
                                                     HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        ReviewTask review = reviewService.reject(reviewId, actor, requestId(request), body == null ? null : body.reason());
        return ResponseEntity.ok(new ApiResponse<>(review, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record RejectRequest(String reason) {
    }
}
