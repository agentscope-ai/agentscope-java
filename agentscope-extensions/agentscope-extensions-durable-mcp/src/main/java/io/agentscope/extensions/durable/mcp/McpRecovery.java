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

package io.agentscope.extensions.durable.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;

/** Explicit opt-in contract advertised in a tool descriptor's _meta, never inferred from MCP alone. */
public record McpRecovery(boolean idempotentStart, String queryTool, String cancelTool) {
    public static final String META_KEY = "io.agentscope.durable/execution";
    public static final int VERSION = 1;

    public McpRecovery {
        DurableJson.text(queryTool, "MCP execution query tool");
        if (cancelTool != null) {
            DurableJson.text(cancelTool, "MCP execution cancel tool");
            if (cancelTool.equals(queryTool))
                throw new IllegalArgumentException("Query and cancellation tools must differ");
        }
    }

    public static McpRecovery advertised(JsonNode descriptor) {
        var meta = descriptor.path("_meta").path(META_KEY);
        if (meta.isMissingNode()) return null;
        DurableProtocol.validate("mcpRecoveryContract", meta);
        var recovery =
                new McpRecovery(
                        meta.path("idempotentStart").asBoolean(),
                        meta.path("queryTool").asText(),
                        meta.hasNonNull("cancelTool") ? meta.path("cancelTool").asText() : null);
        if (recovery.queryTool().equals(descriptor.path("name").asText())
                || descriptor.path("name").asText().equals(recovery.cancelTool()))
            throw new IllegalArgumentException("Recovery cannot call the execution tool");
        return recovery;
    }
}
