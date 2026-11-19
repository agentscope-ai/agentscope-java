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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Continuation must reach the child agent's conversation, not only its session id.
 *
 * <p>The cluster tests assert that two submissions resolve to one session; that is a necessary but
 * not a sufficient condition, because a session id is only a label. This test runs a real {@link
 * HarnessAgent} as the child — the same instance-per-submission shape a deployment has, since the
 * {@link AgentFactory} creates a new agent for every task — and inspects what the child's model
 * actually receives. A continuation is only correct when the second run's prompt contains the first
 * run's user turn.
 *
 * <p>The child's state stores are shared between the two agent instances, which is what a
 * distributed deployment provides; nothing else about the child is faked beyond the model itself,
 * which returns a fixed reply instead of calling a provider.
 */
class AgentProtocolContinuationChildContextTest {

    private static final String CHILD_AGENT_ID = "worker";

    @TempDir Path root;

    private ScriptedModel childModel;
    private InMemoryAgentStateStore childStateStore;
    private InMemoryThreadJournalStore childJournalStore;
    private Path childWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        childModel = new ScriptedModel();
        childStateStore = new InMemoryAgentStateStore();
        childJournalStore = new InMemoryThreadJournalStore();
        childWorkspace = Files.createDirectories(root.resolve("child"));
    }

    @Test
    @Timeout(60)
    void continuedSubmissionPutsTheEarlierTurnIntoTheChildConversation() throws Exception {
        AgentProtocolTaskStore store = store();

        store.submit("task_first", CHILD_AGENT_ID, "first question");
        assertEquals("success", store.waitFor("task_first", 20_000).get("status"));

        store.submit(
                "task_second",
                CHILD_AGENT_ID,
                "second question",
                Map.of("session_id", "task_first"));
        assertEquals("success", store.waitFor("task_second", 20_000).get("status"));

        List<List<Msg>> prompts = childModel.prompts();
        assertEquals(2, prompts.size(), "one model round per submission, got " + prompts);

        String firstPrompt = text(prompts.get(0));
        assertTrue(firstPrompt.contains("first question"), firstPrompt);
        assertFalse(
                firstPrompt.contains("second question"),
                "the first run must not see a turn that had not happened yet: " + firstPrompt);

        String secondPrompt = text(prompts.get(1));
        assertTrue(
                secondPrompt.contains("first question"),
                "the continued run must carry the earlier user turn into its prompt: "
                        + secondPrompt);
        assertTrue(secondPrompt.contains("second question"), secondPrompt);
    }

    @Test
    @Timeout(60)
    void submissionWithoutParentTaskIdStartsFromAnEmptyConversation() throws Exception {
        AgentProtocolTaskStore store = store();

        store.submit("task_alpha", CHILD_AGENT_ID, "alpha question");
        assertEquals("success", store.waitFor("task_alpha", 20_000).get("status"));

        store.submit("task_beta", CHILD_AGENT_ID, "beta question");
        assertEquals("success", store.waitFor("task_beta", 20_000).get("status"));

        List<List<Msg>> prompts = childModel.prompts();
        assertEquals(2, prompts.size(), "one model round per submission, got " + prompts);

        String secondPrompt = text(prompts.get(1));
        assertTrue(secondPrompt.contains("beta question"), secondPrompt);
        assertFalse(
                secondPrompt.contains("alpha question"),
                "a task without a shared session_id must not inherit another task's conversation: "
                        + secondPrompt);
    }

    private AgentProtocolTaskStore store() throws Exception {
        AgentProtocolProperties properties = new AgentProtocolProperties();
        return new AgentProtocolTaskStore(
                request -> newChildAgent(),
                new WorkspaceProtocolTaskRepository(
                        Files.createDirectories(root.resolve("protocol"))),
                new AgentProtocolTaskEventBus(),
                properties,
                List.of(),
                BaseStoreProtocolTaskRunRegistry.inMemory(),
                BaseStoreProtocolTaskContextStore.inMemory());
    }

    /**
     * A fresh instance per submission, as a deployment has: the {@link AgentFactory} is called once
     * per task, so continuity can only come from the shared stores.
     */
    private HarnessAgent newChildAgent() {
        return HarnessAgent.builder()
                .name(CHILD_AGENT_ID)
                .agentId(CHILD_AGENT_ID)
                .workspace(childWorkspace)
                .model(childModel)
                .stateStore(childStateStore)
                .threadJournalStore(childJournalStore)
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .build();
    }

    private static String text(List<Msg> messages) {
        return messages.stream()
                .map(
                        msg ->
                                msg.getContentBlocks(TextBlock.class).stream()
                                        .map(TextBlock::getText)
                                        .collect(Collectors.joining("\n")))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n---\n"));
    }

    /** Records every prompt the child sends to its model and answers with a fixed reply. */
    private static final class ScriptedModel implements Model {

        private final List<List<Msg>> prompts = new CopyOnWriteArrayList<>();

        List<List<Msg>> prompts() {
            return List.copyOf(prompts);
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            assertNotNull(messages, "the agent must hand its prompt to the model");
            prompts.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .usage(new ChatUsage(1, 1, 0))
                            .build());
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }
    }
}
