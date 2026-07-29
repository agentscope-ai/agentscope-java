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
package io.agentscope.builder.web.managed;

/** Canonical session event type names (persisted + stream-only). */
public final class SessionEventTypes {

    private SessionEventTypes() {}

    // ---- Agent (persisted) ----
    public static final String AGENT_MESSAGE = "agent.message";
    public static final String AGENT_THINKING = "agent.thinking";
    public static final String AGENT_TOOL_USE = "agent.tool_use";
    public static final String AGENT_TOOL_RESULT = "agent.tool_result";
    public static final String AGENT_MCP_TOOL_USE = "agent.mcp_tool_use";
    public static final String AGENT_MCP_TOOL_RESULT = "agent.mcp_tool_result";
    public static final String AGENT_CUSTOM_TOOL_USE = "agent.custom_tool_use";
    public static final String AGENT_THREAD_CONTEXT_COMPACTED = "agent.thread_context_compacted";

    // ---- Session (persisted) ----
    public static final String SESSION_STATUS_CREATED = "session.status_created";
    public static final String SESSION_STATUS_RUNNING = "session.status_running";
    public static final String SESSION_STATUS_IDLE = "session.status_idle";
    public static final String SESSION_STATUS_RESCHEDULED = "session.status_rescheduled";
    public static final String SESSION_STATUS_TERMINATED = "session.status_terminated";
    public static final String SESSION_STATUS_REQUIRES_ACTION = "session.status_requires_action";
    public static final String SESSION_STATUS_ARCHIVED = "session.status_archived";
    public static final String SESSION_ERROR = "session.error";
    public static final String SESSION_UPDATED = "session.updated";
    public static final String SESSION_DELETED = "session.deleted";
    public static final String SESSION_INTERRUPTED = "session.interrupted";
    public static final String SESSION_REQUIRES_ACTION = "session.requires_action";

    // ---- Span (persisted) ----
    public static final String SPAN_MODEL_REQUEST_START = "span.model_request_start";
    public static final String SPAN_MODEL_REQUEST_END = "span.model_request_end";

    // ---- Inbound ----
    public static final String USER_MESSAGE = "user.message";
    public static final String USER_INTERRUPT = "user.interrupt";
    public static final String USER_TOOL_CONFIRMATION = "user.tool_confirmation";
    public static final String USER_CUSTOM_TOOL_RESULT = "user.custom_tool_result";
    public static final String USER_TOOL_RESULT = "user.tool_result";
    public static final String USER_DEFINE_OUTCOME = "user.define_outcome";
    public static final String SYSTEM_MESSAGE = "system.message";

    // ---- Stream-only (never persisted) ----
    public static final String EVENT_START = "event_start";
    public static final String EVENT_DELTA = "event_delta";
}
