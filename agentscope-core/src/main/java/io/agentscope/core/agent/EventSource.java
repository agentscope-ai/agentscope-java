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
package io.agentscope.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Identifies the origin of an {@link Event} emitted during streaming agent execution.
 *
 * <p>When a subagent runs synchronously inside a parent agent's {@code stream()} call, every
 * {@link Event} produced by the subagent carries an {@code EventSource} that encodes the full
 * logical path to the producing agent. This mirrors LangGraph streaming v2's
 * <em>namespace / path</em> concept, allowing consumers (UI, adapters, logging) to distinguish
 * events from the top-level agent vs. any nested subagent.
 *
 * <p>Events emitted by the top-level (parent) agent have {@code source == null}.
 *
 * <h3>Field meanings</h3>
 *
 * <table border="1">
 *   <caption>EventSource fields</caption>
 *   <tr><th>Field</th><th>Meaning</th></tr>
 *   <tr><td>{@code agentKey}</td>
 *       <td>Opaque runtime handle for the spawned agent instance, e.g.
 *       {@code "agent:researcher:uuid"}. This is the value the LLM passes back to
 *       {@code agent_send} to address a previously spawned agent.</td></tr>
 *   <tr><td>{@code agentId}</td>
 *       <td>Registered subagent type identifier (the filename without {@code .md} in the
 *       workspace {@code subagents/} directory), e.g. {@code "researcher"}.</td></tr>
 *   <tr><td>{@code agentName}</td>
 *       <td>Human-readable display name of the subagent, may be null if not set.</td></tr>
 *   <tr><td>{@code sessionId}</td>
 *       <td>Unique session ID for this specific subagent invocation. Stable across
 *       {@code agent_send} follow-ups to the same spawned instance.</td></tr>
 *   <tr><td>{@code parentSessionId}</td>
 *       <td>Session ID of the parent agent that issued the {@code agent_spawn} call.</td></tr>
 *   <tr><td>{@code taskId}</td>
 *       <td>Reserved; non-null only when the subagent runs as a background task (async
 *       streaming, not yet implemented — see extension points below).</td></tr>
 *   <tr><td>{@code depth}</td>
 *       <td>Nesting depth: {@code 1} = direct child of the top-level agent, {@code 2} =
 *       grandchild, and so on.</td></tr>
 *   <tr><td>{@code path}</td>
 *       <td>Slash-separated call hierarchy from root to producer, e.g.
 *       {@code "main/researcher"} or {@code "main/planner/sub-executor"}. Split on
 *       {@code "/"} to recover the hierarchy.</td></tr>
 * </table>
 *
 * <h3>Path convention</h3>
 *
 * <p>The {@code path} field is a slash-separated string like {@code "main/researcher"} or
 * {@code "main/planner/sub-executor"}. The root segment is the parent agent's session ID
 * (or {@code "main"} as fallback); each subsequent segment is the {@code agentId} of the
 * subagent at that depth. Consumers can split on {@code "/"} to reconstruct the call hierarchy.
 *
 * <h3>Extension points (not yet implemented)</h3>
 *
 * <ul>
 *   <li><b>Async task streaming:</b> Populate {@code taskId} when the subagent runs as an
 *       async background task. The task repository can attach a dedicated
 *       {@link SubagentEventBus} to each task so the parent's {@code Flux} accumulates
 *       events even after the original request context ends.</li>
 *   <li><b>Remote subagent streaming:</b> An Agent Protocol {@code GET /tasks/{taskId}/events}
 *       SSE endpoint can deliver events from a remote subagent. Each received event is
 *       stamped with an {@code EventSource} (constructed from the remote agent's metadata)
 *       before being forwarded to the local parent {@code FluxSink}.</li>
 *   <li><b>AG-UI / Web adapter:</b> Consumers can inspect {@code source.path} and
 *       {@code source.depth} to render hierarchical cards or collapsible trace panels,
 *       providing a LangGraph v2 streaming-style experience in the UI.</li>
 * </ul>
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * EventSource src = EventSource.builder()
 *     .agentKey("agent:researcher:uuid-1")
 *     .agentId("researcher")
 *     .agentName("ResearcherAgent")
 *     .sessionId("sub-uuid-1")
 *     .parentSessionId("parent-session")
 *     .depth(1)
 *     .path("main/researcher")
 *     .build();
 *
 * // Extend for a grandchild agent:
 * EventSource grandchild = src.withAppendedPath("sub-executor");
 * // grandchild.getDepth() == 2, grandchild.getPath() == "main/researcher/sub-executor"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class EventSource {

    /** Opaque key identifying the spawned agent instance (e.g. {@code "agent:researcher:uuid"}). */
    private final String agentKey;

    /** Registered agent type identifier (e.g. {@code "researcher"}). */
    private final String agentId;

    /** Human-readable agent name. */
    private final String agentName;

    /** Session ID used for this subagent invocation. */
    private final String sessionId;

    /** Session ID of the parent agent that spawned this subagent. */
    private final String parentSessionId;

    /**
     * Optional task ID when this subagent invocation is associated with a background task
     * (reserved for future async streaming support).
     */
    private final String taskId;

    /** Nesting depth: 1 = direct child of the top-level agent, 2 = grandchild, etc. */
    private final int depth;

    /**
     * Slash-separated path from the top-level agent to this producer, e.g.
     * {@code "main/researcher"}.
     */
    private final String path;

    private EventSource(Builder builder) {
        this.agentKey = builder.agentKey;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.sessionId = builder.sessionId;
        this.parentSessionId = builder.parentSessionId;
        this.taskId = builder.taskId;
        this.depth = builder.depth;
        this.path = builder.path;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a new {@code EventSource} with {@code path} extended by {@code segment}, and
     * {@code depth} incremented by one. All other fields are copied from this instance.
     */
    public EventSource withAppendedPath(String segment) {
        String newPath = (path == null || path.isEmpty()) ? segment : path + "/" + segment;
        return builder()
                .agentKey(agentKey)
                .agentId(agentId)
                .agentName(agentName)
                .sessionId(sessionId)
                .parentSessionId(parentSessionId)
                .taskId(taskId)
                .depth(depth + 1)
                .path(newPath)
                .build();
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getDepth() {
        return depth;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "EventSource{path='" + path + "', depth=" + depth + ", agentId='" + agentId + "'}";
    }

    /** Builder for {@link EventSource}. */
    public static final class Builder {

        private String agentKey;
        private String agentId;
        private String agentName;
        private String sessionId;
        private String parentSessionId;
        private String taskId;
        private int depth = 1;
        private String path;

        private Builder() {}

        public Builder agentKey(String agentKey) {
            this.agentKey = agentKey;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder parentSessionId(String parentSessionId) {
            this.parentSessionId = parentSessionId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public EventSource build() {
            return new EventSource(this);
        }
    }
}
