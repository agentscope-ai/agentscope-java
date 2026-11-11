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
package io.agentscope.builder.web.managed.service;

/** Immutable control-plane fence captured before execution, never resolved during delivery. */
public record SessionEventScope(
        String tenant,
        String agentTaskId,
        String attemptId,
        long dispatchGeneration,
        String turnId,
        boolean nativeFact) {
    public SessionEventScope(
            String tenant,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId) {
        this(tenant, agentTaskId, attemptId, dispatchGeneration, turnId, false);
    }

    public SessionEventScope forNativeFact() {
        return new SessionEventScope(
                tenant, agentTaskId, attemptId, dispatchGeneration, turnId, true);
    }

    public static final SessionEventScope NONE = new SessionEventScope(null, null, null, 0, null);

    public boolean managed() {
        return agentTaskId != null
                && !agentTaskId.isBlank()
                && attemptId != null
                && !attemptId.isBlank()
                && dispatchGeneration > 0
                && turnId != null
                && !turnId.isBlank();
    }
}
