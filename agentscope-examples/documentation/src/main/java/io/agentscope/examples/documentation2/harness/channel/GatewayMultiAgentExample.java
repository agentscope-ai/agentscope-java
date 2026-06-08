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
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;

/**
 * GatewayMultiAgentExample — Routes messages to different agents using {@link GatewayBootstrap}
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
                        .model("qwen-plus")
                        .build();

        HarnessAgent supportAgent =
                HarnessAgent.builder()
                        .name("support")
                        .sysPrompt("You are a support agent.")
                        .model("qwen-plus")
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
