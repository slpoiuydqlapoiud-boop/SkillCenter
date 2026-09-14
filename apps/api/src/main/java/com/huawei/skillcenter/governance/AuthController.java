package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final LocalAuthenticationService authenticationService;

    public AuthController(LocalAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (request == null) throw new ForbiddenException("Invalid local credentials");
        LocalAuthenticationService.Session session = authenticationService.authenticate(request.username(), request.password());
        return ResponseEntity.ok(new ApiResponse<>(new LoginResponse(session.token(), session.actor(), session.expiresAt()), requestId(httpRequest)));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        String authorization = httpRequest.getHeader(ActorResolver.AUTHORIZATION_HEADER);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authenticationService.logout(authorization.substring(7).trim());
        }
        return ResponseEntity.ok(new ApiResponse<>(null, requestId(httpRequest)));
    }

    @PostMapping("/guest")
    ResponseEntity<ApiResponse<LoginResponse>> guest(HttpServletRequest httpRequest) {
        LocalAuthenticationService.Session session = authenticationService.guest();
        return ResponseEntity.ok(new ApiResponse<>(new LoginResponse(session.token(), session.actor(), session.expiresAt()), requestId(httpRequest)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, Actor actor, java.time.Instant expiresAt) {
    }
}
