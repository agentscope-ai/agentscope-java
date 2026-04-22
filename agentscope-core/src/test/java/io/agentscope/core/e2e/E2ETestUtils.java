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

package io.agentscope.core.e2e;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;

/**
 * Utility class for E2E testing of ReAct flows and agent interactions.
 *
 * <p>Provides helper methods for creating agents, models, tools, and common test scenarios.
 */
public class E2ETestUtils {

    private E2ETestUtils() {
        // Utility class
    }

    /**
     * Creates a ReActAgent with real DashScope model for E2E testing.
     *
     * @param agentName The name of the agent
     * @param toolkit The toolkit to use
     * @return Configured ReActAgent
     */
    public static ReActAgent createReActAgentWithRealModel(String agentName, Toolkit toolkit) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable not set for E2E tests");
        }

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .build();

        Memory memory = new InMemoryMemory();

        return ReActAgent.builder()
                .name(agentName)
                .sysPrompt("A helpful AI assistant that can use tools to solve problems")
                .model(model)
                .toolkit(toolkit)
                .memory(memory)
                .build();
    }

    /**
     * Creates a simple toolkit with math and string tools for testing.
     *
     * @return Configured toolkit
     */
    public static Toolkit createTestToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MathTools());
        toolkit.registerTool(new StringTools());
        return toolkit;
    }

    /**
     * Waits for agent response with timeout.
     *
     * @param agent The agent
     * @param input The input message
     * @param timeout The timeout duration
     * @return List of response messages
     */
    public static List<Msg> waitForResponse(ReActAgent agent, Msg input, Duration timeout) {
        return List.of(agent.call(input).block(timeout));
    }

    /**
     * Checks if response list contains expected text (case-insensitive).
     *
     * @param responses List of response messages
     * @param expectedText Expected text substring
     * @return true if any response contains the text
     */
    public static boolean responsesContain(List<Msg> responses, String expectedText) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(
                        msg -> {
                            String text = TestUtils.extractTextContent(msg);
                            return text != null
                                    && text.toLowerCase().contains(expectedText.toLowerCase());
                        });
    }

    /**
     * Checks if any response has meaningful content (>5 characters).
     *
     * @param responses List of response messages
     * @return true if at least one response has meaningful content
     */
    public static boolean hasMeaningfulContent(List<Msg> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(
                        msg -> {
                            String text = TestUtils.extractTextContent(msg);
                            return text != null && text.trim().length() > 5;
                        });
    }

    // Test tool implementations

    /** Math tools for testing. */
    public static class MathTools {
        @Tool(description = "Add two numbers together")
        public int add(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            return a + b;
        }

        @Tool(description = "Multiply two numbers")
        public int multiply(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            return a * b;
        }

        @Tool(description = "Calculate factorial of a number")
        public long factorial(
                @ToolParam(name = "n", description = "Number", required = true) int n) {
            if (n < 0) {
                throw new IllegalArgumentException("Factorial of negative number is undefined");
            }
            long result = 1;
            for (int i = 2; i <= n; i++) {
                result *= i;
            }
            return result;
        }
    }

    /** String tools for testing. */
    public static class StringTools {
        @Tool(description = "Concatenate two strings")
        public String concat(
                @ToolParam(name = "s1", description = "First string", required = true) String s1,
                @ToolParam(name = "s2", description = "Second string", required = true) String s2) {
            return s1 + s2;
        }

        @Tool(description = "Convert string to uppercase")
        public String toUpperCase(
                @ToolParam(name = "s", description = "Input string", required = true) String s) {
            return s.toUpperCase();
        }

        @Tool(description = "Get length of string")
        public int length(
                @ToolParam(name = "s", description = "Input string", required = true) String s) {
            return s.length();
        }
    }
}
