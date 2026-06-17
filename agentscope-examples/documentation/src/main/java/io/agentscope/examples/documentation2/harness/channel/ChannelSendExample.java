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
package io.agentscope.examples.documentation2.harness.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;

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
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Basic Example");
        System.out.println("=".repeat(60) + "\n");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .sysPrompt("You are a helpful assistant.")
                        .model("dashscope:qwen-plus")
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

        Msg sessionB = chat.send(SendOptions.of("user-1", "session-b"), "Topic B").block();
        System.out.println(
                "Session B: " + (sessionB != null ? sessionB.getTextContent() : "(null)"));
    }
}
