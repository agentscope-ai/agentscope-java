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
package io.agentscope.core.tool.method;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ExampleConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool Example - How to use ReActAgent with tools.
 *
 * This example demonstrates:
 * 1. Creating tools using @Tool annotation
 * 2. Registering tools with Toolkit
 * 3. Agent automatically calling tools when needed
 * 4. Tool execution and result handling
 *
 * Usage scenarios:
 * - Building agents that can perform actions
 * - Integrating external APIs and services
 * - Creating utility functions for agents
 */
public class MethodToolExample {

    private static final Logger log = LoggerFactory.getLogger(MethodToolExample.class);

    public static void main(String[] args) {
        ExampleConfig config = ExampleConfig.getInstance();
        config.printConfiguration();

        if (!config.isValidApiKey()) {
            log.warn("⚠️  No valid API key found!");
            log.info("Please set your OpenAI API key:");
            log.info("export OPENAI_API_KEY=your-key-here");
            return;
        }

        try {
            log.info("💬 Testing tool usage with ReActAgent...\n");

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MethodTool()); // Registering the current class with tools
            InMemoryMemory memory = new InMemoryMemory();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("ToolAgent")
                            .sysPrompt("You are a helpful assistant that can use tools.")
                            .toolkit(toolkit)
                            .memory(memory)
                            .build();

            String[] requests = {
                "Get current time",
            };

            for (String request : requests) {
                log.info("👤 User: {}", request);
                Msg userMessage =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(request).build())
                                .build();
                Msg response = agent.call(userMessage).block();
                log.info(
                        "🤖 Agent: {}",
                        response.getContent().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> ((TextBlock) block).getText())
                                .collect(Collectors.joining("\n")));
            }

            log.info("✅ Tool example completed successfully!");

        } catch (Exception e) {
            log.error("❌ Error: {}", e.getMessage(), e);
            log.info("💡 Tip: Check your API key and internet connection.");
        }
    }
}
