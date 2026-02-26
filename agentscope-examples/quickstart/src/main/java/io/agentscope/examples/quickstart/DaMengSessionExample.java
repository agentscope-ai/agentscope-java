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
package io.agentscope.examples.quickstart;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.dameng.DaMengSession;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * DaMengSessionExample - Demonstrates persistent conversation sessions using DaMeng database.
 *
 * <p>This example shows how to use DaMengSession to persist Agent conversations in a DaMeng
 * database, allowing sessions to be resumed across application restarts.
 */
public class DaMengSessionExample {

    private static final String DEFAULT_SESSION_ID = "default_session";
    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "DaMeng Session Example",
                "This example demonstrates persistent conversation sessions using DaMeng"
                        + " database.\n"
                        + "Your conversations are saved in DaMeng database and can be resumed"
                        + " later.");

        // Get API key and session ID
        String apiKey = ExampleUtils.getDashScopeApiKey();
        String sessionId = getSessionId();

        // Get DaMeng connection info
        String jdbcUrl = getDaMengJdbcUrl();
        String username = getDaMengUsername();
        String password = getDaMengPassword();

        // Set up DaMeng connection pool
        HikariDataSource dataSource = createDataSource(jdbcUrl, username, password);

        try {
            // Create DaMengSession (auto-create schema and table)
            DaMengSession session = new DaMengSession(dataSource, true);

            // Step 1: Create agent components
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

            // Step 2: Load existing session if it exists
            loadSession(agent, session, sessionId, memory);

            // Step 3: Run interactive conversation
            runConversation(agent, session, sessionId);

            // Step 4: Final save on exit
            saveSession(agent, session, sessionId);

        } finally {
            // Close data source
            dataSource.close();
        }
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

    private static String getDaMengJdbcUrl() throws Exception {
        System.out.print("Enter DaMeng JDBC URL (default: jdbc:dm://localhost:5236/AGENTSCOPE): ");
        String jdbcUrl = reader.readLine().trim();

        if (jdbcUrl.isEmpty()) {
            jdbcUrl = "jdbc:dm://localhost:5236/AGENTSCOPE";
        }

        return jdbcUrl;
    }

    private static String getDaMengUsername() throws Exception {
        System.out.print("Enter DaMeng username (default: SYSDBA): ");
        String username = reader.readLine().trim();

        if (username.isEmpty()) {
            username = "SYSDBA";
        }

        return username;
    }

    private static String getDaMengPassword() throws Exception {
        System.out.print("Enter DaMeng password (default: SYSDBA): ");
        String password = reader.readLine().trim();

        if (password.isEmpty()) {
            password = "SYSDBA";
        }

        return password;
    }

    private static HikariDataSource createDataSource(
            String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("dm.jdbc.driver.DmDriver");

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // DaMeng specific settings
        config.addDataSourceProperty("loginEncrypt", "false");

        try {
            HikariDataSource dataSource = new HikariDataSource(config);
            // Test connection
            try (var conn = dataSource.getConnection()) {
                System.out.println("✓ DaMeng connection pool created and tested successfully\n");
            }
            return dataSource;
        } catch (Exception e) {
            System.err.println("\n❌ Failed to connect to DaMeng database:");
            System.err.println("   Error: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static void loadSession(
            ReActAgent agent, DaMengSession session, String sessionId, InMemoryMemory memory) {
        if (session.exists(SimpleSessionKey.of(sessionId))) {
            // Load existing session
            agent.loadFrom(session, sessionId);
            int messageCount = memory.getMessages().size();
            System.out.println(
                    "✓ Session loaded from DaMeng: "
                            + sessionId
                            + " ("
                            + messageCount
                            + " messages)\n");

            if (messageCount > 0) {
                System.out.println("Type 'history' to view previous messages.");
            }
        } else {
            System.out.println("✓ New session created in DaMeng: " + sessionId + "\n");
        }
    }

    private static void runConversation(ReActAgent agent, DaMengSession session, String sessionId)
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
                showHistory(agent.getMemory());
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
                    System.out.println("Agent> " + MsgUtils.getTextContent(response) + "\n");
                } else {
                    System.out.println("Agent> [No response]\n");
                }

                // Save session after each interaction
                agent.saveTo(session, sessionId);

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    private static void saveSession(ReActAgent agent, DaMengSession session, String sessionId) {
        try {
            agent.saveTo(session, sessionId);
            System.out.println("\n✓ Session saved to DaMeng: " + sessionId);
            System.out.println("Resume this conversation later by entering the same session ID.");
        } catch (Exception e) {
            System.err.println("Warning: Failed to save session: " + e.getMessage());
        }
    }

    private static void showHistory(Memory memory) {
        var messages = memory.getMessages();

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
