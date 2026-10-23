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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;

/**
 * RoutingByToolCallsExample - Demonstrates routing user requests to sub-agents via tool calls.
 *
 * <p>A router agent receives the user query and delegates it to a specialist sub-agent
 * by calling the appropriate tool. Each tool creates and invokes a new {@link ReActAgent}.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Removed all {@code .memory(new InMemoryMemory())} calls — not required in 2.0.</li>
 * </ul>
 */
public class RoutingByToolCallsExample {

    /**
     * Runs the routing example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent routerAgent =
                ReActAgent.builder()
                        .name("RouterImplicit")
                        .sysPrompt(
                                "You're a routing agent. Your target is to route the user query to"
                                        + " the right follow-up task.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(512)
                                                        .build())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        Msg userMsg = new UserMessage("Help me to generate a quick sort function in Python");

        try {
            Msg response = routerAgent.call(userMsg).block();
            if (response != null) {
                System.out.println("Agent> " + response.getTextContent() + "\n");
            } else {
                System.out.println("Agent> [No response]\n");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Tools that the router agent can call to delegate to specialist sub-agents. */
    public static class SimpleTools {

        /**
         * Generates Python code by delegating to a Python specialist agent.
         *
         * @param demand description of the Python code to generate
         * @return the specialist agent's response
         */
        @Tool(
                name = "generate_Python_code",
                description = "Generate Python code based on the demand")
        public Msg generatePython(
                @ToolParam(name = "demand", description = "The demand for the Python code")
                        String demand) {
            System.out.println("PythonAgent: generating code for: " + demand);
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PythonAgent")
                            .sysPrompt(
                                    "You're a Python expert. Generate Python code based on the"
                                            + " demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                            .modelName("qwen-max")
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
            Msg userMsg = new UserMessage(demand);
            return agent.call(userMsg).block();
        }

        /**
         * Generates a poem by delegating to a poetry specialist agent.
         *
         * @param demand description of the poem to generate
         * @return the specialist agent's response
         */
        @Tool(name = "generate_poem", description = "Generate a poem based on the demand")
        public Msg generatePoem(
                @ToolParam(name = "demand", description = "The demand for the poem")
                        String demand) {
            System.out.println("PoemAgent: generating poem for: " + demand);
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PoemAgent")
                            .sysPrompt("You're a poet. Generate poems based on the demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                            .modelName("qwen-max")
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
            Msg userMsg = new UserMessage(demand);
            return agent.call(userMsg).block();
        }
    }
}
