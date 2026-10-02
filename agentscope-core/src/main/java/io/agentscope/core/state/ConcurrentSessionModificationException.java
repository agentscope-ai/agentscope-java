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
package io.agentscope.core.state;

/**
 * Thrown when an optimistic-concurrency save fails because another writer changed the same
 * session slot since it was loaded.
 */
public class ConcurrentSessionModificationException extends RuntimeException {

    private final String userId;
    private final String sessionId;
    private final String key;
    private final long expectedVersion;

    public ConcurrentSessionModificationException(
            String userId, String sessionId, String key, long expectedVersion) {
        super(
                "Concurrent modification of session state"
                        + " (userId="
                        + userId
                        + ", sessionId="
                        + sessionId
                        + ", key="
                        + key
                        + ", expectedVersion="
                        + expectedVersion
                        + ")");
        this.userId = userId;
        this.sessionId = sessionId;
        this.key = key;
        this.expectedVersion = expectedVersion;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getKey() {
        return key;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}
