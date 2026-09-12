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
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventStreams;
import io.agentscope.core.event.TextOutputDispositionEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * AgentEventStreamExample - Demonstrates the opt-in text output disposition wrapper on top of
 * {@link ReActAgent#streamEvents} and the {@link AgentEvent} hierarchy.
 *
 * <p>{@code streamEvents()} returns a {@link reactor.core.publisher.Flux}{@code <AgentEvent>}
 * that covers the full agent lifecycle: startup, each model call, every text token, tool
 * invocations, tool results, and shutdown. {@link AgentEventStreams#withTextOutputDisposition}
 * preserves those events and derives lifecycle signals that classify streamed text as intermediate
 * or terminal. This example prints only those derived disposition events; callers can inspect the
 * unchanged underlying events in the same callback when needed.
 *
 * <p><b>Event sequence for a single-turn response (no tools):</b>
 * <pre>
 *   AGENT_START
 *     MODEL_CALL_START
 *       TEXT_BLOCK_START
 *         TEXT_BLOCK_DELTA  (repeated — one per streamed token chunk)
 *       TEXT_BLOCK_END
 *     MODEL_CALL_END        (carries token usage)
 *   AGENT_RESULT            (authoritative invocation result)
 *   TEXT_OUTPUT_DISPOSITION (TERMINAL, derived by the opt-in wrapper)
 *   AGENT_END
 * </pre>
 *
 * <p><b>Additional underlying events when a tool is called:</b>
 * <pre>
 *     TOOL_CALL_START       (tool name + call ID)
 *       TOOL_CALL_DELTA     (optional — streamed tool input)
 *     TOOL_CALL_END
 *     TOOL_RESULT_START
 *       TOOL_RESULT_TEXT_DELTA / TOOL_RESULT_DATA_DELTA
 *     TOOL_RESULT_END       (carries ToolResultState: SUCCESS / ERROR)
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.AgentEventStreamExample
 * </pre>
 */
public class AgentEventStreamExample {

    /**
     * Runs the agent-event stream demonstration.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AgentEvent Stream Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Prints derived text dispositions while preserving the underlying event stream.");
        System.out.println("=".repeat(60) + "\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("WeatherAgent")
                        .sysPrompt("You are a helpful assistant. Use tools when appropriate.")
                        .model("dashscope:qwen-plus")
                        .toolkit(toolkit)
                        .build();

        Msg input = new UserMessage("user", "What is the weather like in Beijing and Shanghai?");

        System.out.println("User: What is the weather like in Beijing and Shanghai?\n");

        // ── Subscribe to the fine-grained event stream ────────────────────────────────
        //
        // streamEvents(Msg) is a convenience overload of streamEvents(List<Msg>).
        // Each emitted AgentEvent carries:
        //   event.getType()       — AgentEventType enum value
        //   event.getId()         — unique event ID
        //   event.getCreatedAt()  — ISO-8601 timestamp
        //
        // TERMINAL closes the streamed text lifecycle; AgentResultEvent remains the authoritative
        // invocation result and may differ from the streamed preview.
        AgentEventStreams.withTextOutputDisposition(agent.streamEvents(input))
                .doOnNext(
                        event -> {
                            if (event instanceof TextOutputDispositionEvent disposition) {
                                System.out.printf(
                                        "%s -> %s%n",
                                        disposition.getReplyId(), disposition.getDisposition());
                            }
                        })
                .blockLast();
    }

    /** Simulated weather tool used to trigger tool-call events. */
    public static class WeatherTools {

        /**
         * Returns the current weather for the specified city.
         *
         * @param city city name to query
         * @return a short weather description
         */
        @Tool(description = "Get the current weather for a city")
        public String get_weather(
                @ToolParam(name = "city", description = "City name") String city) {
            // Simulated — a real implementation would call a weather API
            return switch (city.toLowerCase()) {
                case "beijing" -> "Beijing: 28°C, partly cloudy";
                case "shanghai" -> "Shanghai: 32°C, humid and sunny";
                default -> city + ": 25°C, clear skies";
            };
        }
    }
}
