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
package io.agentscope.core.agent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Persistent Agent context within a business Session; unrelated to a JVM thread. */
public record ThreadKey(String userId, String agentId, String sessionId, String threadId) {
    public ThreadKey {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(threadId, "threadId");
        if (agentId.isBlank() || sessionId.isBlank() || threadId.isBlank())
            throw new IllegalArgumentException("Empty thread identity");
        if (userId != null && userId.isBlank()) userId = null;
    }

    public String storagePath() {
        String sessionPath =
                "agents/"
                        + encode(agentId)
                        + "/sessions/"
                        + encode(userId == null ? "" : userId)
                        + "/"
                        + encode(sessionId);
        return sessionPath + "/threads/" + encode(threadId);
    }

    /**
     * Component address within an AgentStateStore's user/session bucket. The shared store SPI
     * remains a generic key/value store; all Agent and Thread addressing is defined here.
     */
    public String stateEntry(String component) {
        Objects.requireNonNull(component, "component");
        if (component.isBlank()) throw new IllegalArgumentException("Empty state component");
        // The original single-Thread state contract stored this component directly in the
        // user/session bucket. Keep that address for main so old and new runtimes can alternate.
        return isMain()
                ? component
                : "thread." + encode(agentId) + "." + encode(threadId) + "." + encode(component);
    }

    public boolean isMain() {
        return "main".equals(threadId);
    }

    private static String encode(String value) {
        return "s_"
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
