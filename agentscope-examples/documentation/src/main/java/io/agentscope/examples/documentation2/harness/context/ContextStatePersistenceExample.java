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
package io.agentscope.examples.documentation2.harness.context;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * ContextStatePersistenceExample — Demonstrates the stateless-engine model: one
 * {@link HarnessAgent} instance serves multiple users concurrently, each with isolated state
 * persisted via {@link JsonFileAgentStateStore}.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Multi-user isolation</b> — different {@code (userId, sessionId)} pairs run on the same
 *       agent instance with completely independent conversation history.</li>
 *   <li><b>State persistence</b> — {@link JsonFileAgentStateStore} saves {@link AgentState} to
 *       disk after each {@code call()}; a second agent instance loads the same state and resumes
 *       the conversation seamlessly.</li>
 *   <li><b>Concurrent calls</b> — two different users' calls run in parallel via
 *       {@code Mono.zip()}.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.context.ContextStatePersistenceExample
 * </pre>
 */
public class ContextStatePersistenceExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Context — State Persistence & Multi-User Isolation");
        System.out.println("=".repeat(60) + "\n");

        Path stateDir = Files.createTempDirectory("agentscope-context-example");

        AgentStateStore stateStore = new JsonFileAgentStateStore(stateDir);

        // ── 1. One agent instance, two users ────────────────────────────────

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .sysPrompt("You are a helpful assistant. Keep answers under two sentences.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-session-1").build();
        RuntimeContext bobCtx =
                RuntimeContext.builder().userId("bob").sessionId("bob-session-1").build();

        System.out.println("── Step 1: Two users talk to the same agent in parallel ──\n");

        Mono<Msg> aliceCall =
                agent.call(new UserMessage("My name is Alice. Remember it."), aliceCtx);
        Mono<Msg> bobCall = agent.call(new UserMessage("My name is Bob. What is 2 + 3?"), bobCtx);

        var results = Mono.zip(aliceCall, bobCall).block();
        System.out.println(
                "Alice reply: "
                        + (results.getT1() != null ? results.getT1().getTextContent() : "(null)"));
        System.out.println(
                "Bob reply:   "
                        + (results.getT2() != null ? results.getT2().getTextContent() : "(null)"));

        // ── 2. Inspect persisted state via the store ────────────────────────

        System.out.println("\n── Step 2: Inspect persisted AgentState ──\n");

        Optional<AgentState> aliceState =
                stateStore.get("alice", "alice-session-1", "agent_state", AgentState.class);
        Optional<AgentState> bobState =
                stateStore.get("bob", "bob-session-1", "agent_state", AgentState.class);

        System.out.println(
                "Alice context size: "
                        + aliceState.map(s -> s.getContext().size()).orElse(0)
                        + " messages");
        System.out.println(
                "Bob context size:   "
                        + bobState.map(s -> s.getContext().size()).orElse(0)
                        + " messages");

        // ── 3. Resume on a fresh agent instance ─────────────────────────────

        System.out.println(
                "\n── Step 3: Build a new agent with the same stateStore, "
                        + "resume Alice's conversation ──\n");

        HarnessAgent agent2 =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .sysPrompt("You are a helpful assistant. Keep answers under two sentences.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        Msg resumed = agent2.call(new UserMessage("What is my name?"), aliceCtx).block();
        System.out.println(
                "Resumed reply: " + (resumed != null ? resumed.getTextContent() : "(null)"));

        Optional<AgentState> resumedState =
                stateStore.get("alice", "alice-session-1", "agent_state", AgentState.class);
        System.out.println(
                "Alice context after resume: "
                        + resumedState.map(s -> s.getContext().size()).orElse(0)
                        + " messages");

        // ── 4. Multiple sessions per user ───────────────────────────────────

        System.out.println("\n── Step 4: Alice starts a second, independent session ──\n");

        RuntimeContext aliceSession2 =
                RuntimeContext.builder().userId("alice").sessionId("alice-session-2").build();

        Msg session2Reply =
                agent2.call(new UserMessage("Do you know my name?"), aliceSession2).block();
        System.out.println(
                "Session-2 reply (no prior context): "
                        + (session2Reply != null ? session2Reply.getTextContent() : "(null)"));

        System.out.println("\nState directory: " + stateDir);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done. State files are at: " + stateDir);
        System.out.println("=".repeat(60));
    }
}
