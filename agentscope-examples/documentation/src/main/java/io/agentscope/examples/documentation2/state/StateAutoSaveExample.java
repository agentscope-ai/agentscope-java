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
package io.agentscope.examples.documentation2.state;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SessionAutoSaveExample - Demonstrates the automatic save/restore lifecycle of
 * {@link ReActAgent} when wired with a {@link AgentStateStore}.
 *
 * <p><b>How auto-save/restore works:</b>
 * <ol>
 *   <li><b>Load:</b> When the agent is constructed with a {@code session} and {@code sessionKey},
 *       it calls {@code stateStore.get(userId, sessionId, "agent_state", AgentState.class)} to restore any
 *       previously persisted conversation history and toolkit state.</li>
 *   <li><b>Save after each call:</b> After every {@code call()} or {@code stream()} the agent
 *       automatically calls {@code stateStore.save(userId, sessionId, "agent_state", agentState)} to persist
 *       the updated state. No manual save is needed.</li>
 *   <li><b>Shutdown save:</b> The {@link io.agentscope.core.shutdown.GracefulShutdownManager}
 *       registers a state saver at construction time, so state is also flushed on JVM shutdown.</li>
 * </ol>
 *
 * <p><b>{@link JsonFileAgentStateStore}:</b> A file-backed session store that writes
 * {@code <sessionId>_agent_state.json} into the configured directory. Suitable for local
 * development and testing. Replace with a Redis or database-backed implementation for
 * production.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.session.SessionAutoSaveExample
 * </pre>
 */
public class StateAutoSaveExample {

    private static final String SESSION_ID = "auto-save-demo";
    private static final String SESSION_DIR = "/tmp/agentscope-sessions";

    /**
     * Runs the session auto-save demonstration.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if session directory setup fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AgentStateStore Auto-Save Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates automatic history persistence via AgentStateStore.\n"
                        + "AgentStateStore data is stored in: "
                        + SESSION_DIR);
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Path sessionDir = Paths.get(SESSION_DIR);
        Files.createDirectories(sessionDir);

        // ── Phase 1: First agent instance — starts fresh, sends two messages ──────────
        System.out.println("═══ Phase 1: First agent instance ═══");

        AgentStateStore session1 = new JsonFileAgentStateStore(sessionDir);
        ReActAgent agent1 = buildAgent("alice", apiKey, session1);

        Msg r1 = agent1.call(new UserMessage("user", "My favourite colour is blue.")).block();
        System.out.println("Agent: " + (r1 != null ? r1.getTextContent() : "(null)"));
        System.out.println(
                "History size after call 1: "
                        + agent1.getAgentState().getContext().size()
                        + " messages");

        Msg r2 = agent1.call(new UserMessage("user", "My lucky number is 7.")).block();
        System.out.println("Agent: " + (r2 != null ? r2.getTextContent() : "(null)"));
        System.out.println(
                "History size after call 2: "
                        + agent1.getAgentState().getContext().size()
                        + " messages");

        // Closing the first agent persists any in-flight state (not strictly required
        // because auto-save already ran after each call — shown here for completeness).
        agent1.close();
        System.out.println("Agent 1 closed. State saved to: " + sessionDir);

        // ── Phase 2: Second agent instance — loads state from session ─────────────────
        System.out.println("\n═══ Phase 2: Second agent instance (same session) ═══");

        AgentStateStore session2 = new JsonFileAgentStateStore(sessionDir);
        ReActAgent agent2 = buildAgent("alice", apiKey, session2);

        System.out.println(
                "History size after reload: "
                        + agent2.getAgentState().getContext().size()
                        + " messages");

        // The agent remembers the conversation from Phase 1:
        Msg r3 =
                agent2.call(
                                new UserMessage(
                                        "user", "What is my favourite colour and lucky number?"))
                        .block();
        System.out.println("Agent: " + (r3 != null ? r3.getTextContent() : "(null)"));
        System.out.println("Expected: agent recalls blue and 7 from the previous session.");

        agent2.close();
    }

    /**
     * Builds a {@link ReActAgent} wired to a {@link JsonFileAgentStateStore} with the given user ID.
     *
     * <p>The {@code sessionKey} scopes history to a specific user so multiple users can
     * share the same session store without their histories colliding.
     *
     * @param userId  user identifier (used as the session key)
     * @param apiKey  DashScope API key
     * @param stateStore backing session store
     * @return configured agent
     */
    private static ReActAgent buildAgent(String userId, String apiKey, AgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("SessionAgent")
                .sysPrompt("You are a helpful assistant. Remember what the user tells you.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                // stateStore() + defaultSessionId() wires automatic load-on-start and
                // save-after-call
                .stateStore(stateStore)
                .defaultSessionId(SESSION_ID + "-" + userId)
                .build();
    }
}
