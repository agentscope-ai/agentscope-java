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
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * auto memory example
 */
public class AutoMemoryExample {

    public static void main(String[] args) {

        String apiKey = ExampleUtils.getDashScopeApiKey();

        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen3-max-preview").stream(
                                true)
                        .enableThinking(true)
                        .formatter(new DashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder().thinkingBudget(1024).build())
                        .build();

        // goto https://app.mem0.ai/dashboard/settings?tab=api-keys to get a playground api key.
        Mem0LongTermMemory.Builder builder =
                Mem0LongTermMemory.builder()
                        .apiKey(ExampleUtils.getMem0ApiKey())
                        .userId("example-user") // Use a placeholder user ID for example code
                        .apiBaseUrl("https://api.mem0.ai");
        Mem0LongTermMemory longTermMemory = builder.build();
        AutoContextConfig autoContextConfig =
                AutoContextConfig.builder().tokenRatio(0.1).lastKeep(20).build();
        AutoContextMemory memory = new AutoContextMemory(autoContextConfig, chatModel);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant. Be friendly and concise. Respond"
                                        + " to user using the language that user asks.")
                        .model(chatModel)
                        .memory(memory)
                        .maxIters(50)
                        .longTermMemory(longTermMemory)
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .enablePlan()
                        .toolkit(toolkit)
                        .hook(new AutoContextHook()) // Register the hook for automatic setup
                        .build();
        String sessionId = "123453344";
        // Set up session path
        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        Session session = new JsonSession(sessionPath);

        // Load existing session if it exists
        agent.loadIfExists(session, sessionId);

        Scanner scanner = new Scanner(System.in);
        System.out.println("🚀 Auto Memory Example Started!");
        System.out.println("Enter your query (type 'exit' to quit):\n");

        try {
            while (true) {
                System.out.print("You: ");
                String query = scanner.nextLine().trim();

                // Check if user wants to exit
                if ("exit".equalsIgnoreCase(query)) {
                    System.out.println("👋 Goodbye!");
                    break;
                }

                // Skip empty input
                if (query.isEmpty()) {
                    System.out.println("Please enter a valid query.\n");
                    continue;
                }

                // Create user message
                Msg userMsg =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(query).build())
                                .build();

                // Call agent and get response
                System.out.println("\n🤔 Processing...\n");
                Msg response = agent.call(userMsg).block();

                // Output response
                System.out.println("Assistant: " + response.getTextContent() + "\n");
                agent.saveTo(session, sessionId);
            }

        } catch (Throwable e) {
            System.out.println("error save session: " + e.getMessage());

        } finally {
            System.out.println("save session: ");

            agent.saveTo(session, sessionId);
        }
        scanner.close();
    }
}
