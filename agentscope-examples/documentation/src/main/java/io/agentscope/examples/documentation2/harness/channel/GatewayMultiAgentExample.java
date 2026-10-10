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
 * 【学习示例】多代理网关 - GatewayMultiAgentExample
 * ============================================================================
 *
 * 演示使用 GatewayBootstrap 管理多个代理并按名称路由消息。
 *
 * 1. GatewayBootstrap 多代理管理
 *    GatewayBootstrap gw = GatewayBootstrap.builder()
 *        .agent("sales", salesAgent)       // 注册销售代理
 *        .agent("support", supportAgent)   // 注册客服代理
 *        .mainAgent("sales")               // 设置默认代理
 *        .build();
 *
 * 2. 代理路由
 *    ChatUiChannel chat = gw.chatUiChannel();
 *
 *    // 路由到默认代理（sales）
 *    chat.send(SendOptions.userId("user-1"), "What products?");
 *
 *    // 显式路由到 support 代理
 *    chat.send(
 *        SendOptions.userId("user-1").withAgentId("support"),
 *        "I have a billing issue"
 *    );
 *
 * 3. 架构图
 *    ┌─────────────────────────────────────────────────────────┐
 *    │  GatewayBootstrap                                       │
 *    │     ├── "sales"   → HarnessAgent (默认)                │
 *    │     └── "support" → HarnessAgent                       │
 *    │                                                         │
 *    │  ChatUiChannel                                          │
 *    │     │                                                   │
 *    │     ├── SendOptions.userId("u1")                       │
 *    │     │      → 路由到 mainAgent (sales)                  │
 *    │     │                                                   │
 *    │     └── SendOptions.userId("u1").withAgentId("support")│
 *    │            → 显式路由到 support                        │
 *    └─────────────────────────────────────────────────────────┘
 *
 * 4. 与单代理 Channel 的区别
 *    单代理:
 *      ChatUiChannel chat = agent.channel(ChatUiChannel.create());
 *
 *    多代理:
 *      GatewayBootstrap gw = GatewayBootstrap.builder()...build();
 *      ChatUiChannel chat = gw.chatUiChannel();
 *
 * 5. 应用场景
 *    - 部门分流（销售/客服/技术）
 *    - 技能路由（代码/文档/分析）
 *    - A/B 测试（不同模型/策略）
 *
 * 6. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.channel.GatewayMultiAgentExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.harness.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;

/**
 * Routes messages to different agents using {@link GatewayBootstrap}
 * and {@link SendOptions#withAgentId(String)}.
 *
 * <p>{@link GatewayBootstrap} manages multiple {@link HarnessAgent} instances. Use
 * {@link SendOptions#withAgentId(String)} to explicitly route a message to a specific agent.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.GatewayMultiAgentExample
 * </pre>
 */
public class GatewayMultiAgentExample {

    /**
     * Runs the multi-agent routing example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Multi-Agent Routing");
        System.out.println("=".repeat(60) + "\n");

        HarnessAgent salesAgent =
                HarnessAgent.builder()
                        .name("sales")
                        .sysPrompt("You are a sales assistant.")
                        .model("dashscope:qwen-plus")
                        .build();

        HarnessAgent supportAgent =
                HarnessAgent.builder()
                        .name("support")
                        .sysPrompt("You are a support agent.")
                        .model("dashscope:qwen-plus")
                        .build();

        // Register both agents. mainAgent("sales") makes it the default.
        GatewayBootstrap gw =
                GatewayBootstrap.builder()
                        .agent("sales", salesAgent)
                        .agent("support", supportAgent)
                        .mainAgent("sales")
                        .build();

        ChatUiChannel chat = gw.chatUiChannel();

        // Route to the default (sales) agent — no agentId override needed.
        Msg salesReply =
                chat.send(SendOptions.userId("user-1"), "What products do you have?").block();
        System.out.println(
                "Sales reply: " + (salesReply != null ? salesReply.getTextContent() : "(null)"));

        // Route to the support agent explicitly via withAgentId().
        Msg supportReply =
                chat.send(
                                SendOptions.userId("user-1").withAgentId("support"),
                                "I have a billing issue")
                        .block();
        System.out.println(
                "Support reply: "
                        + (supportReply != null ? supportReply.getTextContent() : "(null)"));
    }
}
