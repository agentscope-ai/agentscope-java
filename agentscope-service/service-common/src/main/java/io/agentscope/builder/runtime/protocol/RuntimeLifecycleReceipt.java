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
package io.agentscope.builder.runtime.protocol;

import java.util.Set;

/** Confirms the desired runtime lifecycle mirror, independent from immutable configuration. */
public record RuntimeLifecycleReceipt(
        int version,
        String sessionId,
        String commandId,
        long lifecycleRevision,
        String lifecycle,
        String payloadDigest,
        String status) {
    public RuntimeLifecycleReceipt {
        RuntimeProtocol.requireVersion(version);
        RuntimeProtocol.requireId(sessionId, "sessionId");
        RuntimeProtocol.requireId(commandId, "commandId");
        RuntimeProtocol.requireId(payloadDigest, "payloadDigest");
        if (lifecycleRevision < 2
                || !Set.of("active", "archived", "deleted").contains(lifecycle)
                || !Set.of("applied", "superseded").contains(status))
            throw new IllegalArgumentException("Invalid lifecycle receipt");
    }
}
