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

import java.util.Map;

/** Caller identity is supplied only by authenticated services; it is never trusted public input. */
public record RuntimeRequestContext(
        int version,
        String tenant,
        String namespace,
        String applicationId,
        String sessionId,
        String turnId,
        String commandId,
        String traceId,
        Map<String, Object> actor,
        String policyVersion,
        long permissionExpiresAt) {
    public RuntimeRequestContext {
        RuntimeProtocol.requireVersion(version);
        RuntimeProtocol.requireId(tenant, "tenant");
        RuntimeProtocol.requireId(namespace, "namespace");
        RuntimeProtocol.requireId(sessionId, "sessionId");
        RuntimeProtocol.requireId(commandId, "commandId");
        RuntimeProtocol.requireId(traceId, "traceId");
        actor = RuntimeProtocol.freeze(actor);
    }
}
