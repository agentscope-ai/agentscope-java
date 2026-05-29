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
package io.agentscope.claw2.runtime.channel;

import io.agentscope.claw2.runtime.gateway.MsgContext;

/**
 * Output of {@link ChannelRouter#resolveRoute}: the resolved agent id, the {@link MsgContext} to
 * pass to {@link io.agentscope.claw2.runtime.gateway.Gateway#run}, a diagnostic {@code matchedBy}
 * label indicating which binding tier (or fallback) produced the result, and the {@link
 * OutboundAddress} for delivering replies back to the originating channel/peer.
 *
 * @param agentId the resolved target agent id
 * @param context the routing context (channel, session scope keys, agentId extra) ready for {@link
 *     io.agentscope.claw2.runtime.gateway.Gateway#run}
 * @param matchedBy human-readable label of the binding tier that matched (e.g. {@code "peer"},
 *     {@code "guild+roles"}, {@code "default"}); useful for debugging and logging
 * @param outboundAddress delivery target for proactive replies (e.g. subagent announces); derived
 *     from the inbound message's channel and peer metadata
 */
public record RouteResult(
        String agentId, MsgContext context, String matchedBy, OutboundAddress outboundAddress) {}
