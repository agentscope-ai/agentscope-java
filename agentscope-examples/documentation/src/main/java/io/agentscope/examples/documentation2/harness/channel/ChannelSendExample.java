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
 * 【学习示例】Channel 发送消息 - ChannelSendExample
 * ============================================================================
 *
 * 演示 ChatUiChannel 的基础用法：发送消息与会话管理。
 *
 * 1. Channel 概念
 *    Channel 是代理与外部系统（Web UI、移动端等）的通信桥梁：
 *
 *    HarnessAgent agent = HarnessAgent.builder()...build();
 *    ChatUiChannel chat = agent.channel(ChatUiChannel.create());
 *
 * 2. SendOptions 用户身份与会话
 *    ┌────────────────────────────────────────────────────────┐
 *    │  SendOptions.userId("user-1")                         │
 *    │    - 按 userId 自动创建/复用会话                       │
 *    │    - 同一 userId 的后续消息在同一对话中                │
 *    ├────────────────────────────────────────────────────────┤
 *    │  SendOptions.of("user-1", "session-a")                │
 *    │    - 显式指定 sessionId                               │
 *    │    - 同一用户可有多个独立会话                          │
 *    ├────────────────────────────────────────────────────────┤
 *    │  SendOptions.userId("user-1").withAttribute(k, v)     │
 *    │    - 附加应用上下文（租户、偏好等）                    │
 *    └────────────────────────────────────────────────────────┘
 *
 * 3. 发送消息
 *    // 简单文本
 *    Msg reply = chat.send(SendOptions.userId("user-1"), "Hello").block();
 *
 *    // 多模态消息（图片+文本）
 *    Msg multimodal = Msg.builder()
 *        .role(MsgRole.USER)
 *        .content(
 *            TextBlock.builder().text("What is in this image?").build(),
 *            ImageBlock.builder()
 *                .source(URLSource.builder().url("https://...").build())
 *                .build()
 *        )
 *        .build();
 *    chat.send(SendOptions.userId("user-1"), multimodal).block();
 *
 * 4. 会话隔离
 *    user-1  ──> session-default-user1 (对话A)
 *    user-2  ──> session-default-user2 (对话B)
 *    user-1 + session-a ──> session-a (对话C)
 *    user-1 + session-b ──> session-b (对话D)
 *
 * 5. StateStore 会话持久化
 *    InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
 *    HarnessAgent.builder()
 *        .stateStore(stateStore)  // 启用会话状态存储
 *        .build();
 *
 * 6. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.channel.ChannelSendExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.harness.channel;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import java.util.List;

/**
 * The simplest Channel usage: build a {@link HarnessAgent}, bind a
 * {@link ChatUiChannel}, and send messages with {@link SendOptions}.
 *
 * <p>{@link SendOptions} carries the user identity and optional session id. Each distinct
 * {@code userId} gets its own session by default; specify a {@code sessionId} to manage multiple
 * conversations for the same user.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.GatewayBasicExample
 * </pre>
 */
public class ChannelSendExample {

    /**
     * Runs the basic channel example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Basic Example");
        System.out.println("=".repeat(60) + "\n");

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .sysPrompt("You are a helpful assistant.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        ChatUiChannel chat = agent.channel(ChatUiChannel.create());

        // Send a message from "user-1". The gateway assigns a stable session for this user.
        Msg reply = chat.send(SendOptions.userId("user-1"), "What can you do?").block();
        System.out.println("Reply: " + (reply != null ? reply.getTextContent() : "(null)"));

        // A second message from the same user continues the same conversation.
        Msg followUp = chat.send(SendOptions.userId("user-1"), "Tell me more.").block();
        System.out.println(
                "Follow-up: " + (followUp != null ? followUp.getTextContent() : "(null)"));

        // A different user gets a completely separate session.
        Msg otherUser = chat.send(SendOptions.userId("user-2"), "Hi there").block();
        System.out.println(
                "Other user: " + (otherUser != null ? otherUser.getTextContent() : "(null)"));

        // Same user, different session — multiple conversations per user.
        Msg sessionA = chat.send(SendOptions.of("user-1", "session-a"), "Topic A").block();
        System.out.println(
                "Session A: " + (sessionA != null ? sessionA.getTextContent() : "(null)"));

        // Attach application context (attributes / force-sync) via SendOptions.
        Msg withCtx =
                chat.send(
                                SendOptions.userId("user-1").withAttribute("tenant", "demo"),
                                "Reply with a short hello.")
                        .block();
        System.out.println(
                "With context: " + (withCtx != null ? withCtx.getTextContent() : "(null)"));

        Msg sessionB = chat.send(SendOptions.of("user-1", "session-b"), "Topic B").block();
        System.out.println(
                "Session B: " + (sessionB != null ? sessionB.getTextContent() : "(null)"));

        // Multimodal / structured Msg overload (image URL is illustrative).
        Msg multimodal =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder().text("What is in this image?").build(),
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/photo.png")
                                                        .build())
                                        .build())
                        .build();
        Msg vision = chat.send(SendOptions.userId("user-1"), multimodal).block();
        System.out.println("Multimodal: " + (vision != null ? vision.getTextContent() : "(null)"));
        // Equivalent list form:
        chat.send(SendOptions.userId("user-1"), List.of(multimodal)).block();

        Thread.sleep(10000); // Wait for async processing to complete before exiting
    }
}
