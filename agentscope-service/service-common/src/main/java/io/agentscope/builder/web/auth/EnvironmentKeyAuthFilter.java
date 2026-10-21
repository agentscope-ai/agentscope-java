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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Authenticates Environment Worker requests via {@code X-Builder-Environment-Key} before the JWT
 * filter chain. Applies to work-queue ops and self-hosted data-plane routes under {@code
 * /api/environments/{id}/...}.
 */
public class EnvironmentKeyAuthFilter implements WebFilter {

    public static final String ENV_KEY_HEADER = "X-Builder-Environment-Key";
    public static final String ROLE_ENV_WORKER = "ROLE_ENV_WORKER";

    private static final Pattern WORKER_OP_PATH =
            Pattern.compile(
                    "^/api/environments/([^/]+)/work/(poll|[^/]+/(ack|heartbeat|stop)|[^/]+)$");

    private static final Pattern SELF_HOSTED_SESSION_PATH =
            Pattern.compile(
                    "^/api/environments/([^/]+)/sessions/([^/]+)/(pending-tools|tool-results|skills)$");

    private final EnvironmentApiKeyVerifier apiKeyVerifier;

    public EnvironmentKeyAuthFilter(EnvironmentApiKeyVerifier apiKeyVerifier) {
        this.apiKeyVerifier = apiKeyVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        Matcher workMatcher = WORKER_OP_PATH.matcher(path);
        Matcher sessionMatcher = SELF_HOSTED_SESSION_PATH.matcher(path);
        String environmentId = null;
        boolean matched = false;
        if (workMatcher.matches()) {
            String suffix = workMatcher.group(2);
            if ("poll".equals(suffix)) {
                matched = "GET".equals(method);
            } else if (suffix.endsWith("/ack")
                    || suffix.endsWith("/heartbeat")
                    || suffix.endsWith("/stop")) {
                matched = "POST".equals(method);
            } else {
                matched = "POST".equals(method);
            }
            if (matched) {
                environmentId = workMatcher.group(1);
            }
        } else if (sessionMatcher.matches()) {
            String op = sessionMatcher.group(3);
            if ("pending-tools".equals(op) || "skills".equals(op)) {
                matched = "GET".equals(method);
            } else if ("tool-results".equals(op)) {
                matched = "POST".equals(method);
            }
            if (matched) {
                environmentId = sessionMatcher.group(1);
            }
        }

        if (!matched || environmentId == null) {
            return chain.filter(exchange);
        }

        String environmentKey = exchange.getRequest().getHeaders().getFirst(ENV_KEY_HEADER);
        if (environmentKey == null || environmentKey.isBlank()) {
            return chain.filter(exchange);
        }

        if (!matchesApiKey(environmentId, environmentKey)) {
            return chain.filter(exchange);
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        "env:" + environmentId,
                        null,
                        List.of(new SimpleGrantedAuthority(ROLE_ENV_WORKER)));
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    /** Returns true when {@code plaintextKey} matches the control-plane hash for the environment. */
    private boolean matchesApiKey(String environmentId, String plaintextKey) {
        if (environmentId == null
                || environmentId.isBlank()
                || plaintextKey == null
                || plaintextKey.isBlank()) {
            return false;
        }
        try {
            return apiKeyVerifier.matches(environmentId, plaintextKey);
        } catch (Exception ex) {
            return false;
        }
    }
}
