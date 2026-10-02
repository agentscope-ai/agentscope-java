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
package io.agentscope.examples.documentation2.harness.async;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import java.nio.file.Path;

/**
 * Demonstrates async subagent execution with wakeup-driven result delivery (Mode B).
 *
 * <p>The Gateway + WakeupDispatcher require a shared {@link MessageBus} instance, so this
 * example creates one explicitly. For the agent itself, InboxMiddleware and other bus-dependent
 * components are auto-wired — the explicit bus is only needed for the gateway layer.
 *
 * <p>Flow:
 * <pre>
 * HarnessAgent (with messageBus)
 *     → SubagentsMiddleware wires TaskCompletionCallback
 *     → Subagent completes → inbox push + enqueue wakeup
 *     → WakeupDispatcher polls wakeup queue → gateway.runWakeup(sessionId)
 *     → InboxMiddleware drains inbox → LLM sees the result
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.async.AsyncSubagentExample
 * </pre>
 */
public class AsyncSubagentExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Async Subagent Example — Mode B (Wakeup)");
        System.out.println("=".repeat(60));
        System.out.println("Subagent results are automatically delivered via inbox + wakeup.\n");

        // ── Shared MessageBus (needed by both agent and gateway) ─────────────

        Path workspaceDir =
                Path.of(System.getProperty("java.io.tmpdir"), "agentscope-subagent-demo");
        workspaceDir.toFile().mkdirs();
        MessageBus bus =
                new WorkspaceMessageBus(new LocalFilesystem(workspaceDir, true, 10), "/bus");

        // ── Build agent + gateway + wakeup dispatcher ────────────────────────

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("orchestrator")
                        .sysPrompt(
                                "You are an orchestrator agent.\n"
                                        + "When asked to research a topic, use agent_spawn to "
                                        + "launch a background researcher subagent with "
                                        + "timeout_seconds=0.\n"
                                        + "After spawning, reply to the user that the task is "
                                        + "running. The result will arrive automatically.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        HarnessGateway gateway = HarnessGateway.create();
        gateway.bindMainAgent(agent);

        WakeupDispatcher dispatcher = new WakeupDispatcher(bus, gateway);
        dispatcher.start();

        System.out.println("WakeupDispatcher: started (polls workspace every 3s)\n");

        // ── Run ──────────────────────────────────────────────────────────────

        System.out.println("─── Sending: 'Research the latest trends in AI agents' ───\n");

        Msg response =
                agent.streamEvents(new UserMessage("Research the latest trends in AI agents"))
                        .doOnNext(AsyncSubagentExample::printEvent)
                        .filter(e -> e instanceof AgentResultEvent)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .blockLast();

        System.out.println("\n\n─── Agent response ───");
        System.out.println(response != null ? response.getTextContent() : "(no response)");

        System.out.println("\n─── Waiting for subagent to complete and wakeup... ───\n");
        Thread.sleep(15000);

        dispatcher.close();
        System.out.println("\nDone.");
    }

    private static void printEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.println("\n[Tool Call] " + e.getToolCallName());
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            System.out.println("[Tool Result] " + AsyncToolExample.truncate(e.getDelta(), 120));
        } else if (event instanceof HintBlockEvent e) {
            System.out.println(
                    "[HintBlock from "
                            + e.getHintSource()
                            + "] "
                            + AsyncToolExample.truncate(e.getHint(), 120));
        }
    }
}
