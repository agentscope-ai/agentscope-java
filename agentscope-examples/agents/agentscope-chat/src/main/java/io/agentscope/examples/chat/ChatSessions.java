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
package io.agentscope.examples.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** One local process owns execution; HTTP/SSE subscribers only observe it. */
public final class ChatSessions implements AutoCloseable {
    private static final String USER = "local-user";
    private static final String AGENT = "session-chat";
    private final HarnessAgent agent;
    private final WorkspaceSessionLogStore store;
    private final String modelName;

    public ChatSessions(Path workspace, Model model) {
        modelName = model.getModelName();
        store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace.toAbsolutePath()));
        var toolkit = new Toolkit();
        if (model instanceof DemoChatModel demo)
            toolkit.registerTool(new DemoTools(demo.toolDelay()));
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("ask_user")
                        .description(
                                "Ask the user for a missing preference and wait for their answer.")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("question", Map.of("type", "string")),
                                        "required",
                                        List.of("question")))
                        .build());
        agent =
                HarnessAgent.builder()
                        .name("Session Chat")
                        .agentId(AGENT)
                        .model(model)
                        .sysPrompt(
                                "You are a concise chat assistant. Use ask_user when a needed"
                                        + " preference is missing.")
                        .workspace(workspace.toAbsolutePath())
                        .sessionLogStore(store)
                        .toolkit(toolkit)
                        .disableShellTool()
                        .disableFilesystemTools()
                        .disableWebTools()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableToolsConfig()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .maxIters(8)
                        .build();
        list().forEach(id -> session(id).start());
    }

    public String modelName() {
        return modelName;
    }

    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid ID");
        return value;
    }

    private RuntimeContext context(String session) {
        return RuntimeContext.builder().userId(USER).sessionId(id(session)).build();
    }

    SessionLog log(String session) {
        return session(session).log();
    }

    AgentSession session(String id) {
        return agent.session(context(id));
    }

    public List<String> list() {
        return store.list(RuntimeContext.builder().userId(USER).build()).stream()
                .filter(key -> key.agentId().equals(AGENT))
                .map(key -> key.sessionId())
                .sorted()
                .toList();
    }

    public ChatHistory.Snapshot snapshot(String id) {
        var session = session(id);
        var head = session.log().head();
        String active = head.leaseUntil() > System.currentTimeMillis() ? head.owner() : null;
        return ChatHistory.snapshot(
                id, session.log(), active, session.executionError(), session.tasks());
    }

    public AgentSession.Task submit(String session, String requestId, String text) {
        validateText(text);
        return session(session).submit(id(requestId), text);
    }

    public AgentSession.Task resume(String session, String turn) {
        return session(session).resume(id(turn));
    }

    public AgentSession.Task answer(String session, String request, String output) {
        validateText(output);
        return session(session).respond(id(request), output);
    }

    public AgentSession.InputReceipt steer(String session, String text) {
        validateText(text);
        return session(session).steer(List.of(supplement(text, "steer")));
    }

    public AgentSession.InputReceipt inject(String session, String text) {
        validateText(text);
        return session(session).inject(List.of(supplement(text, "inject")));
    }

    // The offline model uses this hint to distinguish demo commands from supplemental inputs.
    private Msg supplement(String text, String kind) {
        return UserMessage.builder()
                .textContent(text)
                .metadata(Map.of("chat_input_kind", kind))
                .build();
    }

    public boolean interrupt(String session, String runId) {
        return session(session).interrupt(runId);
    }

    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 8000)
            throw new IllegalArgumentException("Message must contain 1–8000 characters");
    }

    List<SessionEvent> events(String session, long after, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Limit must be 1–256");
        SessionLog log = log(session);
        if (after > log.head().seq())
            throw new IllegalArgumentException("Cursor is ahead of history; reload the snapshot");
        return log.readAfter(after, limit);
    }

    @Override
    public void close() {
        agent.close();
    }
}
