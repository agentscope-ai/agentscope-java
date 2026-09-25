/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import java.util.List;
import java.util.Set;

/**
 * Workspace partitioning for persisted task records served by the protocol endpoints, plus the
 * contract for caller-supplied context attributes.
 */
public final class AgentProtocolConstants {

    private AgentProtocolConstants() {}

    /** Synthetic parent agent id for {@code agents/&lt;id&gt;/tasks/} paths. */
    public static final String PROTOCOL_AGENT_ID = "_agentscope_protocol";

    /** Single session bucket file holding all protocol tasks as a task-id map. */
    public static final String PROTOCOL_SESSION_ID = "_tasks";

    /**
     * Field inside the submission {@code context} that carries caller-defined attributes. Keeping
     * them nested separates them from the protocol's own context fields ({@code user_id},
     * {@code parent_session_id}, {@code stream}, {@code detail}, {@code deny_rules}).
     */
    public static final String CONTEXT_ATTRIBUTES_FIELD = "attributes";

    /**
     * {@link io.agentscope.core.agent.RuntimeContext} key holding the whole {@code
     * context.attributes} map as an unmodifiable {@code Map<String, Object>}.
     *
     * <p>Attributes are namespaced under this single key so that no caller-controlled name can
     * shadow a framework key. Tools and middlewares read them with {@code ctx.get(
     * AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY)}; to expose an attribute under its own
     * top-level key instead, use {@link RuntimeContextCustomizer#flatten(String...)}.
     */
    public static final String RUNTIME_CONTEXT_ATTRIBUTES_KEY = "agentprotocol.context.attributes";

    /** Exact runtime-context keys the framework reads; never overwritten by flattening. */
    private static final Set<String> RESERVED_KEYS = Set.of("agentId", "outboundAddress");

    /** Runtime-context key prefixes owned by the framework; never overwritten by flattening. */
    private static final List<String> RESERVED_PREFIXES =
            List.of("agentscope.", "agui.", "io.agentscope.");

    /**
     * Whether {@code key} is read by the framework itself and therefore unsafe to overwrite with a
     * caller-supplied value. Examples: {@code agentId} drives async tool wakeup routing and
     * {@code outboundAddress} carries the gateway reply address.
     */
    public static boolean isReservedRuntimeContextKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        if (RESERVED_KEYS.contains(key)) {
            return true;
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
