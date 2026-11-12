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
package io.agentcore.managed.server.runtime.protocol;

import java.util.Set;

/** A committed runtime interaction receipt; stopping and input application remain separate facts. */
public record RuntimeInteractionReceipt(
        int version,
        String sessionId,
        String turnId,
        String commandId,
        String kind,
        String payloadDigest,
        String status,
        String inputStatus) {
    public RuntimeInteractionReceipt {
        RuntimeProtocol.requireVersion(version);
        RuntimeProtocol.requireId(sessionId, "sessionId");
        RuntimeProtocol.requireId(commandId, "commandId");
        RuntimeProtocol.requireId(payloadDigest, "payloadDigest");
        if (!Set.of(
                        "steer",
                        "inject",
                        "cancel",
                        "resume",
                        "approval",
                        "tool_result",
                        "tool_retry",
                        "workspace_recover")
                .contains(kind))
            throw new IllegalArgumentException("Unsupported interaction receipt kind");
        if (!kind.equals("inject")) RuntimeProtocol.requireId(turnId, "turnId");
        if (!"runtime_accepted".equals(status))
            throw new IllegalArgumentException("Unsupported interaction receipt status");
        if (Set.of("steer", "inject", "tool_retry", "workspace_recover").contains(kind)) {
            if (!Set.of("accepted", "applied", "rejected").contains(inputStatus))
                throw new IllegalArgumentException("Invalid native input receipt status");
        } else if (inputStatus != null)
            throw new IllegalArgumentException("Unexpected input status");
    }
}
