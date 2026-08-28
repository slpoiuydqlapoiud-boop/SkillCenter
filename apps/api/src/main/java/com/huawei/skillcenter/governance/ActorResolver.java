package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Component
public class ActorResolver {
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    private static final Set<String> ALLOWED_ROLES = Set.of("developer", "admin", "viewer", "maintainer", "reviewer");
    public enum Mode { LOCAL, JWT }

    private final Mode mode;
    private final ActorTokenVerifier tokenVerifier;

    @org.springframework.beans.factory.annotation.Autowired
    public ActorResolver(ActorAuthenticationProperties properties) {
        this(resolveMode(properties.getMode()), verifierFor(properties));
    }

    public ActorResolver() {
        this(Mode.LOCAL, token -> {
            throw new ForbiddenException("Bearer token required");
        });
    }

    public ActorResolver(Mode mode, ActorTokenVerifier tokenVerifier) {
        this.mode = mode == null ? Mode.LOCAL : mode;
        this.tokenVerifier = tokenVerifier;
    }

    public Actor resolve(HttpServletRequest request) {
        if (mode == Mode.JWT) {
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new ForbiddenException("Bearer token required");
            }
            String token = authorization.substring(7).trim();
            if (token.isBlank()) {
                throw new ForbiddenException("Bearer token required");
            }
            return tokenVerifier.verify(token);
        }
        String userId = headerOrDefault(request.getHeader(USER_ID_HEADER), "local-user");
        String role = headerOrDefault(request.getHeader(ROLE_HEADER), "admin").toLowerCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(role)) {
            throw new ForbiddenException("Unknown actor role");
        }
        return new Actor(userId, role);
    }

    private static Mode resolveMode(String value) {
        try {
            return Mode.valueOf(value == null ? "LOCAL" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported actor authentication mode");
        }
    }

    private static ActorTokenVerifier jwtVerifier(ActorAuthenticationProperties.JwtProperties properties) {
        String publicKey = properties.getPublicKey();
        String jwksUri = properties.getJwksUri();
        if (!publicKey.isBlank() && !jwksUri.isBlank()) {
            throw new IllegalStateException("JWT verification sources are mutually exclusive");
        }
        Clock clock = Clock.systemUTC();
        if (!jwksUri.isBlank()) {
            JwksKeySetProvider provider = new JwksKeySetProvider(URI.create(jwksUri),
                    Duration.ofMillis(properties.getConnectTimeoutMs()),
                    Duration.ofMillis(properties.getRequestTimeoutMs()),
                    Duration.ofSeconds(properties.getCacheTtlSeconds()),
                    properties.getMaxResponseBytes(), clock);
            return new JwksActorTokenVerifier(provider, properties.getIssuer(), properties.getAudience(),
                    properties.getClockSkewSeconds(), clock, properties.getTeamClaim(),
                    properties.isTeamClaimRequired());
        }
        if (publicKey.isBlank()) {
            throw new IllegalStateException("JWT verification source must be configured");
        }
        return new JwtActorTokenVerifier(publicKey, properties.getIssuer(), properties.getAudience(),
                properties.getClockSkewSeconds(), clock, properties.getTeamClaim(),
                properties.isTeamClaimRequired());
    }

    private static ActorTokenVerifier verifierFor(ActorAuthenticationProperties properties) {
        if (resolveMode(properties.getMode()) == Mode.JWT) {
            return jwtVerifier(properties.getJwt());
        }
        return token -> {
            throw new ForbiddenException("Bearer token required");
        };
    }

    private String headerOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
