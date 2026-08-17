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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * ToolBaseExample - Demonstrates how to implement a custom tool by extending {@link ToolBase}.
 *
 * <p>{@link ToolBase} is the preferred base class when you need to:
 * <ul>
 *   <li>Plug into the permission engine ({@code checkPermissions}, {@code generateSuggestions})</li>
 *   <li>Return structured results from an async Mono pipeline ({@code callAsync})</li>
 *   <li>Declare safety flags ({@code readOnly}, {@code concurrencySafe})</li>
 * </ul>
 *
 * <p>Contrast with the simpler {@code @Tool} annotation approach:
 * <ul>
 *   <li>Use {@code @Tool} on a POJO method for simple, synchronous, always-allowed tools.</li>
 *   <li>Extend {@code ToolBase} when you need permission checks, async execution, or
 *       structured metadata.</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.tool.ToolBaseExample
 * </pre>
 */
public class ToolBaseExample {

    /**
     * Runs the ToolBase example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ToolBase Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates a custom tool that extends ToolBase.\n"
                        + "The temperature tool will ask for confirmation when reading\n"
                        + "a location considered outside the safe allow-list.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new TemperatureTool());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("WeatherAgent")
                        .sysPrompt("You are a weather assistant. Use the temperature tool.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        System.out.println(
                "Try: 'What is the temperature in Shanghai?' or 'Get temperature for"
                        + " /etc/passwd'\n");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    /**
     * Custom tool that extends {@link ToolBase} to participate in permission evaluation.
     *
     * <p>The tool uses an explicit input schema (a raw JSON Schema map) rather than
     * annotation-driven schema generation, which is required when extending {@code ToolBase}.
     */
    public static class TemperatureTool extends ToolBase {

        /**
         * Constructs the TemperatureTool by providing metadata via the {@link ToolBase.Builder}.
         */
        public TemperatureTool() {
            super(
                    ToolBase.builder()
                            .name("get_temperature")
                            .description("Get the current temperature for a city or location")
                            .inputSchema(
                                    Map.of(
                                            "type", "object",
                                            "properties",
                                                    Map.of(
                                                            "location",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "description",
                                                                    "City or region name,"
                                                                            + " e.g."
                                                                            + " 'Shanghai'")),
                                            "required", List.of("location")))
                            .readOnly(true) // does not modify any state
                            .concurrencySafe(true)); // safe to call in parallel
        }

        /**
         * Performs the actual temperature lookup.
         *
         * <p>In a real implementation this would call a weather API. Here it simulates
         * with a fixed response for demonstration purposes.
         *
         * @param param tool invocation parameters (input, context, etc.)
         * @return Mono emitting a ToolResultBlock with the temperature string
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String location = (String) param.getInput().getOrDefault("location", "unknown");
            String callId =
                    param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
            String result = "Temperature in " + location + ": 22°C (simulated)";
            ToolResultBlock block =
                    new ToolResultBlock(
                            callId, getName(), List.of(TextBlock.builder().text(result).build()));
            return Mono.just(block);
        }

        /**
         * Permission self-check for the temperature tool.
         *
         * <p>Locations containing suspicious path separators are denied. All other
         * queries pass through to the engine's rule-based defaults.
         *
         * @param toolInput   parsed tool arguments
         * @param context     current permission evaluation context
         * @return allow, deny, or passthrough decision
         */
        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            String location = (String) toolInput.getOrDefault("location", "");
            if (location.contains("/") || location.contains("\\")) {
                return Mono.just(
                        PermissionDecision.deny(
                                "Suspicious path in location argument: '" + location + "'"));
            }
            // Pass to engine defaults for everything else
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        /**
         * Suggests a location-specific allow rule once the user approves a query.
         *
         * @param toolInput parsed tool arguments
         * @return list of suggested permission rules
         */
        @Override
        public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
            String location = (String) toolInput.getOrDefault("location", "");
            String pattern = location.isBlank() ? null : "location:" + location;
            return List.of(
                    new PermissionRule(getName(), pattern, PermissionBehavior.ALLOW, "suggested"));
        }
    }
}
