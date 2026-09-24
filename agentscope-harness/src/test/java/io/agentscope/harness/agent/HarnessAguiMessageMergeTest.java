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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Regression test for the AG-UI message merge on the harness path: {@code HarnessAgent}
 * derives a new RuntimeContext in {@code ensureSessionDefaults} whenever workspace or sandbox
 * defaults are injected, and that derivation must explicitly carry the entry-call {@code
 * onAgentStateBound} hook (it is not copied by {@code Builder.from}) — otherwise the AG-UI
 * dedup silently stops working for harness agents.
 */
class HarnessAguiMessageMergeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir Path workspace;

    private Model mockModel;
    private List<List<Msg>> modelInputs;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        modelInputs = new ArrayList<>();
    }

    @Test
    void fullTranscriptMerge_survivesSessionDefaultsDerivation() throws Exception {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("done").build()))
                        .build();
        when(mockModel.getModelName()).thenReturn("stub");
        when(mockModel.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            modelInputs.add(new ArrayList<>(invocation.getArgument(0)));
                            return Flux.just(response);
                        });
        // workspace(...) forces ensureSessionDefaults onto the derivation path.
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("harness-merge")
                        .model(mockModel)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .build()) {
            AguiAgentAdapter adapter =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
            String threadId = "t-harness-merge";

            adapter.run(input(threadId, "r-1", AguiMessage.userMessage("u1", "hello")))
                    .collectList()
                    .block(TIMEOUT);
            List<List<Msg>> reasoningAfterFirst = reasoningCalls();
            assertEquals(1, reasoningAfterFirst.size());
            assertEquals(List.of("hello"), conversationTexts(reasoningAfterFirst.get(0)));

            List<AguiEvent> events =
                    adapter.run(
                                    input(
                                            threadId,
                                            "r-2",
                                            AguiMessage.userMessage("u1", "hello"),
                                            AguiMessage.assistantMessage("a1", "done"),
                                            AguiMessage.userMessage("u2", "again")))
                            .collectList()
                            .block(TIMEOUT);

            assertTrue(noRunError(events), "full transcript replay must not fail the run");
            List<List<Msg>> reasoningAfterSecond = reasoningCalls();
            assertEquals(2, reasoningAfterSecond.size());
            // The derived delegate context still carries the merge hook: the second reasoning
            // invocation sees the history exactly once, not the transcript re-appended.
            assertEquals(
                    List.of("hello", "done", "again"),
                    conversationTexts(reasoningAfterSecond.get(1)));
        }
    }

    // ---------- helpers ----------

    private static RunAgentInput input(String threadId, String runId, AguiMessage... messages) {
        return RunAgentInput.builder()
                .threadId(threadId)
                .runId(runId)
                .messages(List.of(messages))
                .build();
    }

    private static boolean noRunError(List<AguiEvent> events) {
        return events != null && events.stream().noneMatch(e -> e instanceof AguiEvent.RunError);
    }

    /**
     * Model calls that are conversation reasoning, i.e. not the harness's post-run
     * memory-extraction calls (those carry the "memory extraction assistant" system prompt).
     */
    private List<List<Msg>> reasoningCalls() {
        return modelInputs.stream()
                .filter(
                        input ->
                                input.stream()
                                        .noneMatch(
                                                m ->
                                                        m.getRole() == MsgRole.SYSTEM
                                                                && m.getTextContent()
                                                                        .contains(
                                                                                "memory extraction"
                                                                                    + " assistant")))
                .toList();
    }

    /** Non-system message texts in order, as the model sees them. */
    private static List<String> conversationTexts(List<Msg> msgs) {
        return msgs.stream()
                .filter(m -> m.getRole() != MsgRole.SYSTEM)
                .map(Msg::getTextContent)
                .toList();
    }
}
