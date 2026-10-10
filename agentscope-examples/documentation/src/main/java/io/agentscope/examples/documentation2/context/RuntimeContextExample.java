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

/*
 * ============================================================================
 * 【学习示例】运行时上下文 - RuntimeContextExample
 * ============================================================================
 *
 * 演示 RuntimeContext 与 AgentStateStore 的配合使用。
 *
 * 1. RuntimeContext vs AgentState
 *    ┌────────────────────┬─────────────────────┬────────────────────┐
 *    │       维度         │   RuntimeContext    │     AgentState     │
 *    ├────────────────────┼─────────────────────┼────────────────────┤
 *    │ 生命周期           │ 单次 call()         │ 跨多次 call()      │
 *    │ 持久化             │ 否                  │ 是                 │
 *    │ 内容               │ 请求元数据、临时属性 │ 对话历史、权限状态 │
 *    │ 创建方式           │ 每次调用新建        │ 从 StateStore 加载 │
 *    └────────────────────┴─────────────────────┴────────────────────┘
 *
 * 2. RuntimeContext 构建
 *    RuntimeContext ctx = RuntimeContext.builder()
 *        .userId("alice")                   // 用户标识
 *        .sessionId("session-123")          // 会话标识
 *        .put("request_id", "req-001")      // 临时属性
 *        .put(MyService.class, myService)   // 类型化属性（DI）
 *        .build();
 *
 * 3. 在工具中使用
 *    @Tool
 *    public String myTool(RuntimeContext ctx) {
 *        String userId = ctx.getUserId();
 *        String reqId = ctx.get("request_id");
 *        MyService svc = ctx.get(MyService.class);
 *        // ...
 *    }
 *
 * 4. 多租户隔离
 *    // 同一代理，不同用户/会话 → 独立的 AgentState
 *    RuntimeContext ctxAlice = RuntimeContext.builder()
 *        .userId("alice").sessionId("s1").build();
 *    RuntimeContext ctxBob = RuntimeContext.builder()
 *        .userId("bob").sessionId("s2").build();
 *
 *    agent.call(msg, ctxAlice);  // Alice 的对话历史
 *    agent.call(msg, ctxBob);    // Bob 的对话历史（独立）
 *
 * 5. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.context.RuntimeContextExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.context;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Demonstrates how to thread per-call metadata through {@link RuntimeContext} and
 * combine it with persistent {@link AgentStateStore} storage so the same agent instance can
 * pick up history across runs.
 *
 * <p>{@link RuntimeContext} is a transient, per-call metadata bag — useful for
 * passing tenant ids, request ids, or anything else hooks and tools should see
 * during a single {@code call}. {@link AgentStateStore}
 * configured on the builder turn the same agent into one that loads and saves
 * conversation context to disk automatically.
 */
public class RuntimeContextExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RuntimeContext Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows per-call RuntimeContext metadata and JsonFileAgentStateStore persistence.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        AgentStateStore stateStore = new JsonFileAgentStateStore(sessionPath);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful assistant. Remember details from prior turns.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .stateStore(stateStore)
                        .defaultSessionId("runtime-context-demo")
                        .build();

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("runtime-context-demo")
                        .put("request_id", "req-001")
                        .build();

        int loaded = agent.getAgentState(ctx).getContext().size();
        System.out.println("Loaded " + loaded + " message(s) from session.");

        Msg first = agent.call(List.of(new UserMessage("Hi, my name is Alice.")), ctx).block();
        System.out.println("Assistant: " + (first == null ? "" : first.getTextContent()));

        Msg second = agent.call(List.of(new UserMessage("What is my name?")), ctx).block();
        System.out.println("Assistant: " + (second == null ? "" : second.getTextContent()));

        System.out.println(
                "Final context size: "
                        + agent.getAgentState(ctx).getContext().size()
                        + " message(s).");
    }
}
