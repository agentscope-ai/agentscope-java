/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HS256 JWT service for the claw web application.
 *
 * <p>Tokens contain the following claims:
 * <ul>
 *   <li>{@code sub} — {@code userId} (stable identity key for HarnessAgent namespace)
 *   <li>{@code username} — display name
 *   <li>{@code roles} — list of role strings
 * </ul>
 *
 * <p>The signing secret is read from {@code builder.jwt.secret} (with {@code claw.jwt.secret} as a
 * legacy fallback). Defaults to a development-only placeholder — <strong>must be overridden in
 * production</strong>.
 */
@Service
public class JwtService {

    private static final long TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1_000L; // 7 days

    private final SecretKey signingKey;

    public JwtService(
            @Value(
                            "${builder.jwt.secret:${claw.jwt.secret:builder-default-dev-secret-change-in-production-32chars}}")
                    String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // JJWT requires >= 32 bytes for HS256
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT for the given user.
     */
    public String generate(String userId, String username, List<String> roles) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TOKEN_TTL_MS))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and validates a JWT, returning its claims.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Extracts the {@code userId} (subject) from a token without full validation (caller must
     * call {@link #parse} for secure extraction).
     */
    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
