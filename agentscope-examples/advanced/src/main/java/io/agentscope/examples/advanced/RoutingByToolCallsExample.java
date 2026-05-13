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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.advanced.util.MsgUtils;

public class RoutingByToolCallsExample {

    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());
        ReActAgent routerImplicit =
                ReActAgent.builder()
                        .name("RouterImplicit")
                        .sysPrompt(
                                "You're a routing agent. Your target is to route the user query to"
                                        + " the right follow-up task.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(ExampleUtils.getDashScopeApiKey())
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(512)
                                                        .build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(toolkit)
                        .build();

        // Example of implicit routing with tool calls.
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("Help me to generate a quick sort function in Python")
                                        .build())
                        .build();
        try {
            Msg response = routerImplicit.call(userMsg).block();
            if (response != null) {
                System.out.println("Agent> " + MsgUtils.getTextContent(response) + "\n");
            } else {
                System.out.println("Agent> [No response]\n");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static class SimpleTools {
        /**
         * Generate Python code based on the demand.
         *
         * @param demand The demand for the Python code.
         */
        @Tool(
                name = "generate_Python_code",
                description = "Generate Python code based on the demand")
        public Msg generatePython(
                @ToolParam(name = "demand", description = "The demand for the Python code")
                        String demand) {
            System.out.println("I am PythonAgent,now generating Python code for demand: " + demand);
            String apiKey = ExampleUtils.getDashScopeApiKey();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PythonAgent")
                            .sysPrompt(
                                    "You're a Python expert, your target is to generate Python code"
                                            + " based on the demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(true)
                                            .formatter(new DashScopeChatFormatter())
                                            .defaultOptions(
                                                    GenerateOptions.builder()
                                                            .thinkingBudget(1024)
                                                            .build())
                                            .build())
                            .memory(new InMemoryMemory())
                            .toolkit(new Toolkit())
                            .build();

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(demand).build())
                            .build();
            return agent.call(userMsg).block();
        }

        /**
         * Generate a poem based on the demand.
         *
         * @param demand The demand for the poem.
         */
        @Tool(name = "generate_poem", description = "Generate a poem based on the demand")
        public Msg generatePoem(
                @ToolParam(name = "demand", description = "The demand for the poem")
                        String demand) {
            System.out.println("I am PoemAgent,now generating a poem for demand: " + demand);
            String apiKey = ExampleUtils.getDashScopeApiKey();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PoemAgent")
                            .sysPrompt(
                                    "You're a poet, your target is to generate poems based on the"
                                            + " demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(true)
                                            .formatter(new DashScopeChatFormatter())
                                            .defaultOptions(
                                                    GenerateOptions.builder()
                                                            .thinkingBudget(1024)
                                                            .build())
                                            .build())
                            .memory(new InMemoryMemory())
                            .toolkit(new Toolkit())
                            .build();

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(demand).build())
                            .build();
            return agent.call(userMsg).block();
        }
    }
}
