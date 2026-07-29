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

/** Trusted caller-to-business-user binding created only by an authentication resolver. */
public record A2aIdentity(A2aPrincipal principal, String userId, boolean delegated) {

    public A2aIdentity {
        Objects.requireNonNull(principal, "principal");
        if (!principal.isAuthenticated()) {
            throw new IllegalArgumentException("authenticated principal is required");
        }
        userId = requireText(userId, "userId");
        if (!delegated && !principal.getUsername().equals(userId)) {
            throw new IllegalArgumentException(
                    "non-delegated userId must match principal username");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
