/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Root Session state maintenance; refreshed transport grants do not change its identity. */
public record RuntimeCheckpointCommand(
        RuntimeRequestContext source,
        RuntimeRequestContext target,
        String checkpointId,
        String reason) {
    public RuntimeCheckpointCommand {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        if (!source.commandId().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Checkpoint command ID must be a digest");
        if (!source.commandId().equals(target.commandId())
                || source.turnId() != null
                || target.turnId() != null)
            throw new IllegalArgumentException("Invalid checkpoint scope");
        for (var context : new RuntimeRequestContext[] {source, target})
            if (!Boolean.FALSE.equals(context.actor().get("internal"))
                    || !(context.actor().get("user") instanceof String user)
                    || user.isBlank()
                    || !(context.actor().get("key_hash") instanceof String))
                throw new IllegalArgumentException("Original caller is required");
        if (checkpointId == null
                || !checkpointId.startsWith("cp1.")
                || checkpointId.length() > 2048)
            throw new IllegalArgumentException("Invalid checkpoint handle");
        if (reason == null
                || reason.isBlank()
                || reason.length() > 1024
                || reason.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("Reason must contain 1..1024 characters");
    }

    public String digest(ObjectMapper mapper) {
        return RuntimePayloadDigest.of(
                mapper,
                Map.of(
                        "source",
                        identity(source),
                        "target",
                        identity(target),
                        "checkpoint_id",
                        checkpointId,
                        "reason",
                        reason));
    }

    private static Map<String, Object> identity(RuntimeRequestContext context) {
        var content = new LinkedHashMap<String, Object>();
        content.put("tenant", context.tenant());
        content.put("namespace", context.namespace());
        content.put("application", context.applicationId());
        content.put("session", context.sessionId());
        content.put("actor", context.actor());
        content.put("command", context.commandId());
        return content;
    }
}
