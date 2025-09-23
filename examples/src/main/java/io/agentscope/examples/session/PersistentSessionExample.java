/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.examples.session;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple JsonSession example for persistent conversations.
 *
 * Usage: java PersistentSessionExample [session_id]
 * Commands: 'exit' to quit, 'history' to show chat history
 */
public class PersistentSessionExample {

    private static final Logger log = LoggerFactory.getLogger(PersistentSessionExample.class);
    private static final String DEFAULT_SESSION_ID = "default_session";

    public static void main(String[] args) throws Exception {
        String dashApiKey = System.getenv("DASHSCOPE_API_KEY");

        if (dashApiKey == null || dashApiKey.isEmpty()) {
            log.warn("No API key found. Please set DASHSCOPE_API_KEY environment variable.");
            return;
        }

        // Get session ID from command line args or use default
        String sessionId = args.length > 0 ? args[0] : DEFAULT_SESSION_ID;
        log.info("Using session ID: {}", sessionId);

        // Initialize JsonSession with custom directory
        JsonSession session = new JsonSession(
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions"));

        // Create memory and agent components
        InMemoryMemory memory = new InMemoryMemory();
        Toolkit toolkit = new Toolkit();

        ReActAgent agent = ReActAgent.builder()
                .name("Friday")
                .sysPrompt("You are Friday, a helpful AI assistant with persistent memory.")
                .toolkit(toolkit)
                .memory(memory)
                .model(DashScopeChatModel.builder()
                        .apiKey(dashApiKey)
                        .modelName("qwen-max")
                        .stream(true)
                        .enableThinking(false)
                        .defaultOptions(new GenerateOptions())
                        .build())
                .formatter(new DashScopeChatFormatter())
                .build();

        // Create state modules map for session management
        Map<String, StateModule> stateModules = new HashMap<>();
        stateModules.put("agent", agent);
        stateModules.put("memory", memory);

        // Load existing session if it exists
        try {
            boolean sessionExists = session.sessionExists(sessionId);
            if (sessionExists) {
                session.loadSessionState(sessionId, stateModules);
                System.out.println("Session loaded: " + sessionId + " (" + memory.getMessages().size() + " messages)");
            } else {
                System.out.println("New session: " + sessionId);
            }
        } catch (Exception e) {
            System.out.println("New session: " + sessionId);
        }

        // Start interactive conversation loop
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Type 'exit' to quit.");

        while (true) {
            System.out.print("User> ");
            String line = reader.readLine();

            if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                break;
            }

            try {
                // Send user message to agent
                Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(line).build();
                Msg response = agent.reply(userMsg).block();

                if (response != null) {
                    System.out.println("Friday> " + response.getContentAsText());
                } else {
                    System.out.println("Friday> [No response received]");
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
            System.out.println("Session saved. Resume with: " + sessionId);
        } catch (Exception e) {
            log.error("Failed to save final session state", e);
            System.err.println("Warning: Failed to save session");
        }
    }
}
