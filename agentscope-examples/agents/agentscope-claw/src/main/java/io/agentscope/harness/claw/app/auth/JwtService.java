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
package io.agentscope.harness.claw.app.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * HS256 JWT service for the claw application.
 *
 * <p>Tokens contain:
 * <ul>
 *   <li>{@code sub} — {@code userId} (stable identity key for HarnessAgent namespace)
 *   <li>{@code username} — display name
 *   <li>{@code roles} — list of role strings
 * </ul>
 *
 * <p>The signing secret is read from {@code claw.jwt.secret} and must be overridden in production.
 * The secret is shared with agentscope-claw-web so admin users can authenticate to both services
 * using a single token.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final long TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1_000L; // 7 days

    /**
     * Default value bundled in {@code application.yml}. Mirrored here so we can detect that the
     * operator never overrode it and refuse to boot outside dev profiles.
     */
    static final String DEFAULT_DEV_SECRET = "claw-default-dev-secret-change-in-production-32chars";

    private final SecretKey signingKey;

    public JwtService(
            @Value("${claw.jwt.secret:" + DEFAULT_DEV_SECRET + "}") String secret,
            Environment env) {
        validate(secret, env);
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private static void validate(String secret, Environment env) {
        boolean isDefault = DEFAULT_DEV_SECRET.equals(secret);
        if (!isDefault) return;
        boolean isDevProfile = false;
        for (String p : env.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p)) {
                isDevProfile = true;
                break;
            }
        }
        if (env.getActiveProfiles().length == 0) isDevProfile = true; // no profile = dev default
        if (isDevProfile) {
            log.warn(
                    "claw.jwt.secret is using the bundled default. This is acceptable for"
                            + " development but MUST be overridden in production. Set"
                            + " -Dclaw.jwt.secret=<at-least-32-char-random-secret>.");
            return;
        }
        throw new IllegalStateException(
                "claw.jwt.secret is still the bundled development default. Refusing to start with"
                        + " active profiles "
                        + java.util.Arrays.toString(env.getActiveProfiles())
                        + ". Set -Dclaw.jwt.secret=<at-least-32-char-random-secret> or activate the"
                        + " 'dev' profile.");
    }

    /** Generates a signed JWT for the given user. */
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
