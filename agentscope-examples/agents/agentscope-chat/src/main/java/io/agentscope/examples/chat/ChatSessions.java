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

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages.Input;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableSession;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.CommandReceipt;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.RunPage;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Local application wiring. Business operations use the same Runtime API as remote hosts. */
public final class ChatSessions implements AutoCloseable {
    private static final String USER = "local-user";
    private static final String AGENT = "session-chat";
    private final SessionBackend backend;
    private final DurableRuntime runtime;
    private final String modelName;

    public ChatSessions(Path workspace, Model model) {
        modelName = model.getModelName();
        backend = LocalBackend.open(workspace.toAbsolutePath().resolve("durable"));
        var adapter =
                AgentScopeAdapter.forHarness(
                        binding -> {
                            var toolkit = new Toolkit();
                            if (model instanceof DemoChatModel demo)
                                toolkit.registerTool(new DemoTools(demo.toolDelay()));
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("ask_user")
                                            .description(
                                                    "Ask the user for a missing preference and wait"
                                                            + " for their answer.")
                                            .parameters(
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of(
                                                                    "question",
                                                                    Map.of("type", "string")),
                                                            "required",
                                                            List.of("question")))
                                            .build());
                            Model invocationModel = model;
                            if (model instanceof DemoChatModel demo) {
                                // The offline fixture identifies the task from the public input
                                // projection,
                                // never by decoding native journal records or private message IDs.
                                var snapshot = session(binding.context().getSessionId()).snapshot();
                                for (var message : snapshot.projection().path("messages")) {
                                    if (binding.context()
                                                    .getTurnId()
                                                    .equals(message.path("turnId").asText())
                                            && "submit"
                                                    .equals(message.path("inputKind").asText())) {
                                        invocationModel =
                                                demo.forTask(message.path("text").asText());
                                        break;
                                    }
                                }
                            }
                            return HarnessAgent.builder()
                                    .name("Session Chat")
                                    .agentId(AGENT)
                                    .model(invocationModel)
                                    .sysPrompt(
                                            "You are a concise chat assistant. Use ask_user when a"
                                                    + " needed preference is missing.")
                                    .workspace(workspace.toAbsolutePath())
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
                                    .maxIters(8);
                        },
                        Map.of());
        runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        // Recreating a session with another model is a conflicting definition.
                        .harness(AGENT, "chat-v1:" + modelName, adapter)
                        .pollInterval(Duration.ofMillis(50))
                        .build();
        runtime.start();
    }

    public String modelName() {
        return modelName;
    }

    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid ID");
        return value;
    }

    public SessionView create(String sessionId) {
        return runtime.createSession(new SessionKey(USER, id(sessionId)), AGENT).view();
    }

    DurableSession session(String sessionId) {
        return runtime.getSession(new SessionKey(USER, id(sessionId)));
    }

    DurableTurn turn(String sessionId, String turnId) {
        return session(sessionId).getTurn(id(turnId));
    }

    public List<String> list() {
        var ids = new ArrayList<String>();
        String cursor = null;
        do {
            var page = runtime.listSessions(USER, cursor, 100);
            page.items().forEach(value -> ids.add(value.key().sessionId()));
            cursor = page.cursor();
        } while (cursor != null);
        return ids.stream().sorted().toList();
    }

    public Snapshot snapshot(String sessionId) {
        return session(sessionId).snapshot();
    }

    public TurnView submit(String sessionId, String requestId, String text) {
        validateText(text);
        return session(sessionId).submit(id(requestId), text).status();
    }

    public CommandReceipt steer(String sessionId, String turnId, String requestId, String text) {
        validateText(text);
        return turn(sessionId, turnId).steer(id(requestId), text);
    }

    public CommandReceipt inject(String sessionId, String turnId, String requestId, String text) {
        validateText(text);
        return turn(sessionId, turnId).inject(id(requestId), Input.text(text));
    }

    public CommandReceipt pause(String sessionId, String turnId, String requestId) {
        return turn(sessionId, turnId).pause(id(requestId));
    }

    public CommandReceipt resume(String sessionId, String turnId, String requestId, Long revision) {
        if (revision == null) throw new IllegalArgumentException("expectedRevision is required");
        return turn(sessionId, turnId).resume(id(requestId), revision);
    }

    public CommandReceipt cancel(String sessionId, String turnId, String requestId) {
        return turn(sessionId, turnId).cancel(id(requestId));
    }

    public CommandReceipt answer(
            String sessionId,
            String turnId,
            String requestId,
            String interactionId,
            String output) {
        validateText(output);
        return turn(sessionId, turnId)
                .respond(id(requestId), interactionId, DurableJson.value(output));
    }

    public EventPage events(String sessionId, String cursor, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Limit must be 1–256");
        return session(sessionId).events(cursor, limit);
    }

    public RunPage runs(String sessionId, String turnId, String cursor, int limit) {
        return turn(sessionId, turnId).listRuns(cursor, limit);
    }

    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 8000)
            throw new IllegalArgumentException("Message must contain 1–8000 characters");
    }

    @Override
    public void close() {
        try {
            runtime.close();
        } finally {
            backend.close();
        }
    }
}
