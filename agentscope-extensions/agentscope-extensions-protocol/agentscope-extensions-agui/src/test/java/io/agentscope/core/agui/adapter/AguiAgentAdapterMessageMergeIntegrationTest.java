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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Integration tests driving the {@code onAgentStateBound} message merge through the real {@code
 * AguiAgentAdapter.run(...)} path against a {@link ReActAgent} with server-side memory.
 *
 * <p>These tests exist because the unit tests in {@link AguiAgentAdapterMessageMergeTest} invoke
 * the callback directly with a hand-built mutable list, while the production path feeds the
 * callback the immutable {@code List.copyOf} produced by {@code AguiMessageConverter.toMsgList} —
 * which used to throw {@code UnsupportedOperationException} exactly when the anchor matched.
 */
@DisplayName("AguiAgentAdapter message merge through the real run path")
class AguiAgentAdapterMessageMergeIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private Model mockModel;
    private List<List<Msg>> modelInputs;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        modelInputs = new ArrayList<>();
    }

    @Test
    @DisplayName("full transcript replay: deduplicated instead of failing or duplicating history")
    void run_fullTranscript_deduplicatesAndDoesNotThrow() {
        AguiAgentAdapter adapter = adapterWithAgent();
        String threadId = "t-merge-full";

        // First run seeds the server-side memory: context becomes [u1 "hello", reply "done"].
        adapter.run(input(threadId, "r-1", AguiMessage.userMessage("u1", "hello")))
                .collectList()
                .block(TIMEOUT);
        assertEquals(1, modelInputs.size());

        // Second run sends the full transcript (old tail + one new user message). The converter
        // hands the agent an immutable list; before the mutable-copy fix the merge hook threw
        // UnsupportedOperationException here, surfacing as RunError.
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
        assertEquals(2, modelInputs.size());
        // The second model invocation sees the history exactly once — the incoming overlap was
        // stripped instead of being appended on top of the persisted context.
        assertEquals(List.of("hello", "done", "again"), conversationTexts(modelInputs.get(1)));
    }

    @Test
    @DisplayName("regenerate (tail equals persisted tail): re-answers the last question")
    void run_regenerate_reAnswersLastQuestion() {
        AguiAgentAdapter adapter = adapterWithAgent();
        String threadId = "t-merge-regen";

        adapter.run(input(threadId, "r-1", AguiMessage.userMessage("u1", "hello")))
                .collectList()
                .block(TIMEOUT);

        // Regenerate: the client replays the transcript including the trailing assistant turn.
        List<AguiEvent> events =
                adapter.run(
                                input(
                                        threadId,
                                        "r-2",
                                        AguiMessage.userMessage("u1", "hello"),
                                        AguiMessage.assistantMessage("a1", "done")))
                        .collectList()
                        .block(TIMEOUT);

        assertTrue(noRunError(events), "regenerate must not fail the run");
        assertEquals(2, modelInputs.size());
        // The strip emptied the input; the last persisted user turn is restored as the prompt,
        // so the model re-answers "hello" against the persisted history. Without the merge the
        // replayed assistant turn would be appended too: [hello, done, hello, done].
        assertEquals(List.of("hello", "done", "hello"), conversationTexts(modelInputs.get(1)));
    }

    // ---------- helpers ----------

    private AguiAgentAdapter adapterWithAgent() {
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
        ReActAgent agent =
                ReActAgent.builder().name("merge-agent").model(mockModel).maxIters(1).build();
        return new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
    }

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

    /** Non-system message texts in order, as the model sees them. */
    private static List<String> conversationTexts(List<Msg> msgs) {
        return msgs.stream()
                .filter(m -> m.getRole() != MsgRole.SYSTEM)
                .map(Msg::getTextContent)
                .toList();
    }
}
