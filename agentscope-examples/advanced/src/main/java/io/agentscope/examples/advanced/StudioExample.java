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
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioUserAgent;

/**
 * Simple example demonstrating AgentScope Studio integration.
 */
public class StudioExample {

    public static void main(String[] args) throws Exception {
        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        System.out.println("Starting Studio Example...\n");

        // Initialize Studio
        System.out.println("Connecting to Studio at http://localhost:3000...");
        StudioManager.init()
                .studioUrl("http://localhost:3000")
                .project("JavaExamples")
                .runName("studio_demo_" + System.currentTimeMillis())
                .initialize()
                .block();
        System.out.println("Connected to Studio\n");

        // Create agent with Studio hook
        System.out.println("Creating agent with Studio integration...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .build();
        System.out.println("Agent created\n");

        // Create user agent
        System.out.println("Creating user agent...");
        StudioUserAgent user =
                StudioUserAgent.builder()
                        .name("User")
                        .studioClient(StudioManager.getClient())
                        .webSocketClient(StudioManager.getWebSocketClient())
                        .build();
        System.out.println("User agent ready\n");

        // Conversation loop
        System.out.println("Starting conversation (type 'exit' to quit)");
        System.out.println("Open http://localhost:3000 to interact\n");

        try {
            Msg msg = null;
            int turn = 1;
            while (true) {
                System.out.println("[Turn " + turn + "] Waiting for user input...");
                msg = user.call(msg).block();

                if (msg == null || "exit".equalsIgnoreCase(msg.getTextContent())) {
                    System.out.println("\nConversation ended");
                    break;
                }

                System.out.println("[Turn " + turn + "] User: " + msg.getTextContent());
                System.out.println("[Turn " + turn + "] Agent thinking...");

                msg = agent.call(msg).block();

                if (msg != null) {
                    System.out.println("[Turn " + turn + "] Agent: " + msg.getTextContent() + "\n");
                }

                turn++;
            }
        } finally {
            System.out.println("\nShutting down...");
            StudioManager.shutdown();
            System.out.println("Done\n");
        }
    }
}
