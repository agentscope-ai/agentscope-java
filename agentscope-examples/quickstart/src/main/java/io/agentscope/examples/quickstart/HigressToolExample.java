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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import io.agentscope.extensions.higress.HigressToolkit;

/**
 * HigressToolExample - Demonstrates Higress AI Gateway integration with AgentScope.
 *
 * <p>Usage: Set DASHSCOPE_API_KEY environment variable before running.
 */
public class HigressToolExample {

    // Higress endpoint - replace with your own
    private static final String HIGRESS_ENDPOINT = "your higress endpoint";

    public static void main(String[] args) throws Exception {
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // 1. Create Higress MCP client
        HigressMcpClientWrapper higressClient =
                HigressMcpClientBuilder.create("higress")
                        .streamableHttpEndpoint(HIGRESS_ENDPOINT)
                        // .sseEndpoint(HIGRESS_ENDPOINT + "/sse")  // Alternative: SSE transport
                        // .header("Authorization", "Bearer xxx")   // Optional: Add auth header
                        // .queryParam("queryKey", "queryValue")   // Optional: Add query param
                        .toolSearch("your agent description", 5) // Optional: Enable tool search
                        .buildAsync()
                        .block();

        // 2. Register with HigressToolkit
        Toolkit toolkit = new HigressToolkit();
        toolkit.registerMcpClient(higressClient).block();

        // 3. Create agent with toolkit
        ReActAgent agent =
                ReActAgent.builder()
                        .name("HigressAgent")
                        .sysPrompt(
                                "You are a helpful assistant. Please answer questions concisely and"
                                        + " accurately.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        // 4. Start chat
        ExampleUtils.startChat(agent);
    }
}
