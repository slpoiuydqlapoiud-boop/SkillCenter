package com.huawei.skillcenter.governance;

import java.security.PublicKey;
import java.time.Clock;

/** Verifies RS256 bearer tokens against a bounded, configured JWKS key set. */
public final class JwksActorTokenVerifier implements ActorTokenVerifier {
    private final JwksKeySetProvider keys;
    private final String issuer;
    private final String audience;
    private final long clockSkewSeconds;
    private final Clock clock;
    private final String teamClaim;
    private final boolean teamClaimRequired;

    public JwksActorTokenVerifier(JwksKeySetProvider keys, String issuer, String audience,
                                  long clockSkewSeconds, Clock clock) {
        this(keys, issuer, audience, clockSkewSeconds, clock, "", false);
    }

    public JwksActorTokenVerifier(JwksKeySetProvider keys, String issuer, String audience,
                                  long clockSkewSeconds, Clock clock, String teamClaim,
                                  boolean teamClaimRequired) {
        if (keys == null) throw new IllegalArgumentException("JWKS provider must be configured");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 300) {
            throw new IllegalArgumentException("JWT clock skew must be between 0 and 300 seconds");
        }
        this.keys = keys;
        this.issuer = clean(issuer);
        this.audience = clean(audience);
        this.clockSkewSeconds = clockSkewSeconds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.teamClaim = JwtActorTokenVerifier.normalizeTeamClaim(teamClaim);
        this.teamClaimRequired = teamClaimRequired;
    }

    @Override
    public Actor verify(String token) {
        try {
            String kid = JwtActorTokenVerifier.keyId(token);
            PublicKey key = keys.find(kid).orElseThrow(JwtActorTokenVerifier::invalidToken);
            return JwtActorTokenVerifier.verifyWithKey(token, key, issuer, audience, clockSkewSeconds, clock,
                    teamClaim, teamClaimRequired);
        } catch (ForbiddenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw JwtActorTokenVerifier.invalidToken();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
