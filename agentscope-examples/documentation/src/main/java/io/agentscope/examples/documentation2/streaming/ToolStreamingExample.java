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
 * 【学习示例】工具流式输出 - ToolStreamingExample
 * ============================================================================
 *
 * 演示工具通过 ToolEmitter 发送流式进度更新。
 *
 * 1. 工具流式输出概念
 *    - 工具可以在执行过程中发送中间状态
 *    - 这些状态仅用于 UI 展示，不会发送给 LLM
 *    - 最终 return 值才是 LLM 看到的工具结果
 *
 * 2. ToolEmitter 声明
 *    工具方法声明 ToolEmitter 参数，框架自动注入：
 *
 *    @Tool(description = "Research a topic")
 *    public String research_topic(
 *        @ToolParam(name = "topic") String topic,
 *        ToolEmitter emitter) {  // 自动注入，无需 @ToolParam
 *
 *        emitter.emit(ToolResultBlock.text("Step 1..."));
 *        // ... 执行
 *        emitter.emit(ToolResultBlock.text("Step 2..."));
 *        // ...
 *        return "Final result for LLM";
 *    }
 *
 * 3. 事件序列
 *    ┌────────────────────────────────────────────┐
 *    │  TOOL_RESULT_START                         │
 *    │     (工具开始执行)                          │
 *    │                                            │
 *    │  TOOL_RESULT_TEXT_DELTA                    │
 *    │     ("Searching for information...")       │
 *    │                                            │
 *    │  TOOL_RESULT_TEXT_DELTA                    │
 *    │     ("Found 12 sources. Analyzing...")     │
 *    │                                            │
 *    │  TOOL_RESULT_TEXT_DELTA                    │
 *    │     ("Cross-referencing findings...")      │
 *    │                                            │
 *    │  TOOL_RESULT_END                           │
 *    │     (state=SUCCESS, final result → LLM)    │
 *    └────────────────────────────────────────────┘
 *
 * 4. 事件处理
 *    agent.streamEvents(msg)
 *        .doOnNext(event -> {
 *            if (event instanceof ToolResultStartEvent e) {
 *                // 工具开始
 *            } else if (event instanceof ToolResultTextDeltaEvent e) {
 *                // 流式文本进度（来自 emitter.emit()）
 *                System.out.print(e.getDelta());
 *            } else if (event instanceof ToolResultDataDeltaEvent e) {
 *                // 非文本数据块（图片、二进制等）
 *            } else if (event instanceof ToolResultEndEvent e) {
 *                // 工具完成，state=SUCCESS/ERROR
 *            }
 *        })
 *        .blockLast();
 *
 * 5. emit() vs return 的区别
 *    ┌──────────────────────────────────────────┐
 *    │  emitter.emit(...)                       │
 *    │    - 发送到事件流                         │
 *    │    - UI 可见                             │
 *    │    - LLM 不可见                          │
 *    ├──────────────────────────────────────────┤
 *    │  return "..."                            │
 *    │    - 工具最终结果                         │
 *    │    - 发送给 LLM                          │
 *    │    - 也可能显示在 UI                     │
 *    └──────────────────────────────────────────┘
 *
 * 6. 数据类型支持
 *    emitter.emit(ToolResultBlock.text("..."));     // 文本
 *    emitter.emit(ToolResultBlock.image(bytes));    // 图片
 *    emitter.emit(ToolResultBlock.data(object));    // 结构化数据
 *
 * 7. 适用场景
 *    - 长时间操作的进度展示
 *    - 多步骤处理的阶段反馈
 *    - 数据处理的中间结果
 *    - 搜索过程的实时状态
 *
 * 8. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.ToolStreamingExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * ToolStreamingExample - Demonstrates how tools emit streaming progress via {@link ToolEmitter}
 * and how the caller receives them as {@link ToolResultTextDeltaEvent} / {@link
 * ToolResultDataDeltaEvent} through {@code streamEvents()}.
 *
 * <p><b>How tool streaming works:</b>
 *
 * <ol>
 *   <li>A {@code @Tool} method declares a {@link ToolEmitter} parameter (no annotation needed —
 *       the framework auto-injects it).
 *   <li>During execution, the tool calls {@code emitter.emit(ToolResultBlock.text(...))} to push
 *       intermediate progress chunks.
 *   <li>The framework routes each chunk to the event stream as a {@link ToolResultTextDeltaEvent}
 *       or {@link ToolResultDataDeltaEvent}.
 *   <li>The tool's final {@code return} value becomes the tool result sent to the LLM — emitted
 *       chunks are NOT sent to the LLM (they are for the UI only).
 * </ol>
 *
 * <p><b>Event sequence for a streaming tool call:</b>
 * <pre>
 *   TOOL_RESULT_START        (tool execution begins)
 *     TOOL_RESULT_TEXT_DELTA  ("Step 1: Fetching data...")
 *     TOOL_RESULT_TEXT_DELTA  ("Step 2: Processing...")
 *     TOOL_RESULT_TEXT_DELTA  ("Step 3: Formatting results...")
 *   TOOL_RESULT_END          (state=SUCCESS, final result sent to LLM)
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.ToolStreamingExample
 * </pre>
 */
