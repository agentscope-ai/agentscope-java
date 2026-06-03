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
package io.agentscope.examples.documentation2.session;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.common.ExampleUtils;
import io.agentscope.examples.documentation2.common.MsgUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * SessionExample - Demonstrates persistent conversation sessions.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced {@code legacy.session.JsonSession} with {@code io.agentscope.core.session.JsonSession}.</li>
 *   <li>Replaced {@code legacy.memory.InMemoryMemory} and {@code .memory()} — removed entirely;
 *       conversation history is now held in {@code AgentState}.</li>
 *   <li>Replaced {@code agent.loadFrom(session, id)} / {@code agent.saveTo(session, id)} with
 *       {@code .session(session).sessionId(id)} on the builder — the agent loads and saves
 *       automatically when a session is configured.</li>
 *   <li>Replaced {@code agent.getMemory().getMessages()} with {@code agent.getState().getContext()}.</li>
 * </ul>
 */
public class SessionExample {

    private static final String DEFAULT_SESSION_ID = "default_session";
    private static final BufferedReader READER =
            new BufferedReader(new InputStreamReader(System.in));

    /**
     * Runs the session example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "Session Example",
                "This example demonstrates persistent conversation sessions.\n"
                        + "Your conversations are saved and can be resumed later.");

        String apiKey = ExampleUtils.getDashScopeApiKey();
        String sessionId = getSessionId();

        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        Session session = new JsonSession(sessionPath);

        // Configure the agent with a session — it loads existing history on first call
        // and persists after every interaction automatically.
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant with persistent memory. You can"
                                        + " remember information from previous conversations.")
                        .toolkit(new Toolkit())
                        .session(session)
                        .sessionKey(SimpleSessionKey.of(sessionId))
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .build();

        runConversation(agent, sessionId);
    }

    private static String getSessionId() throws Exception {
        System.out.print("Enter session ID (default: " + DEFAULT_SESSION_ID + "): ");
        String sessionId = READER.readLine().trim();
        if (sessionId.isEmpty()) {
            sessionId = DEFAULT_SESSION_ID;
        }
        System.out.println("Using session: " + sessionId + "\n");
        return sessionId;
    }

    private static void runConversation(ReActAgent agent, String sessionId) throws Exception {
        System.out.println("=== Chat Started ===");
        System.out.println("Commands: 'exit' to quit, 'history' to view message history\n");

        // Show context size loaded from session
        int ctxSize = agent.getState().getContext().size();
        if (ctxSize > 0) {
            System.out.println("Session resumed — " + ctxSize + " messages loaded.");
            System.out.println("Type 'history' to view them.\n");
        } else {
            System.out.println("New session: " + sessionId + "\n");
        }

        while (true) {
            System.out.print("You> ");
            String input = READER.readLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }
            if (input.trim().isEmpty()) {
                continue;
            }
            if ("history".equalsIgnoreCase(input.trim())) {
                showHistory(agent);
                continue;
            }

            try {
                Msg userMsg = new UserMessage(input);

                Msg response = agent.call(userMsg).block();
                if (response != null) {
                    System.out.println("Agent> " + MsgUtils.getTextContent(response) + "\n");
                } else {
                    System.out.println("Agent> [No response]\n");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        System.out.println("\nSession '" + sessionId + "' is persisted automatically.");
        System.out.println("Resume this conversation later by entering the same session ID.");
    }

    private static void showHistory(ReActAgent agent) {
        List<Msg> messages = agent.getState().getContext();

        if (messages.isEmpty()) {
            System.out.println("\n[No message history]\n");
            return;
        }

        System.out.println("\n=== Message History ===");
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String role = msg.getRole() == MsgRole.USER ? "You" : "Agent";
            String content = MsgUtils.getTextContent(msg);
            if (content.length() > 100) {
                content = content.substring(0, 97) + "...";
            }
            System.out.println((i + 1) + ". " + role + ": " + content);
        }
        System.out.println("Total messages: " + messages.size());
        System.out.println("=======================\n");
    }
}
