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

/** Dataplane receipt for a committed Inbox record; this never claims execution has started. */
public record RuntimeCommandReceipt(
        int version,
        String sessionId,
        String turnId,
        String commandId,
        String inputDigest,
        long admissionSequence,
        String status) {
    public RuntimeCommandReceipt {
        RuntimeProtocol.requireVersion(version);
        RuntimeProtocol.requireId(sessionId, "sessionId");
        RuntimeProtocol.requireId(turnId, "turnId");
        RuntimeProtocol.requireId(commandId, "commandId");
        RuntimeProtocol.requireId(inputDigest, "inputDigest");
        if (admissionSequence <= 0)
            throw new IllegalArgumentException("admissionSequence must be positive");
        if (!"runtime_accepted".equals(status))
            throw new IllegalArgumentException("Unsupported command receipt status");
    }
}
