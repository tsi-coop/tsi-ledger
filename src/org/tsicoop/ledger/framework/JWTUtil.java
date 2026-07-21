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
 * Issues and validates the Ops Console admin session token.
 * There is exactly one token type here — the ledger has no operator/role
 * system, just a single master-key-gated admin session (see InputProcessor).
 */
public class JWTUtil {

    private static final long EXPIRATION_TIME = 36_000_000L; // 10 hours
    private static final Key SECRET_KEY = loadSecretKey();

    private static Key loadSecretKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT_SECRET environment variable must be set");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateAdminToken() {
        Map<String, String> claims = new HashMap<>();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject("admin")
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

    private static Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY).build()
                .parseClaimsJws(token).getBody();
    }
}
