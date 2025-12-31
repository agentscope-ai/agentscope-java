/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import java.util.Objects;

/**
 * Default simple session identifier implementation using a single string.
 *
 * <p>This is the recommended session key for most use cases where a simple string identifier is
 * sufficient.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * SessionKey sessionKey = SimpleSessionKey.of("user_123");
 * agent.loadIfExists(session, sessionKey);
 * agent.saveTo(session, sessionKey);
 * }</pre>
 *
 * @param sessionId the unique session identifier string
 * @see SessionKey
 */
public record SimpleSessionKey(String sessionId) implements SessionKey {

    /**
     * Creates a SimpleSessionKey with validation.
     *
     * @param sessionId the session identifier (must not be null or blank)
     */
    public SimpleSessionKey {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    /**
     * Factory method to create a SimpleSessionKey.
     *
     * @param sessionId the session identifier
     * @return a new SimpleSessionKey instance
     */
    public static SimpleSessionKey of(String sessionId) {
        return new SimpleSessionKey(sessionId);
    }

    @Override
    public String toIdentifier() {
        return sessionId;
    }

    @Override
    public String toString() {
        return sessionId;
    }
}
