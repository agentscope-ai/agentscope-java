/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.agui.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.agui.tools.ExampleTools;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that registers agents with the AG-UI registry.
 *
 * <p>This example demonstrates how to register multiple agents with different IDs.
 * Clients can select which agent to use via:
 * <ul>
 *   <li>URL path variable: {@code POST /agui/run/{agentId}}</li>
 *   <li>HTTP header: {@code X-Agent-Id: agentId}</li>
 *   <li>Request body: {@code forwardedProps.agentId}</li>
 * </ul>
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer =
                registry -> {
                    // Register a factory for the default agent
                    // Using a factory ensures each request gets a fresh agent instance
                    registry.registerFactory("default", this::createDefaultAgent);

                    // Register additional agents with different IDs
                    // Example: a simple chat agent without tools
                    registry.registerFactory("chat", this::createChatAgent);

                    // Example: an agent specialized for calculations
                    registry.registerFactory("calculator", this::createCalculatorAgent);
                };

        System.out.println("Registered agents with AG-UI registry: default, chat, calculator");
        System.out.println("Access agents via:");
        System.out.println("  - POST /agui/run (uses default-agent-id from config)");
        System.out.println("  - POST /agui/run/chat (uses 'chat' agent)");
        System.out.println("  - POST /agui/run with X-Agent-Id header");

        return aguiAgentRegistryCustomizer;
    }

    /**
     * Create the default agent instance.
     *
     * <p>This agent is configured with:
     * <ul>
     *   <li>DashScope qwen-plus model with streaming enabled</li>
     *   <li>Example tools (get_weather, calculate)</li>
     *   <li>In-memory conversation memory</li>
     * </ul>
     */
    private Agent createDefaultAgent() {
        String apiKey = getRequiredApiKey();

        // Create toolkit with example tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        // Create the agent
        return ReActAgent.builder()
                .name("AG-UI Assistant")
                .sysPrompt(
                        "You are a helpful AI assistant exposed via the AG-UI protocol. "
                                + "You can help users with various tasks including weather queries "
                                + "and calculations. Be concise and helpful in your responses.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(10)
                .build();
    }

    /**
     * Create a simple chat agent without tools.
     *
     * <p>This agent is a pure conversational assistant.
     */
    private Agent createChatAgent() {
        String apiKey = getRequiredApiKey();

        return ReActAgent.builder()
                .name("Chat Assistant")
                .sysPrompt(
                        "You are a friendly conversational assistant. "
                                + "Engage in natural conversation and help users "
                                + "with general questions and discussions.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .maxIters(1)
                .build();
    }

    /**
     * Create a calculator agent specialized for mathematical operations.
     */
    private Agent createCalculatorAgent() {
        String apiKey = getRequiredApiKey();

        // Create toolkit with only calculation tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        return ReActAgent.builder()
                .name("Calculator Agent")
                .sysPrompt(
                        "You are a mathematical assistant specialized in calculations. "
                                + "Use the calculate tool to perform mathematical operations. "
                                + "Always show your work and explain the results.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(5)
                .build();
    }

    private String getRequiredApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable is required. "
                            + "Please set it before starting the application.");
        }
        return apiKey;
    }
}
