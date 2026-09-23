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
package io.agentscope.harness.agent.gateway.channel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.gateway.MsgContext;

/**
 * Inputs available to a {@link ChannelRuntimeContextResolver} when resolving caller context for a
 * Gateway turn.
 *
 * @param channelId originating channel id (e.g. {@code "chatui"}, {@code "dingtalk"}); may be null
 * @param inboundMessage the normalized inbound envelope when the turn came through {@link
 *     Channel#dispatch}; {@code null} for programmatic {@code SendOptions} paths that skip an
 *     inbound envelope
 * @param msgContext resolved routing context for this turn
 * @param outboundAddress delivery target for replies; may be null
 * @param callerContext context carried on {@link
 *     io.agentscope.harness.agent.gateway.channel.chatui.SendOptions} or {@link InboundMessage};
 *     may be null
 */
public record ChannelRuntimeContextRequest(
        String channelId,
        InboundMessage inboundMessage,
        MsgContext msgContext,
        OutboundAddress outboundAddress,
        RuntimeContext callerContext) {}
