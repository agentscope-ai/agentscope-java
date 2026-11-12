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
package io.agentscope.dataagent.web.auth;

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
 * HS256 JWT service for the DataAgent web application.
 *
 * <p>Tokens contain the following claims:
 * <ul>
 *   <li>{@code sub} — {@code userId} (stable identity key for HarnessAgent namespace)
 *   <li>{@code username} — display name
 *   <li>{@code roles} — list of role strings
 * </ul>
 *
 * <p>The signing secret is read from {@code dataagent.jwt.secret}. A deployment-specific random
 * secret of at least 32 bytes is required in every profile.
 */
@Service
public class JwtService {

    private static final long TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1_000L; // 7 days

    private final SecretKey signingKey;

    public JwtService(@Value("${dataagent.jwt.secret:}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // Reject the former public default shipped by previous versions; it is publicly known.
        if (secret.isBlank()
                || secret.equals("dataagent-default-dev-secret-change-in-production-32chars")
                || keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "dataagent.jwt.secret must be a deployment-specific random secret of at least"
                            + " 32 bytes, not the published default; set DATAAGENT_JWT_SECRET");
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
