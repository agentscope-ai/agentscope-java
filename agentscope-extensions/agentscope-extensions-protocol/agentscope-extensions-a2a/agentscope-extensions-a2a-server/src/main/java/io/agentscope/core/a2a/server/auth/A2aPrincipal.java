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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.a2aproject.sdk.server.auth.User;

/** Authenticated network caller exposed through the official A2A call context. */
public final class A2aPrincipal implements User {

    private static final A2aPrincipal ANONYMOUS = new A2aPrincipal(false, "", Map.of());

    private final boolean authenticated;
    private final String username;
    private final Map<String, Object> attributes;

    private A2aPrincipal(boolean authenticated, String username, Map<String, Object> attributes) {
        this.authenticated = authenticated;
        this.username = username;
        this.attributes = immutableMap(attributes);
    }

    public static A2aPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static A2aPrincipal authenticated(String username, Map<String, Object> attributes) {
        return new A2aPrincipal(true, requireText(username, "username"), attributes);
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public <T> Optional<T> getAttribute(String name, Class<T> type) {
        if (name == null || type == null) {
            return Optional.empty();
        }
        Object value = attributes.get(name);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
