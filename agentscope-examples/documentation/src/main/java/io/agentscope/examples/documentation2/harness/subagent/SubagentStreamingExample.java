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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

/**
 * Demonstrates live subagent event forwarding via
 * {@code streamEvents()}.
 *
 * <p>When a parent agent spawns a synchronous subagent via {@code agent_spawn}, the child's
 * intermediate events (model calls, text deltas, tool invocations, etc.) are forwarded into the
 * parent's {@code streamEvents()} stream in real time. Each child event carries a
 * {@link AgentEvent#getSource() source} path (e.g. {@code "main/researcher"}) so callers can
 * distinguish parent events ({@code source == null}) from child events.
 *
 * <p>Event flow:
 * <pre>
 *   AGENT_START                                      ← parent
 *     TEXT_BLOCK_DELTA …                             ← parent reasoning
 *     TOOL_CALL_START "agent_spawn"                  ← parent calls agent_spawn
 *     AGENT_START       (source="main/researcher")   ← child starts
 *       TEXT_BLOCK_DELTA  (source="main/researcher") ← child reasoning tokens
 *     AGENT_END         (source="main/researcher")   ← child done
 *     TOOL_RESULT_END                                ← parent gets tool result
 *     TEXT_BLOCK_DELTA …                             ← parent final response
 *   AGENT_END                                        ← parent done
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.SubagentStreamingExample
 * </pre>
 */
public class SubagentStreamingExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Subagent Streaming — streamEvents() event forwarding");
        System.out.println("=".repeat(60) + "\n");

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        // ── Build a parent agent with a programmatic subagent declaration ──
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("orchestrator")
                        .sysPrompt(
                                "You are an orchestrator. When the user asks a question, "
                                        + "spawn the researcher subagent to investigate, then "
                                        + "summarize the findings.")
                        .model("dashscope:qwen-plus")
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("researcher")
                                        .description(
                                                "Research specialist. Use when the user needs"
                                                        + " in-depth investigation on a topic.")
                                        .inlineAgentsBody(
                                                "You are a research assistant. Investigate the"
                                                    + " given topic and provide a concise summary"
                                                    + " with key findings.")
                                        .persistSession(true)
                                        .build())
                        .stateStore(stateStore)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("demo-subagent-stream").build();

        // ── Stream events and distinguish parent vs child ──
        System.out.println("User: What are the latest trends in LLM agents?\n");

        agent.streamEvents(new UserMessage("What are the latest trends in LLM agents?"), ctx)
                .doOnNext(SubagentStreamingExample::handleEvent)
                .blockLast();

        System.out.println("\n" + "=".repeat(60));

        Thread.sleep(
                10000); // Wait a moment to ensure all events are printed before the program exits.
    }

    private static String lastTextSource = null;

    private static void handleEvent(AgentEvent event) {
        String source = event.getSource();
        String prefix = (source != null) ? "[" + source + "] " : "";

        if (event instanceof AgentStartEvent e) {
            lastTextSource = null;
            if (source != null) {
                System.out.printf("%n── child agent started: %s ──%n", source);
            } else {
                System.out.printf("[AGENT_START] agent=%s%n", e.getName());
            }

        } else if (event instanceof TextBlockDeltaEvent e) {
            String currentSource = source != null ? source : "__parent__";
            if (!currentSource.equals(lastTextSource)) {
                if (lastTextSource != null) {
                    System.out.println();
                }
                System.out.print(prefix);
                lastTextSource = currentSource;
            }
            System.out.print(e.getDelta());

        } else if (event instanceof ToolCallStartEvent e) {
            lastTextSource = null;
            System.out.printf("%n%s[TOOL_CALL] %s%n", prefix, e.getToolCallName());

        } else if (event instanceof ToolResultEndEvent e) {
            lastTextSource = null;
            System.out.printf("%s[TOOL_RESULT_END] state=%s%n", prefix, e.getState());

        } else if (event instanceof AgentEndEvent) {
            lastTextSource = null;
            if (source != null) {
                System.out.printf("%n── child agent finished: %s ──%n", source);
            } else {
                System.out.println("\n[AGENT_END]");
            }
        }
    }
}
