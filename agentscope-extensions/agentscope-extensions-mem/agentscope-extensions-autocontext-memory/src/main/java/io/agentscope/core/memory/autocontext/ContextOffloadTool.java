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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/** Tool for reloading offloaded context. */
public class ContextOffloadTool {

    private final ContextOffLoader contextOffLoader;
    private final AutoContextHook hook;

    public ContextOffloadTool(ContextOffLoader contextOffLoader) {
        this.contextOffLoader = contextOffLoader;
        this.hook = null;
    }

    ContextOffloadTool(AutoContextHook hook) {
        this.contextOffLoader = null;
        this.hook = hook;
    }

    public List<Msg> reload(String uuid) {
        return reloadInternal(uuid, null, null);
    }

    @Tool(
            name = "context_reload",
            description =
                    "Reload previously offloaded context messages by UUID. Use this tool when you"
                        + " need to access the original tool invocation history that was compressed"
                        + " and stored.")
    public List<Msg> reload(
            @ToolParam(
                            name = "working_context_offload_uuid",
                            description = "The UUID of the offloaded context to reload.")
                    String uuid,
            Agent agent,
            RuntimeContext runtimeContext) {
        return reloadInternal(uuid, agent, runtimeContext);
    }

    private List<Msg> reloadInternal(String uuid, Agent agent, RuntimeContext runtimeContext) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return List.of(errorMsg("Error: UUID cannot be null or empty."));
        }

        List<Msg> messages;
        if (hook != null) {
            messages = hook.reload(uuid, agent, runtimeContext);
        } else if (contextOffLoader != null) {
            try {
                messages = contextOffLoader.reload(uuid);
            } catch (Exception e) {
                return List.of(
                        errorMsg(
                                "Error reloading context with UUID "
                                        + uuid
                                        + ": "
                                        + e.getMessage()));
            }
        } else {
            return List.of(
                    errorMsg(
                            "Error: Context offloader is not available. Cannot reload context with"
                                    + " UUID: "
                                    + uuid));
        }

        if (messages == null || messages.isEmpty()) {
            return List.of(
                    errorMsg(
                            "No messages found for UUID: "
                                    + uuid
                                    + ", The context may have been cleared or the UUID is"
                                    + " invalid."));
        }
        return messages;
    }

    private Msg errorMsg(String text) {
        return Msg.builder().content(TextBlock.builder().text(text).build()).build();
    }
}
