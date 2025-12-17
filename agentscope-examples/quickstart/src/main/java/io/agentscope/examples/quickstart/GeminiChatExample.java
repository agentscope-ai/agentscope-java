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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;

/**
 * GeminiChatExample - An Agent conversation example using Google Gemini.
 */
public class GeminiChatExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Gemini Chat Example",
                "This example demonstrates the simplest Agent setup.\n"
                        + "You'll chat with an AI assistant powered by Google Gemini.");

        // Get API key
        String apiKey =
                ExampleUtils.getApiKey(
                        "GEMINI_API_KEY", "Gemini", "https://aistudio.google.com/app/apikey");

        // Create Agent with minimal configuration
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model(
                                GeminiChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("gemini-3-pro-preview")
                                        .streamEnabled(true)
                                        .formatter(new GeminiChatFormatter())
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(1024)
                                                        .build())
                                        .build())
                        .memory(new InMemoryMemory())
                        
                        .toolkit(new Toolkit())
                        .build();

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }
}
