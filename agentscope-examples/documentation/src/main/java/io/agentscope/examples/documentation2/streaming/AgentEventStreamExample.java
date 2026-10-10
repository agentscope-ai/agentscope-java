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

/*
 * ============================================================================
 * 【学习示例】事件流 - AgentEventStreamExample
 * ============================================================================
 *
 * 演示 streamEvents() 返回的 28 种 AgentEvent 事件类型。
 *
 * 1. 事件流的价值
 *    - 实时 UI 渲染（逐字输出）
 *    - 工具调用追踪（展示执行过程）
 *    - Token 用量统计
 *    - 调试和审计日志
 *
 * 2. 无工具调用时的事件序列
 *    AGENT_START
 *      MODEL_CALL_START
 *        TEXT_BLOCK_START
 *          TEXT_BLOCK_DELTA  (重复多次，每次一个token块)
 *        TEXT_BLOCK_END
 *      MODEL_CALL_END        (携带token用量)
 *    AGENT_END
 *
 * 3. 有工具调用时的额外事件
 *      TOOL_CALL_START       (工具名 + 调用ID)
 *        TOOL_CALL_DELTA     (流式工具参数)
 *      TOOL_CALL_END
 *      TOOL_RESULT_START
 *        TOOL_RESULT_TEXT_DELTA / TOOL_RESULT_DATA_DELTA
 *      TOOL_RESULT_END       (状态: SUCCESS/ERROR)
 *
 * 4. 处理事件的方式
 *    agent.streamEvents(userMsg)
 *        .doOnNext(event -> {
 *            if (event instanceof TextBlockDeltaEvent e) {
 *                System.out.print(e.getDelta());
 *            } else if (event instanceof ToolCallStartEvent e) {
 *                System.out.println("调用工具: " + e.getToolCallName());
 *            }
 *            // ... 其他事件类型
 *        })
 *        .blockLast();
 *
 * 5. 关键事件类型
 *    AgentStartEvent      - 代理调用开始
 *    AgentEndEvent        - 代理调用结束
 *    ModelCallStartEvent  - LLM API 调用开始
 *    ModelCallEndEvent    - LLM API 调用结束（含token用量）
 *    TextBlockDeltaEvent  - 流式文本增量
 *    ToolCallStartEvent   - 工具调用开始
 *    ToolResultEndEvent   - 工具结果完成
 *
 * 6. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.AgentEventStreamExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * AgentEventStreamExample - Demonstrates {@link ReActAgent#streamEvents} and the
 * {@link AgentEvent} hierarchy.
 *
 * <p>{@code streamEvents()} returns a {@link reactor.core.publisher.Flux}{@code <AgentEvent>}
 * that covers the full agent lifecycle: startup, each model call, every text token, tool
 * invocations, tool results, and shutdown. This gives callers the granularity needed to build
 * real-time UIs, audit logs, or cost trackers without custom middleware.
 *
 * <p><b>Event sequence for a single-turn response (no tools):</b>
 * <pre>
 *   AGENT_START
 *     MODEL_CALL_START
 *       TEXT_BLOCK_START
 *         TEXT_BLOCK_DELTA  (repeated — one per streamed token chunk)
 *       TEXT_BLOCK_END
 *     MODEL_CALL_END        (carries token usage)
 *   AGENT_END
 * </pre>
 *
 * <p><b>Additional events when a tool is called:</b>
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
                "Shows every lifecycle event emitted by streamEvents(), including tool calls.");
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

        Msg userMsg = new UserMessage("user", "What is the weather like in Beijing and Shanghai?");

        System.out.println("User: What is the weather like in Beijing and Shanghai?\n");

        // ── Subscribe to the fine-grained event stream ────────────────────────────────
        //
        // streamEvents(Msg) is a convenience overload of streamEvents(List<Msg>).
        // Each emitted AgentEvent carries:
        //   event.getType()       — AgentEventType enum value
        //   event.getId()         — unique event ID
        //   event.getCreatedAt()  — ISO-8601 timestamp
        //
        // Use instanceof to access type-specific fields.
        agent.streamEvents(userMsg).doOnNext(AgentEventStreamExample::handleEvent).blockLast();
    }

    /**
     * Dispatches an {@link AgentEvent} to a type-specific handler.
     *
     * <p>The {@code instanceof} pattern is the recommended way to consume events because
     * it gives compile-time access to typed fields without casting. An exhaustive
     * {@code switch} on {@link io.agentscope.core.event.AgentEventType} is an alternative
     * when only the type discriminator is needed.
     *
     * @param event the event to handle
     */
    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent e) {
            // Emitted once at the very beginning of an invocation.
            // replyId correlates all subsequent events to this invocation.
            System.out.printf(
                    "[AGENT_START]        agent=%s  replyId=%s%n", e.getName(), e.getReplyId());

        } else if (event instanceof ModelCallStartEvent e) {
            // Emitted before each model (LLM) API call.
            // A multi-tool ReAct loop fires one MODEL_CALL_START per iteration.
            System.out.printf("[MODEL_CALL_START]   replyId=%s%n", e.getReplyId());

        } else if (event instanceof TextBlockDeltaEvent e) {
            // Emitted for every token chunk streamed from the model.
            // Print without newline to render the response incrementally.
            System.out.print(e.getDelta());

        } else if (event instanceof ModelCallEndEvent e) {
            // Emitted after the model call completes.
            // ChatUsage carries input/output token counts when the model reports them.
            System.out.println();
            if (e.getUsage() != null) {
                System.out.printf(
                        "[MODEL_CALL_END]     inputTokens=%d  outputTokens=%d%n",
                        e.getUsage().getInputTokens(), e.getUsage().getOutputTokens());
            } else {
                System.out.println("[MODEL_CALL_END]");
            }

        } else if (event instanceof ToolCallStartEvent e) {
            // Emitted when the model requests a tool invocation.
            // toolCallId correlates ToolCallStart/End and ToolResultStart/End pairs.
            System.out.printf(
                    "[TOOL_CALL_START]    tool=%s  callId=%s%n",
                    e.getToolCallName(), e.getToolCallId());

        } else if (event instanceof ToolCallEndEvent e) {
            System.out.printf("[TOOL_CALL_END]      callId=%s%n", e.getToolCallId());

        } else if (event instanceof ToolResultEndEvent e) {
            // Emitted after the tool result has been produced.
            // ToolResultState is SUCCESS or ERROR.
            System.out.printf(
                    "[TOOL_RESULT_END]    callId=%s  state=%s%n", e.getToolCallId(), e.getState());

        } else if (event instanceof AgentEndEvent e) {
            // Emitted once after the agent finishes all iterations.
            System.out.printf("[AGENT_END]          replyId=%s%n", e.getReplyId());

        } else {
            // All other events (TEXT_BLOCK_START/END, TOOL_RESULT_START, THINKING_BLOCK_*, etc.)
            // are silently ignored in this example but are available for more advanced use cases.
            System.out.printf("[%-24s] (skipped)%n", event.getType());
        }
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
