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
package io.agentscope.examples.chat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating a conversation between a ReActAgent (Friday) and a UserAgent.
 * This mirrors the Python conversation logic where the agents take turns communicating.
 */
public class ConversationExample {

    private static final Logger log = LoggerFactory.getLogger(ConversationExample.class);

    public static void main(String[] args) {
        String dashApiKey = System.getenv("DASHSCOPE_API_KEY");

        if (dashApiKey == null || dashApiKey.isEmpty()) {
            log.warn("No API key found. Please set DASHSCOPE_API_KEY environment variable.");
            System.out.println("Please set DASHSCOPE_API_KEY environment variable to run this example.");
            return;
        }

        try {
            runConversation(dashApiKey);
        } catch (Exception e) {
            log.error("Error running conversation", e);
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * Run a simple conversation between Friday (ReActAgent) and User (UserAgent).
     * This corresponds to the Python async def run_conversation() logic.
     */
    private static void runConversation(String apiKey) {
        // Create shared memory for both agents
        InMemoryMemory memory = new InMemoryMemory();

        // Create Friday - ReActAgent
        Toolkit toolkit = new Toolkit();
        ReActAgent friday = ReActAgent.builder()
                .name("Friday")
                .sysPrompt("You are a helpful AI assistant named Friday. You are having a conversation with a user. " +
                          "Be friendly, helpful, and engaging. Keep your responses concise but informative.")
                .toolkit(toolkit)
                .memory(memory)
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen-max")
                        .stream(true)
                        .enableThinking(false)
                        .defaultOptions(new GenerateOptions())
                        .build())
                .formatter(new DashScopeChatFormatter())
                .build();

        // Create User - UserAgent
        UserAgent user = new UserAgent("User", memory);

        System.out.println("=== Conversation between Friday (AI) and User ===");
        System.out.println("Friday will start the conversation. Type 'exit' to end the conversation.");
        System.out.println("================================================");

        // Start the conversation loop
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("").build();

        while (true) {
            try {
                // Friday's turn - equivalent to: msg = await friday(msg)
                System.out.println("\n--- Friday is thinking... ---");
                msg = friday.reply(msg).block();

                if (msg != null) {
                    System.out.println("Friday: " + getTextContent(msg));
                } else {
                    System.out.println("Friday: [No response]");
                    break;
                }

                // User's turn - equivalent to: msg = await user(msg)
                System.out.println("\n--- Your turn ---");
                msg = user.reply(msg).block();

                if (msg != null) {
                    String userText = getTextContent(msg);
                    System.out.println("User: " + userText);

                    // Check for exit condition - equivalent to: if msg.get_text_content() == "exit":
                    if ("exit".equalsIgnoreCase(userText.trim())) {
                        System.out.println("\nGoodbye! Thanks for the conversation!");
                        break;
                    }
                } else {
                    System.out.println("User: [No input]");
                    break;
                }

            } catch (Exception e) {
                log.error("Error in conversation loop", e);
                System.err.println("Conversation error: " + e.getMessage());
                break;
            }
        }
    }

    /**
     * Extract text content from a message, similar to Python's get_text_content().
     */
    private static String getTextContent(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }

        if (msg.getContent() instanceof TextBlock) {
            return ((TextBlock) msg.getContent()).getText();
        }

        // Fallback to toString for other content types
        return msg.getContent().toString();
    }
}
