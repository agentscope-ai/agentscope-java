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
 * 【学习示例】异步工具卸载 - AsyncToolExample
 * ============================================================================
 *
 * 演示工具超时自动卸载到后台执行的机制。
 *
 * 1. 异步工具核心概念
 *    工具执行超过 asyncToolTimeout 时，自动卸载到后台：
 *
 *    HarnessAgent.builder()
 *        .asyncToolTimeout(Duration.ofSeconds(2))  // 2秒超时
 *        .build();
 *
 *    // 工具执行 5 秒 > 2 秒超时 → 自动卸载
 *
 * 2. 三种结果交付模式
 *    ┌─────────────────────────────────────────────────────────┐
 *    │  Mode A: 自动注入 (InboxMiddleware)                     │
 *    │     后台工具完成 → inbox → 下次 reasoning 时自动注入    │
 *    ├─────────────────────────────────────────────────────────┤
 *    │  Mode B: 唤醒驱动 (WakeupDispatcher)                    │
 *    │     后台工具完成 → wakeup → 触发新的 call()             │
 *    ├─────────────────────────────────────────────────────────┤
 *    │  Mode C: 主动等待 (wait_async_results 工具)             │
 *    │     代理调用 wait_async_results → 阻塞等待结果          │
 *    └─────────────────────────────────────────────────────────┘
 *
 * 3. 自动卸载流程
 *    用户请求
 *       ↓
 *    代理调用 slow_analysis (需5秒)
 *       ↓ 2秒后超时
 *    工具卸载到后台线程
 *       ↓
 *    代理立即收到 "工具在后台运行" 提示
 *       ↓
 *    代理继续响应用户
 *       ↓ 5秒后
 *    后台工具完成 → inbox 送达
 *
 * 4. 无需显式配置
 *    HarnessAgent 自动创建：
 *    - MessageBus (workspace-backed)
 *    - AsyncToolRegistry
 *    只需设置 asyncToolTimeout() 即可启用
 *
 * 5. 自动注册的工具
 *    - wait_async_results: 代理可主动调用等待后台结果
 *
 * 6. 适用场景
 *    - 大数据分析（分钟级）
 *    - 外部API调用（网络延迟）
 *    - 文件处理（大文件）
 *    - 模型推理（GPU计算）
 *
 * 7. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.async.AsyncToolExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.harness.async;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;

/**
 * Demonstrates async tool execution — tools that exceed a timeout are automatically offloaded
 * to background, and results are delivered via the inbox mechanism.
 *
 * <p>No explicit MessageBus or AsyncToolRegistry configuration is needed — HarnessAgent
 * auto-creates workspace-backed defaults from the resolved filesystem. Only
 * {@code .asyncToolTimeout()} is required to enable async tool offloading.
 *
 * <p>Three result delivery modes are supported:
 * <ul>
 *   <li><b>Mode A</b> — Results auto-injected via InboxMiddleware on next reasoning step</li>
 *   <li><b>Mode B</b> — WakeupDispatcher triggers a new call() when results arrive</li>
 *   <li><b>Mode C</b> — Agent calls {@code wait_async_results} to block until results arrive</li>
 * </ul>
 *
 * <p>The {@code slow_analysis} tool sleeps 5s; async timeout is 2s → offloaded to background.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.async.AsyncToolExample
 * </pre>
 */
public class AsyncToolExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Async Tool Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Tools exceeding the timeout are offloaded to background.\n"
                        + "MessageBus + AsyncToolRegistry are auto-created from workspace.\n");

        // ── Build agent — only asyncToolTimeout is needed ────────────────────

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("AsyncToolAgent")
                        .sysPrompt(
                                "You are an assistant with async tool capabilities.\n"
                                        + "When a tool runs in background, you have two options:\n"
                                        + "1. Call wait_async_results to wait for the result\n"
                                        + "2. Reply to the user and the result will arrive later\n"
                                        + "Choose based on the user's needs.")
                        .model("dashscope:qwen-plus")
                        .asyncToolTimeout(Duration.ofSeconds(2))
                        .build();

        agent.getToolkit().registerTool(new SlowTools());

        System.out.println("Tools: slow_analysis (5s), wait_async_results (auto-registered)");
        System.out.println("Async timeout: 2s (tool will be offloaded)\n");

        // ── Run ──────────────────────────────────────────────────────────────

        System.out.println("─── Sending: 'Analyze the performance data' ───\n");

        Msg response =
                agent.streamEvents(new UserMessage("Analyze the performance data"))
                        .doOnNext(AsyncToolExample::printEvent)
                        .filter(e -> e instanceof AgentResultEvent)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .blockLast();

        System.out.println("\n─── Agent response ───");
        System.out.println(response != null ? response.getTextContent() : "(no response)");

        // ── Wait for background result ───────────────────────────────────────

        System.out.println("\n─── Waiting 6s for background tool to finish... ───");
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("(Background result is now in inbox, persisted on workspace)");
        System.out.println("Done.");
    }

    static void printEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            System.out.println("[Tool Result] " + truncate(e.getDelta(), 100));
        } else if (event instanceof HintBlockEvent e) {
            System.out.println("[HintBlock] " + truncate(e.getHint(), 100));
        }
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * Tools with a deliberately slow operation to demonstrate async offloading.
     */
    public static class SlowTools {

        @Tool(
                name = "slow_analysis",
                description =
                        "Perform a thorough data analysis. This operation takes several"
                                + " seconds to complete.")
        public String analyze(
                @ToolParam(name = "topic", description = "The topic to analyze") String topic) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Analysis interrupted.";
            }
            return "Analysis of '"
                    + topic
                    + "' complete.\n\n"
                    + "Key findings:\n"
                    + "- Throughput increased 23% over the last quarter\n"
                    + "- P99 latency reduced from 450ms to 280ms\n"
                    + "- Error rate dropped to 0.02%\n"
                    + "- Top bottleneck: database connection pooling";
        }
    }
}
