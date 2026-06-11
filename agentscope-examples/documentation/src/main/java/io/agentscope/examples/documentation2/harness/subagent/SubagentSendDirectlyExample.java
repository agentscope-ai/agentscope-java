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
package io.agentscope.examples.documentation2.harness.subagent;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming events via {@code sendStream()} and talking to
 * exposed subagents via {@link SubagentExposedEvent}.
 *
 * <p>{@code sendStream()} returns {@code Flux<}{@link AgentEvent}{@code >} — the same
 * fine-grained event stream as {@code agent.streamEvents()}, but routed through the gateway
 * with session management. This is the recommended path for SSE web chat UIs.
 *
 * <p>When the agent spawns a subagent with {@code expose_to_user=true}, a
 * {@link SubagentExposedEvent} is emitted into the stream carrying the {@code subagentId}. The
 * client can then send messages directly to that subagent via
 * {@code sendToSubagentStream(subagentId, text)}, bypassing the parent agent entirely.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.GatewayStreamingThreadExample
 * </pre>
 */
public class SubagentSendDirectlyExample {

    /**
     * Runs the streaming + subagent example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Streaming + SubagentExposedEvent");
        System.out.println("=".repeat(60) + "\n");

//        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("orchestrator")
                        .sysPrompt(
                                "You are an orchestrator. When asked to research, spawn a "
                                        + "researcher subagent with expose_to_user=true.")
                        .model("dashscope:qwen-plus")
//                        .stateStore(stateStore)
                        .build();

        ChatUiChannel chat = agent.channel(ChatUiChannel.create());

        // Hold the discovered subagentId so we can use it after the stream completes.
        AtomicReference<String> discoveredSubagentId = new AtomicReference<>();

        // ── Stream events from the main agent ────────────────────────────────
        //
        // sendStream() returns Flux<AgentEvent> — perfect for SSE forwarding.
        // SubagentExposedEvent arrives when the agent calls agent_spawn(expose_to_user=true).
        chat.sendStream(SendOptions.userId("user-1"), "Please research recent AI trends")
                .doOnNext(
                        event -> {
                            if (event instanceof AgentStartEvent e) {
                                System.out.printf("[AGENT_START] agent=%s%n", e.getName());

                            } else if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());

                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[TOOL_CALL] %s%n", e.getToolCallName());

                            } else if (event instanceof SubagentExposedEvent e) {
                                discoveredSubagentId.set(e.getSubagentId());
                                System.out.printf(
                                        "%n[SUBAGENT_EXPOSED] subagentId=%s  agentId=%s"
                                                + "  label=%s%n",
                                        e.getSubagentId(), e.getAgentId(), e.getLabel());

                            } else if (event instanceof AgentEndEvent) {
                                System.out.println("\n[AGENT_END]");
                            }
                        })
                .blockLast();

        // ── Talk directly to the exposed subagent ────────────────────────────
        //
        // If the agent exposed a subagent, we can send messages to it directly
        // via its subagentId — bypassing the parent agent entirely.
        String subagentId = discoveredSubagentId.get();
        if (subagentId != null) {
            System.out.println("\nTalking directly to subagent: " + subagentId);

            chat.sendToSubagentStream(subagentId, "Focus on LLM agents specifically. Compare LLM agent frameworks (e.g., LangChain vs. AgentScope vs. LlamaIndex)")
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();

            System.out.println();
        }
    }
}
