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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.common.ExampleUtils;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ToolCallingExample - Demonstrates equipping an Agent with custom tools.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Removed {@code .memory(new InMemoryMemory())} — not required in 2.0.</li>
 * </ul>
 */
public class ToolCallingExample {

    /**
     * Runs the tool calling example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "Tool Calling Example",
                "This example demonstrates how to equip an Agent with tools.\n"
                        + "The agent has access to: time checking, calculator, and search.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        System.out.println("Registered tools:");
        System.out.println("  - get_current_time: Get current time in a timezone");
        System.out.println("  - calculate: Evaluate simple math expressions");
        System.out.println("  - search: Simulate search functionality\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools. "
                                        + "Use tools when needed to answer questions accurately. "
                                        + "Always explain what you're doing when using tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        ExampleUtils.startChat(agent);
    }

    /**
     * Simple tools for demonstration. Each {@code @Tool}-annotated method
     * becomes a callable tool for the agent.
     */
    public static class SimpleTools {

        /**
         * Returns the current time in the specified timezone.
         *
         * @param timezone IANA timezone name, e.g. {@code "Asia/Tokyo"}
         * @return current time string
         */
        @Tool(
                name = "get_current_time",
                description = "Get the current time in a specific timezone")
        public String getCurrentTime(
                @ToolParam(
                                name = "timezone",
                                description =
                                        "Timezone name, e.g., 'Asia/Tokyo', 'America/New_York',"
                                                + " 'Europe/London'")
                        String timezone) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                LocalDateTime now = LocalDateTime.now(zoneId);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                return String.format("Current time in %s: %s", timezone, now.format(fmt));
            } catch (Exception e) {
                return "Error: Invalid timezone. Try 'Asia/Tokyo' or 'America/New_York'";
            }
        }

        /**
         * Evaluates a simple arithmetic expression.
         *
         * @param expression the expression to evaluate, e.g. {@code "123 + 456"}
         * @return the result string
         */
        @Tool(name = "calculate", description = "Calculate simple math expressions")
        public String calculate(
                @ToolParam(
                                name = "expression",
                                description = "Math expression, e.g., '123 + 456', '10 * 20'")
                        String expression) {
            try {
                String expr = expression.replaceAll("\\s+", "");
                double result;
                if (expr.contains("+")) {
                    String[] p = expr.split("\\+");
                    result = Double.parseDouble(p[0]) + Double.parseDouble(p[1]);
                } else if (expr.contains("-")) {
                    String[] p = expr.split("-");
                    result = Double.parseDouble(p[0]) - Double.parseDouble(p[1]);
                } else if (expr.contains("*")) {
                    String[] p = expr.split("\\*");
                    result = Double.parseDouble(p[0]) * Double.parseDouble(p[1]);
                } else if (expr.contains("/")) {
                    String[] p = expr.split("/");
                    result = Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
                } else {
                    return "Error: Unsupported operation. Use +, -, *, or /";
                }
                return String.format("%s = %.2f", expression, result);
            } catch (Exception e) {
                return "Error: Invalid expression. Example: '123 + 456'";
            }
        }

        /**
         * Simulates a web search and returns placeholder results.
         *
         * @param query the search query
         * @return simulated search results
         */
        @Tool(name = "search", description = "Search for information (simulated)")
        public String searchWeb(
                @ToolParam(name = "query", description = "Search query") String query) {
            return String.format(
                    "Search results for '%s':\n"
                            + "1. Example result about %s - Wikipedia\n"
                            + "2. Latest news about %s - News Site\n"
                            + "3. Documentation for %s - Official Docs\n"
                            + "\nNote: These are simulated results for demonstration purposes.",
                    query, query, query, query);
        }
    }
}
