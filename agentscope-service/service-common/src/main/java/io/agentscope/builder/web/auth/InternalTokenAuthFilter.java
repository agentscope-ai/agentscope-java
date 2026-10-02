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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Authenticates cross-plane internal calls via a shared static token carried in {@code
 * X-Builder-Internal-Token}. Used on control-plane {@code /api/internal/**} routes and on
 * data-plane endpoints invoked by the control plane or scheduler (never by browsers). The acting
 * owner is carried in {@code X-Builder-Internal-User} and exposed as the authentication principal
 * so the receiving plane can attribute the operation without a user JWT.
 *
 * <p>The filter is a no-op when the configured token is blank (internal endpoints stay closed:
 * without an authentication the security chain rejects them with 401) or when the presented token
 * does not match — in both cases the request continues unauthenticated.
 */
public class InternalTokenAuthFilter implements WebFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Builder-Internal-Token";
    public static final String INTERNAL_USER_HEADER = "X-Builder-Internal-User";
    public static final String ROLE_INTERNAL = "ROLE_INTERNAL";

    private final String internalToken;

    public InternalTokenAuthFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String presented = exchange.getRequest().getHeaders().getFirst(INTERNAL_TOKEN_HEADER);
        if (internalToken == null
                || internalToken.isBlank()
                || presented == null
                || !constantTimeEquals(internalToken, presented)) {
            return chain.filter(exchange);
        }
        String user = exchange.getRequest().getHeaders().getFirst(INTERNAL_USER_HEADER);
        String principal = user != null && !user.isBlank() ? user : "internal";
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(ROLE_INTERNAL)));
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