public class ToolStreamingExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Streaming Example");
        System.out.println("=".repeat(60));
        System.out.println("Shows how tools emit streaming progress via ToolEmitter.");
        System.out.println("=".repeat(60) + "\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ResearchTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ResearchAgent")
                        .sysPrompt(
                                "You are a research assistant. Use the research_topic tool to"
                                        + " investigate topics.")
                        .model("dashscope:qwen-plus")
                        .toolkit(toolkit)
                        .build();

        Msg userMsg = new UserMessage("user", "Research the current state of quantum computing");

        System.out.println("User: Research the current state of quantum computing\n");
        System.out.println("─".repeat(60));

        agent.streamEvents(userMsg).doOnNext(ToolStreamingExample::handleEvent).blockLast();

        System.out.println("\n" + "─".repeat(60));
        System.out.println("Done.");
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof ToolResultStartEvent e) {
            System.out.printf(
                    "%n[Tool Started] %s (id=%s)%n", e.getToolCallName(), e.getToolCallId());

        } else if (event instanceof ToolResultTextDeltaEvent e) {
            // Streaming text chunks from the tool — display them incrementally.
            // These come from ToolEmitter.emit() calls inside the tool method.
            System.out.println("  ▸ " + e.getDelta());

        } else if (event instanceof ToolResultDataDeltaEvent e) {
            // Non-text data blocks (images, binary, structured data).
            System.out.printf("  ▸ [data block: %s]%n", e.getData().getClass().getSimpleName());

        } else if (event instanceof ToolResultEndEvent e) {
            System.out.printf("[Tool Finished] id=%s  state=%s%n", e.getToolCallId(), e.getState());
        }
    }

    /**
     * Example tool class that uses {@link ToolEmitter} to stream progress updates.
     *
     * <p>The key pattern: declare a {@code ToolEmitter} parameter in your @Tool method.
     * The framework injects it automatically — no @ToolParam annotation needed. Call
     * {@code emitter.emit(...)} during execution to push intermediate results to the
     * event stream.
     */
    public static class ResearchTools {

        @Tool(description = "Research a topic and return a summary with streaming progress updates")
        public String research_topic(
                @ToolParam(name = "topic", description = "The topic to research") String topic,
                ToolEmitter emitter) {

            // Step 1: emit progress
            emitter.emit(ToolResultBlock.text("Searching for information on: " + topic));
            sleep(500);

            // Step 2: emit progress
            emitter.emit(ToolResultBlock.text("Found 12 relevant sources. Analyzing..."));
            sleep(500);

            // Step 3: emit progress
            emitter.emit(ToolResultBlock.text("Cross-referencing findings..."));
            sleep(500);

            // Step 4: emit progress
            emitter.emit(ToolResultBlock.text("Generating summary..."));
            sleep(300);

            // The final return value is what the LLM sees as the tool result.
            // All emitter.emit() calls above are for the UI only.
            return "Research summary for '"
                    + topic
                    + "': Quantum computing has made significant progress"
                    + " in 2025-2026, with major advances in error correction (Google's"
                    + " Willow chip achieving below-threshold error rates), increased qubit"
                    + " counts (IBM's 1000+ qubit processors), and early commercial"
                    + " applications in drug discovery and materials science.";
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
