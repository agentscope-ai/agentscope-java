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
package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.common.ExampleUtils;

/**
 * BasicChatExample - The simplest Agent conversation example.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Removed {@code .memory(new InMemoryMemory())} — conversation history is now
 *       held internally in {@code AgentState}. Use {@code .stateStore()} to configure
 *       an external persistence back-end when needed.</li>
 * </ul>
 */
public class BasicChatExample {

    /**
     * Runs the basic chat example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs during the chat loop
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "Basic Chat Example",
                "This example demonstrates the simplest Agent setup.\n"
                        + "You'll chat with an AI assistant powered by DashScope.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        // In 2.0, conversation history is kept in AgentState automatically.
        // No .memory(new InMemoryMemory()) call is needed.
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(1024)
                                                        .build())
                                        .build())
                        .toolkit(new Toolkit())
                        .build();

        ExampleUtils.startChat(agent);
    }
}
