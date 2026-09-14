package com.huawei.skillcenter.governance;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Small in-process account/token service for the single-node department deployment. */
@Component
public class LocalAuthenticationService {
    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HASH_PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Account> accounts;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final boolean allowGuest;
    private final String guestUserId;
    private final String guestRole;
    private final long tokenTtlSeconds;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalAuthenticationService(ActorAuthenticationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public LocalAuthenticationService(ActorAuthenticationProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        ActorAuthenticationProperties.LocalProperties local = properties.getLocal();
        this.tokenTtlSeconds = local.getTokenTtlSeconds();
        this.allowGuest = local.isAllowGuest();
        this.guestUserId = local.getGuestUserId();
        this.guestRole = normalizeRole(local.getGuestRole());
        this.accounts = configuredAccounts(local);
    }

    public Session authenticate(String username, String password) {
        Account account = username == null ? null : accounts.get(username.trim());
        if (account == null || !matches(password, account.passwordHash())) {
            throw new ForbiddenException("Invalid local credentials");
        }
        return issue(new Actor(account.username(), account.role()));
    }

    public Session guest() {
        if (!allowGuest) throw new ForbiddenException("Guest access is disabled");
        return issue(new Actor(guestUserId, guestRole));
    }

    public Optional<Actor> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session session = sessions.get(token.trim());
        if (session == null) return Optional.empty();
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(token.trim(), session);
            return Optional.empty();
        }
        return Optional.of(session.actor());
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token.trim());
    }

    private Session issue(Actor actor) {
        Instant expiresAt = clock.instant().plusSeconds(tokenTtlSeconds);
        String token = randomToken();
        Session session = new Session(token, actor, expiresAt);
        sessions.put(token, session);
        return session;
    }

    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("password is required");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return formatHash(salt, derive(password.toCharArray(), salt, ITERATIONS));
    }

    private Map<String, Account> configuredAccounts(ActorAuthenticationProperties.LocalProperties local) {
        Map<String, Account> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, ActorAuthenticationProperties.LocalAccount> entry : local.getAccounts().entrySet()) {
            String username = entry.getKey() == null ? "" : entry.getKey().trim();
            ActorAuthenticationProperties.LocalAccount configured = entry.getValue();
            if (username.isBlank() || configured == null) continue;
            result.put(username, account(username, configured.getPasswordHash(), configured.getPassword(), configured.getRole()));
        }
        if (result.isEmpty() && !local.getUsername().isBlank()
                && (!local.getPasswordHash().isBlank() || !local.getPassword().isBlank())) {
            result.put(local.getUsername(), account(local.getUsername(), local.getPasswordHash(), local.getPassword(), local.getRole()));
        }
        return Map.copyOf(result);
    }

    private Account account(String username, String passwordHash, String password, String configuredRole) {
        String hash = passwordHash == null ? "" : passwordHash.trim();
        if (hash.isBlank()) hash = hashPassword(password);
        validateHash(hash);
        return new Account(username, normalizeRole(configuredRole), hash);
    }

    private static String normalizeRole(String configuredRole) {
        return switch ((configuredRole == null ? "MEMBER" : configuredRole).trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> "admin";
            case "VIEWER" -> "viewer";
            case "MEMBER", "DEVELOPER" -> "developer";
            case "MAINTAINER" -> "maintainer";
            case "REVIEWER" -> "reviewer";
            default -> throw new IllegalArgumentException("local account role must be ADMIN, MEMBER or VIEWER");
        };
    }

    private static boolean matches(String password, String encoded) {
        if (password == null || encoded == null) return false;
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password.toCharArray(), salt, iterations));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void validateHash(String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) throw new IllegalArgumentException();
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] derived = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < 8 || derived.length != KEY_BITS / 8) throw new IllegalArgumentException();
            derive("validation-only".toCharArray(), salt, iterations);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("local password hash must use pbkdf2-sha256 format");
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        if (iterations < 10_000 || iterations > 1_000_000) throw new IllegalArgumentException("invalid PBKDF2 iterations");
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("local password hashing unavailable", exception);
        }
    }

    private static String formatHash(byte[] salt, byte[] derived) {
        return HASH_PREFIX + "$" + ITERATIONS + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    private String randomToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public record Session(String token, Actor actor, Instant expiresAt) {
    }

    private record Account(String username, String role, String passwordHash) {
    }
}
