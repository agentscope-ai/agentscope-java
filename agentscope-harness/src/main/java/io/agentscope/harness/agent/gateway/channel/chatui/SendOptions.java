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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Objects;

/**
 * Routing identity for {@link ChatUiChannel} requests. Replaces the need to understand
 * {@link io.agentscope.harness.agent.gateway.channel.DmScope} by letting the caller express
 * routing intent through simple business identifiers.
 *
 * <ul>
 *   <li>{@code userId} — identifies the user. Maps to
 *       {@link io.agentscope.harness.agent.gateway.MsgContext#userId()} for HarnessAgent
 *       namespace isolation, and is used as the default session key when {@code sessionId} is null.
 *   <li>{@code sessionId} — optional explicit conversation identifier. When provided, different
 *       sessions for the same user are kept separate. When null, one session per user is the
 *       default.
 *   <li>{@code agentId} — optional target agent override for multi-agent setups. When null, the
 *       channel's default agent is used.
 *   <li>{@code runtimeContext} — optional per-call {@link RuntimeContext} merged into the agent
 *       turn (attributes, typed values, force-sync flags, …). Gateway-owned identity fields still
 *       win on conflict.
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // One session per user (most common)
 * chat.send(SendOptions.userId("user-1"), "hello");
 *
 * // Same user, different sessions
 * chat.send(SendOptions.of("user-1", "session-a"), "hello");
 * chat.send(SendOptions.of("user-1", "session-b"), "hello");
 *
 * // Target a specific agent in multi-agent setups
 * chat.send(SendOptions.userId("user-1").withAgentId("support"), "help me");
 *
 * // Pass application context into the agent turn
 * chat.send(
 *     SendOptions.userId("user-1")
 *         .withAttribute("tenant", "acme")
 *         .withRuntimeContext(
 *             RuntimeContext.builder()
 *                 .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
 *                 .build()),
 *     "hello");
 * }</pre>
 *
 * @param userId the user identity (required)
 * @param sessionId optional conversation identifier; null means one session per user
 * @param agentId optional target agent override; null for default routing
 * @param runtimeContext optional caller-supplied runtime context; null when none
 */
public record SendOptions(
        String userId, String sessionId, String agentId, RuntimeContext runtimeContext) {

    public SendOptions {
        Objects.requireNonNull(userId, "userId");
    }

    /** One session per user — the most common case. */
    public static SendOptions userId(String userId) {
        return new SendOptions(userId, null, null, null);
    }

    /** Explicit user + session — multiple conversations for the same user. */
    public static SendOptions of(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new SendOptions(userId, sessionId, null, null);
    }

    /** Returns a copy with the given agent id override. */
    public SendOptions withAgentId(String agentId) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * Returns a copy that carries the given {@link RuntimeContext} into the agent turn. Replaces
     * any previously attached context.
     */
    public SendOptions withRuntimeContext(RuntimeContext runtimeContext) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * Returns a copy with a string attribute merged into the carried {@link RuntimeContext}.
     * Creates a new context from empty when none is present yet.
     */
    public SendOptions withAttribute(String key, Object value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(key, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /**
     * Returns a copy with a typed attribute merged into the carried {@link RuntimeContext}.
     * Creates a new context from empty when none is present yet.
     */
    public <T> SendOptions withAttribute(Class<T> type, T value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(type, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /** The effective session key: sessionId if provided, otherwise userId. */
    String effectiveSessionKey() {
        return sessionId != null ? sessionId : userId;
    }
}
