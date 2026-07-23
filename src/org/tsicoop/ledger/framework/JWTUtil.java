package org.tsicoop.ledger.framework;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates the Ops Console session token. One token type,
 * carrying the logged-in operator's identity/role (see InputProcessor.
 * resolveConsoleSession) — replaces the old single shared-admin-key session.
 */
public class JWTUtil {

    private static final long EXPIRATION_TIME = 36_000_000L; // 10 hours
    private static final Key SECRET_KEY = loadSecretKey();

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";

    private static Key loadSecretKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT_SECRET environment variable must be set");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateUserToken(UUID userId, String email, String role) {
        Map<String, String> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId.toString());
        claims.put(CLAIM_EMAIL, email);
        claims.put(CLAIM_ROLE, role);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId.toString())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(SECRET_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String getClaim(String token, String claimName) {
        try {
            return parseClaims(token).get(claimName, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY).build()
                .parseClaimsJws(token).getBody();
    }
}
