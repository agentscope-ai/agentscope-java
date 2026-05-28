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
package io.agentscope.claw2.runtime.gateway;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;
import java.util.Objects;

/**
 * Routing context for inbound turns (direct API, channel adapter, group/room/thread). Used by
 * {@link HarnessGateway} to map stable conversation keys to {@link RuntimeContext} session ids.
 *
 * <p>agentscope-claw is single-user — there is no {@code userId} dimension. Multi-tenant
 * channel/room/thread routing fields remain so external channel adapters (Slack, Discord, ...)
 * can still distinguish conversations.
 *
 * @param channel logical channel name (e.g. slack, discord, web, chatui)
 * @param group optional group / team / workspace id
 * @param room optional room / channel id
 * @param threadId optional thread / topic id
 * @param threadTs optional provider-specific thread timestamp or message anchor
 * @param extra additional key/value pairs for adapters (e.g. {@code agentId})
 */
public record MsgContext(
        String channel,
        String group,
        String room,
        String threadId,
        String threadTs,
        Map<String, String> extra) {

    public MsgContext {
        extra = extra != null ? Map.copyOf(extra) : Map.of();
    }

    /** Default single-conversation context (no channel metadata). */
    public static MsgContext defaultContext() {
        return new MsgContext("default", null, null, null, null, Map.of());
    }

    /** Stable key for session routing: same logical conversation maps to the same gateway session id. */
    public String canonicalKey() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Objects.requireNonNullElse(channel, "default"));
        if (group != null && !group.isBlank()) {
            sb.append("|g:").append(group.trim());
        }
        if (room != null && !room.isBlank()) {
            sb.append("|r:").append(room.trim());
        }
        if (threadId != null && !threadId.isBlank()) {
            sb.append("|t:").append(threadId.trim());
        }
        if (threadTs != null && !threadTs.isBlank()) {
            sb.append("|ts:").append(threadTs.trim());
        }
        if (!extra.isEmpty()) {
            extra.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    sb.append("|x:")
                                            .append(e.getKey())
                                            .append('=')
                                            .append(e.getValue()));
        }
        return sb.toString();
    }
}
