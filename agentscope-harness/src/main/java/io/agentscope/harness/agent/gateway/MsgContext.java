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
package io.agentscope.harness.agent.gateway;

import java.util.Map;
import java.util.Objects;

/**
 * Routing context for inbound turns (direct API, channel adapter, group/room/thread). Used by
 * the gateway to map stable conversation keys to session ids.
 *
 * <p>The {@link #userId} field carries the message sender's identity for multi-tenant namespace
 * isolation in {@link io.agentscope.harness.agent.HarnessAgent}. It is derived from
 * {@link io.agentscope.harness.agent.gateway.channel.InboundMessage#senderId()} and does
 * <em>not</em> participate in {@link #canonicalKey()} computation — the same user's conversations
 * always map to the same session key regardless of how userId is set.
 *
 * @param channel logical channel name (e.g. slack, discord, web)
 * @param group optional group / team / workspace id
 * @param room optional room / channel id
 * @param threadId optional thread / topic id
 * @param threadTs optional provider-specific thread timestamp or message anchor
 * @param extra additional key/value pairs for adapters
 * @param userId optional authenticated user identity for HarnessAgent namespace isolation
 */
public record MsgContext(
        String channel,
        String group,
        String room,
        String threadId,
        String threadTs,
        Map<String, String> extra,
        String userId) {

    public MsgContext {
        extra = extra != null ? Map.copyOf(extra) : Map.of();
    }

    /**
     * Convenience constructor without {@code userId} (backwards-compatible for callsites that do
     * not carry user identity).
     */
    public MsgContext(
            String channel,
            String group,
            String room,
            String threadId,
            String threadTs,
            Map<String, String> extra) {
        this(channel, group, room, threadId, threadTs, extra, null);
    }

    /** Default single-conversation context (no channel metadata, no userId). */
    public static MsgContext defaultContext() {
        return new MsgContext("default", null, null, null, null, Map.of(), null);
    }

    /** Returns a copy of this context with the given {@code userId} set. */
    public MsgContext withUserId(String userId) {
        return new MsgContext(channel, group, room, threadId, threadTs, extra, userId);
    }

    /** Stable key for session routing: same logical conversation maps to the same gateway session id. */
    public String canonicalKey() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(escapeKeyComponent(Objects.requireNonNullElse(channel, "default")));
        if (group != null && !group.isBlank()) {
            sb.append("|g:").append(escapeKeyComponent(group.trim()));
        }
        if (room != null && !room.isBlank()) {
            sb.append("|r:").append(escapeKeyComponent(room.trim()));
        }
        if (threadId != null && !threadId.isBlank()) {
            sb.append("|t:").append(escapeKeyComponent(threadId.trim()));
        }
        if (threadTs != null && !threadTs.isBlank()) {
            sb.append("|ts:").append(escapeKeyComponent(threadTs.trim()));
        }
        if (!extra.isEmpty()) {
            extra.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    sb.append("|x:")
                                            .append(escapeKeyComponent(e.getKey()))
                                            .append('=')
                                            .append(escapeKeyComponent(e.getValue())));
        }
        return sb.toString();
    }

    /**
     * Escape the two characters that would otherwise let a value imitate key structure, so that
     * {@code '|'} appears in the key only as a separator and {@code '%'} only as an escape
     * introducer. Without this, {@code group="team|r:room-1"} and {@code group="team",
     * room="room-1"} produce the same key, and two distinct conversations share one gateway session
     * (session id, per-key turn gate and the session-to-key wake-up map all derive from it).
     *
     * <p>Escapes {@code '%'} before {@code '|'}: the other order would re-escape the {@code '%'} of
     * the escape it just wrote, and a literal {@code %7C} value would then collide with a literal
     * {@code |} one.
     *
     * <p>Values containing neither character - the common case - are returned unchanged, so
     * existing session keys and their derived ids stay byte-identical.
     */
    private static String escapeKeyComponent(String value) {
        if (value.indexOf('%') < 0 && value.indexOf('|') < 0) {
            return value;
        }
        return value.replace("%", "%25").replace("|", "%7C");
    }
}
