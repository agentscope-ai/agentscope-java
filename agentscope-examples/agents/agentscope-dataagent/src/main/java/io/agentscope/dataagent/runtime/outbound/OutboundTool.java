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
package io.agentscope.dataagent.runtime.outbound;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.runtime.gateway.ChannelManager;
import java.util.Objects;

/**
 * Agent-facing tool for proactive outbound delivery into any registered channel (DingTalk, WeCom,
 * ...). Registered onto the main agent's toolkit by {@link
 * io.agentscope.dataagent.runtime.DataAgentBootstrap}.
 *
 * <p>The agent supplies the target ({@code channel_id} + {@code peer_kind} + {@code peer_id}) and
 * either {@code text} or {@code markdown}. The tool returns a short status string.
 */
public final class OutboundTool {

    private final OutboundService service;

    public OutboundTool(ChannelManager channelManager) {
        Objects.requireNonNull(channelManager, "channelManager");
        this.service = new OutboundService(channelManager);
    }

    @Tool(
            name = "outbound_send",
            description =
                    """
                    Proactively deliver a message into a registered IM channel \
                    (e.g. DingTalk, WeCom). Use to push notifications, status updates, or \
                    follow-ups when no incoming user message is in flight. Specify channel_id \
                    (a registered channel), peer_kind (DIRECT | CHANNEL | GROUP | THREAD), and \
                    peer_id (the provider-specific user or group id). Either text or markdown \
                    must be supplied. Returns "ok" on success or a short error description.\
                    """)
    public String send(
            @ToolParam(
                            name = "channel_id",
                            description = "Registered channel id (e.g. 'wecom-prod')")
                    String channelId,
            @ToolParam(
                            name = "peer_kind",
                            description =
                                    "DIRECT | CHANNEL | GROUP | THREAD; defaults to DIRECT when"
                                            + " omitted")
                    String peerKind,
            @ToolParam(name = "peer_id", description = "Target user / group / channel id")
                    String peerId,
            @ToolParam(
                            name = "text",
                            description =
                                    "Plain-text message body; mutually exclusive with markdown",
                            required = false)
                    String text,
            @ToolParam(
                            name = "markdown",
                            description = "Markdown message body; mutually exclusive with text",
                            required = false)
                    String markdown,
            @ToolParam(
                            name = "account_id",
                            description =
                                    "Optional multi-account dimension (corp/app instance) when"
                                            + " the channel hosts more than one account",
                            required = false)
                    String accountId,
            @ToolParam(
                            name = "thread_id",
                            description = "Optional thread anchor for threaded replies",
                            required = false)
                    String threadId,
            @ToolParam(
                            name = "agent_id",
                            description =
                                    "Optional caller agent id. When set, the service verifies that"
                                        + " the channel's routing for this peer resolves to the"
                                        + " same agent and refuses delivery otherwise — preventing"
                                        + " one agent from posting into a channel/peer bound to a"
                                        + " different agent. Omit for backward-compatible behaviour"
                                        + " with no caller check.",
                            required = false)
                    String agentId) {
        try {
            service.send(
                    new OutboundRequest(
                            channelId, peerKind, peerId, accountId, threadId, text, markdown,
                            agentId));
            return "ok";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
    }
}
