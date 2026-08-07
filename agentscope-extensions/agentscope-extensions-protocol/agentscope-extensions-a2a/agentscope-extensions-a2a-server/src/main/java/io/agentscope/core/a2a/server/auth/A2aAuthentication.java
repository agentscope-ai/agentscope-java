/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.a2a.server.auth;

import java.util.Objects;

/** Result of authenticating an inbound A2A request. */
public final class A2aAuthentication {

    private static final A2aAuthentication ANONYMOUS =
            new A2aAuthentication(A2aPrincipal.anonymous(), null, false);

    private final A2aPrincipal principal;
    private final String userId;
    private final boolean delegated;

    private A2aAuthentication(A2aPrincipal principal, String userId, boolean delegated) {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.userId = normalize(userId);
        this.delegated = delegated;
    }

    public static A2aAuthentication anonymous() {
        return ANONYMOUS;
    }

    public static A2aAuthentication authenticated(A2aPrincipal principal) {
        requireAuthenticated(principal);
        return new A2aAuthentication(principal, principal.getUsername(), false);
    }

    public static A2aAuthentication delegated(A2aPrincipal principal, String effectiveUserId) {
        requireAuthenticated(principal);
        String userId = normalize(effectiveUserId);
        if (userId == null) {
            throw new IllegalArgumentException("effectiveUserId must not be blank");
        }
        return new A2aAuthentication(principal, userId, true);
    }

    public A2aPrincipal getPrincipal() {
        return principal;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isDelegated() {
        return delegated;
    }

    public A2aIdentity toIdentity() {
        return principal.isAuthenticated() ? new A2aIdentity(principal, userId, delegated) : null;
    }

    private static void requireAuthenticated(A2aPrincipal principal) {
        if (principal == null || !principal.isAuthenticated()) {
            throw new IllegalArgumentException("authenticated principal is required");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
