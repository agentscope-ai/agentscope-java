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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/**
 * Tool for reloading offloaded context messages.
 *
 * <p>This tool allows agents to retrieve previously offloaded context messages
 * by their UUID. When context compression occurs, tool invocation messages are
 * offloaded to storage with a UUID, and this tool can be used to restore them
 * when needed.
 */
public class ContextOffloadTool {

    private final ContextOffLoader contextOffLoader;

    /**
     * Creates a new ContextOffloadTool with the specified ContextOffLoader.
     *
     * @param contextOffLoader the context offloader to use for reloading messages
     */
    public ContextOffloadTool(ContextOffLoader contextOffLoader) {
        this.contextOffLoader = contextOffLoader;
    }

    /**
     * Reload offloaded context messages by UUID.
     *
     * <p>This tool retrieves a list of messages that were previously offloaded
     * during context compression. The messages are returned as a list of Msg objects
     * that can be used directly in the conversation context.
     *
     * <p>Use this tool when:
     * <ul>
     *   <li>You need to access the original tool invocation history that was compressed
     *   <li>The compressed context mentions a UUID for offloaded messages
     *   <li>You need detailed information about past tool calls that were summarized
     * </ul>
     *
     * @param uuid the UUID of the offloaded context to reload
     * @return a list of messages that were previously offloaded, or a list containing
     *         an error message if the UUID is not found or invalid
     */
    @Tool(
            name = "context_reload",
            description =
                    "Reload previously offloaded context messages by UUID. Use this tool when you"
                            + " need to access the original tool invocation history that was"
                            + " compressed and stored. The UUID is typically provided in compressed"
                            + " context hints.")
    public List<Msg> reload(
            @ToolParam(
                            name = "working_context_offload_uuid",
                            description =
                                    "The UUID of the offloaded context to reload. This UUID is"
                                            + " provided in compressed context hints when context"
                                            + " compression occurs.")
                    String uuid) {
        if (contextOffLoader == null) {
            return List.of(
                    Msg.builder()
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "Error: Context offloader is not available."
                                                            + " Cannot reload context with UUID: "
                                                            + uuid)
                                            .build())
                            .build());
        }

        if (uuid == null || uuid.trim().isEmpty()) {
            return List.of(
                    Msg.builder()
                            .content(
                                    TextBlock.builder()
                                            .text("Error: UUID cannot be null or empty.")
                                            .build())
                            .build());
        }

        try {
            List<Msg> messages = contextOffLoader.reload(uuid);
            if (messages == null || messages.isEmpty()) {
                return List.of(
                        Msg.builder()
                                .content(
                                        TextBlock.builder()
                                                .text(
                                                        "No messages found for UUID: "
                                                                + uuid
                                                                + ", The context may have been"
                                                                + " cleared or the UUID is"
                                                                + " invalid.")
                                                .build())
                                .build());
            }
            return messages;

        } catch (Exception e) {
            return List.of(
                    Msg.builder()
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "Error reloading context with UUID "
                                                            + uuid
                                                            + ": "
                                                            + e.getMessage())
                                            .build())
                            .build());
        }
    }
}
