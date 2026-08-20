package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/distribution/authorizations")
public class AuthorizationController {
    private final DistributionAuthorizationService service;

    public AuthorizationController(DistributionAuthorizationService service) {
        this.service = service;
    }

    @PostMapping("/{tokenId}/consume")
    ResponseEntity<ApiResponse<ConsumedAuthorization>> consume(@PathVariable String tokenId,
                                                                @RequestBody ConsumeRequest request,
                                                                HttpServletRequest servletRequest) {
        DistributionAuthorization authorization = service.consume(tokenId, request == null ? null : request.token());
        ConsumedAuthorization body = new ConsumedAuthorization(authorization.tokenId(), authorization.consumedAt());
        return ResponseEntity.ok(new ApiResponse<>(body,
                String.valueOf(servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    public record ConsumeRequest(String token) {
    }

    public record ConsumedAuthorization(String tokenId, java.time.Instant consumedAt) {
    }
}
