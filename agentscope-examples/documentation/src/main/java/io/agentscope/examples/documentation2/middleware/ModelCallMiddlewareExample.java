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
 * 【学习示例】模型调用中间件 - ModelCallMiddlewareExample
 * ============================================================================
 *
 * 演示 onModelCall 钩子：拦截所有发往 LLM 的 API 请求。
 *
 * 1. 中间件 5 个钩子（按调用顺序）
 *    ┌──────────────────────────────────────────────────────────┐
 *    │  1. onAgent           — 代理 call() 入口               │
 *    │  2. onSystemPrompt    — 系统提示构建                   │
 *    │  3. onReasoning       — 推理循环开始                   │
 *    │  4. onModelCall       — 模型 API 调用 (本示例)         │
 *    │  5. onActing          — 工具执行                       │
 *    └──────────────────────────────────────────────────────────┘
 *
 * 2. ModelCallInput 结构
 *    input.messages()  — 发送给模型的完整对话历史
 *    input.tools()     — 当前可见的工具 schema 列表
 *    input.options()   — 生成选项（temperature、max_tokens 等）
 *    input.model()     — Model 实例
 *
 * 3. onModelCall 签名
 *    @Override
 *    public Flux<AgentEvent> onModelCall(
 *        Agent agent,
 *        RuntimeContext ctx,
 *        ModelCallInput input,
 *        Function<ModelCallInput, Flux<AgentEvent>> next) {
 *
 *        // 前置：记录请求元数据
 *        System.out.printf("model=%s | messages=%d | tools=%d%n",
 *            input.model().getClass().getSimpleName(),
 *            input.messages().size(),
 *            input.tools().size());
 *
 *        // 必须调用 next.apply(input) 继续
 *        return next.apply(input)
 *            .doOnNext(event -> ...)      // 后置：计数事件
 *            .doOnComplete(() -> ...);    // 完成：计算延迟
 *    }
 *
 * 4. 典型用途
 *    - 请求日志（模型、消息数、工具数）
 *    - 延迟/吞吐量监控
 *    - 消息注入/变换
 *    - 响应缓存/拦截
 *
 * 5. 注意事项
 *    - next 必须调用，否则请求被丢弃
 *    - 返回不同的 Flux 可完全替换模型响应
 *
 * 6. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.middleware.ModelCallMiddlewareExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * ModelCallMiddlewareExample - Demonstrates intercepting the model API call via
 * {@link MiddlewareBase#onModelCall(Agent, RuntimeContext, ModelCallInput, Function)}.
 *
 * <p>{@code onModelCall} wraps every call to the underlying model (LLM), giving you access to
 * the raw request before it is sent and the raw event stream as it arrives. This is useful for:
 * <ul>
 *   <li>Logging request metadata (model name, message count, tool count)</li>
 *   <li>Measuring latency and token throughput</li>
 *   <li>Injecting or transforming messages before they reach the model</li>
 *   <li>Caching or intercepting responses</li>
 * </ul>
 *
 * <p><b>{@link ModelCallInput} fields:</b>
 * <ul>
 *   <li>{@code messages()} — the full conversation history passed to the model</li>
 *   <li>{@code tools()}    — the tool schemas currently visible to the model</li>
 *   <li>{@code options()}  — generation options (temperature, max tokens, etc.)</li>
 *   <li>{@code model()}    — the {@link io.agentscope.core.model.Model} instance</li>
 * </ul>
 *
 * <p>The {@code next} function must always be called (or the call is dropped). Returning a
 * different Flux substitutes the model response entirely.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.middleware.ModelCallMiddlewareExample
 * </pre>
 */
public class ModelCallMiddlewareExample {

    /**
     * Runs the model call middleware example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Model Call Middleware Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates onModelCall() to log request metadata and measure latency.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        AuditingMiddleware auditMiddleware = new AuditingMiddleware();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("AuditedAgent")
                        .sysPrompt("You are a concise assistant. Reply in one sentence.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .middleware(auditMiddleware)
                        .build();

        System.out.println("Sending a few messages to observe middleware logging ...\n");
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

        System.out.println("\n--- Audit Summary ---");
        System.out.println("Total model calls intercepted: " + auditMiddleware.callCount.get());
        System.out.println("Total events received:         " + auditMiddleware.eventCount.get());
    }

    /**
     * Middleware that logs model call metadata and measures latency for every API call.
     */
    public static class AuditingMiddleware implements MiddlewareBase {

        /** Counts the total number of model API calls intercepted. */
        final AtomicLong callCount = new AtomicLong();

        /** Counts the total number of streaming events received across all calls. */
        final AtomicLong eventCount = new AtomicLong();

        /**
         * Intercepts every model call to log request metadata and measure latency.
         *
         * <p>The {@code next} function must always be called to forward the request.
         * This implementation wraps the response Flux to count events and compute
         * end-to-end latency.
         *
         * @param agent  the agent making the call
         * @param input  the model call request (messages, tools, options, model)
         * @param next   the downstream function that actually calls the model
         * @return event stream from the model (possibly enriched or transformed)
         */
        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {

            long callIndex = callCount.incrementAndGet();
            long startMs = System.currentTimeMillis();

            System.out.printf(
                    "[Audit #%d] model=%s | messages=%d | tools=%d%n",
                    callIndex,
                    input.model().getClass().getSimpleName(),
                    input.messages().size(),
                    input.tools().size());

            // Forward to model and wrap the response stream
            return next.apply(input)
                    .doOnNext(event -> eventCount.incrementAndGet())
                    .doOnComplete(
                            () -> {
                                long elapsed = System.currentTimeMillis() - startMs;
                                System.out.printf(
                                        "[Audit #%d] completed in %d ms (events: %d)%n",
                                        callIndex, elapsed, eventCount.get());
                            })
                    .doOnError(
                            err ->
                                    System.err.printf(
                                            "[Audit #%d] error: %s%n",
                                            callIndex, err.getMessage()));
        }
    }
}
