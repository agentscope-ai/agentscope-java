/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.util.MsgUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionExample - Demonstrates persistent conversation sessions.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>Using JsonSession to save/load conversations
 *   <li>Maintaining conversation context across runs
 *   <li>Session state management
 * </ul>
 *
 * <p>Run:
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.SessionExample"
 * </pre>
 *
 * <p>Try:
 *
 * <ul>
 *   <li>First run: "My name is Alice"
 *   <li>Second run: "What's my name?" → Agent remembers: "Alice"
 * </ul>
 */
public class SessionExample {

    private static final String DEFAULT_SESSION_ID = "default_session";
    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Session Example",
                "This example demonstrates persistent conversation sessions.\n"
                        + "Your conversations are saved and can be resumed later.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Get session ID
        String sessionId = getSessionId();

        // Initialize JsonSession
        JsonSession session =
                new JsonSession(
                        Paths.get(
                                System.getProperty("user.home"),
                                ".agentscope",
                                "examples",
                                "sessions"));

        // Create agent components
        InMemoryMemory memory = new InMemoryMemory();
        Toolkit toolkit = new Toolkit();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant with persistent memory. You can"
                                        + " remember information from previous conversations.")
                        .toolkit(toolkit)
                        .memory(memory)
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .build();

        // Create state modules map for session management
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("agent", agent);
        stateModules.put("memory", memory);

        // Load existing session if it exists
        loadSession(session, sessionId, stateModules, memory);

        // Start interactive conversation
        runConversation(agent, session, sessionId, stateModules, memory);
    }

    private static String getSessionId() throws Exception {
        System.out.print("Enter session ID (default: " + DEFAULT_SESSION_ID + "): ");
        String sessionId = reader.readLine().trim();

        if (sessionId.isEmpty()) {
            sessionId = DEFAULT_SESSION_ID;
        }

        System.out.println("Using session: " + sessionId + "\n");
        return sessionId;
    }

    private static void loadSession(
            JsonSession session,
            String sessionId,
            Map<String, StateModule> stateModules,
            InMemoryMemory memory) {

        try {
            boolean sessionExists = session.sessionExists(sessionId);
            if (sessionExists) {
                session.loadSessionState(sessionId, stateModules);
                int messageCount = memory.getMessages().size();
                System.out.println(
                        "✓ Session loaded: " + sessionId + " (" + messageCount + " messages)\n");

                if (messageCount > 0) {
                    System.out.println("Type 'history' to view previous messages.");
                }
            } else {
                System.out.println("✓ New session created: " + sessionId + "\n");
            }
        } catch (Exception e) {
            System.out.println("✓ New session created: " + sessionId + "\n");
        }
    }

    private static void runConversation(
            ReActAgent agent,
            JsonSession session,
            String sessionId,
            Map<String, StateModule> stateModules,
            InMemoryMemory memory)
            throws Exception {

        System.out.println("=== Chat Started ===");
        System.out.println("Commands: 'exit' to quit, 'history' to view message history\n");

        while (true) {
            System.out.print("You> ");
            String input = reader.readLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            if (input.trim().isEmpty()) {
                continue;
            }

            // Handle special commands
            if ("history".equalsIgnoreCase(input.trim())) {
                showHistory(memory);
                continue;
            }

            try {
                // Send user message to agent
                Msg userMsg =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(input).build())
                                .build();

                Msg response = agent.call(userMsg).block();

                if (response != null) {
                    System.out.println(
                            "Agent> "
                                    + io.agentscope.examples.util.MsgUtils.getTextContent(response)
                                    + "\n");
                } else {
                    System.out.println("Agent> [No response]\n");
                }

                // Save session after each interaction
                session.saveSessionState(sessionId, stateModules);

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        // Final session save before exit
        try {
            session.saveSessionState(sessionId, stateModules);
            System.out.println("\n✓ Session saved: " + sessionId);
            System.out.println("Resume this conversation later by entering the same session ID.");
        } catch (Exception e) {
            System.err.println("Warning: Failed to save session");
        }
    }

    private static void showHistory(InMemoryMemory memory) {
        List<Msg> messages = memory.getMessages();

        if (messages.isEmpty()) {
            System.out.println("\n[No message history]\n");
            return;
        }

        System.out.println("\n=== Message History ===");
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String role = msg.getRole() == MsgRole.USER ? "You" : "Agent";
            String content = MsgUtils.getTextContent(msg);

            // Truncate long messages
            if (content.length() > 100) {
                content = content.substring(0, 97) + "...";
            }

            System.out.println((i + 1) + ". " + role + ": " + content);
        }
        System.out.println("Total messages: " + messages.size());
        System.out.println("=======================\n");
    }
}
