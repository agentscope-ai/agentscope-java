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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class SessionRequestRecordingTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static class CaptureModel implements Model {
        final List<List<Msg>> calls = new ArrayList<>();

        public String getModelName() {
            return "offline-model";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private ModelCallInput input() {
        return new ModelCallInput(
                List.of(new UserMessage("request-body")),
                List.of(
                        ToolSchema.builder()
                                .name("lookup")
                                .description("tool-schema-body")
                                .parameters(Map.of("type", "object"))
                                .build()),
                GenerateOptions.builder().temperature(0.5).build(),
                new CaptureModel());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void existingConstructorsKeepFullRequestPayload(int arity) {
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), context());
        var recorder =
                switch (arity) {
                    case 2 -> new SessionRecorder(log, "turn");
                    case 3 -> new SessionRecorder(log, "turn", null);
                    default -> new SessionRecorder(log, "turn", "run", null);
                };
        try {
            recorder.prepareModel(input(), "call", "reasoning", AgentState.builder().build())
                    .block(WAIT);
            var data = requests(log).get(0).data();
            assertEquals(
                    Set.of("modelCallId", "purpose", "model", "messages", "tools", "options"),
                    data.keySet());
            var json = requests(log).get(0).payloadJson();
            assertTrue(json.contains("request-body"));
            assertTrue(json.contains("tool-schema-body"));
            assertTrue(json.contains("0.5"));
        } finally {
            recorder.close();
        }
    }

    @Test
    void metadataRetainsIdentityAndCountsWithoutRequestBodies() {
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), context());
        var recorder =
                new SessionRecorder(log, "turn", "run", null, SessionRequestRecording.METADATA);
        try {
            recorder.prepareModel(input(), "call", "reasoning", AgentState.builder().build())
                    .block(WAIT);
            assertEquals(
                    Map.of(
                            "modelCallId",
                            "call",
                            "purpose",
                            "reasoning",
                            "model",
                            "offline-model",
                            "payloadMode",
                            "metadata",
                            "messageCount",
                            1,
                            "toolCount",
                            1),
                    requests(log).get(0).data());
            assertTrue(
                    log.readAfter(0, 1000).stream()
                            .anyMatch(
                                    e ->
                                            e.type().equals("model/dispatch")
                                                    && e.data().get("modelCallId").equals("call")));
            recorder.prepareModel(
                            new ModelCallInput(List.of(), null, null, new CaptureModel()),
                            "empty",
                            "summary",
                            AgentState.builder().build())
                    .block(WAIT);
            assertEquals(0, requests(log).get(1).data().get("toolCount"));
            assertEquals(0, requests(log).get(1).data().get("messageCount"));
        } finally {
            recorder.close();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionRequestRecording.class)
    void copiedBuilderAndRebuiltAgentPreserveHistoryAcrossRecordingModes(
            SessionRequestRecording firstMode) {
        var store = new InMemorySessionLogStore();
        var builder =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .sessionRequestRecording(firstMode);
        try (var first = builder.copy().build()) {
            first.call(List.of(new UserMessage("keep-this-history")), context()).block(WAIT);
        }
        var model = new CaptureModel();
        var secondMode =
                firstMode == SessionRequestRecording.FULL
                        ? SessionRequestRecording.METADATA
                        : SessionRequestRecording.FULL;
        try (var second = builder.copy().model(model).sessionRequestRecording(secondMode).build()) {
            second.streamEvents(List.of(new UserMessage("continue")), context())
                    .collectList()
                    .block(WAIT);
        }
        assertTrue(
                model.calls.get(0).stream()
                        .anyMatch(m -> "keep-this-history".equals(m.getTextContent())));
        var log = store.open(new SessionKey("u", "stable", "s"), context());
        var requests = requests(log);
        assertEquals(2, requests.size());
        assertEquals(
                firstMode == SessionRequestRecording.FULL,
                requests.get(0).data().containsKey("messages"));
        assertEquals(
                secondMode == SessionRequestRecording.FULL,
                requests.get(1).data().containsKey("messages"));
        var projection = SessionProjection.read(log);
        assertTrue(projection.activeRuns().isEmpty());
        assertTrue(
                projection.restore().getContext().stream()
                        .anyMatch(m -> "keep-this-history".equals(m.getTextContent())));
        assertTrue(
                SessionViews.transcript(log).messages().stream()
                        .anyMatch(m -> "keep-this-history".equals(m.getTextContent())));
        long seq = 0;
        for (var event : log.scan(0, log.head().seq())) assertEquals(++seq, event.seq());
        assertEquals(
                2,
                log.readAfter(0, 1000).stream()
                        .filter(e -> e.type().equals("model/dispatch"))
                        .count());
        assertTrue(
                log.readAfter(0, 1000).stream().anyMatch(e -> e.type().equals("state/checkpoint")));
    }

    @Test
    void unfinishedMetadataExecutionResumesFromItsCheckpoint() {
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), context());
        var state = AgentState.builder().context(input().messages()).build();
        var first =
                new SessionRecorder(log, "turn", "first", null, SessionRequestRecording.METADATA);
        try {
            first.start(input().messages(), state).block(WAIT);
            first.prepareModel(input(), "call", "reasoning", state).block(WAIT);
        } finally {
            // No run/end: simulate releasing the process after the acknowledged checkpoint.
            first.close();
        }
        var checkpoint = SessionProjection.read(log);
        assertEquals(Set.of("first"), checkpoint.activeRuns());
        assertEquals("request-body", checkpoint.restore().getContext().get(0).getTextContent());
        var resumed =
                new SessionRecorder(log, "turn", "second", null, SessionRequestRecording.METADATA);
        try {
            resumed.start(List.of(), checkpoint.restore()).block(WAIT);
            resumed.finish(checkpoint.restore(), "completed").block(WAIT);
        } finally {
            resumed.close();
        }
        assertTrue(
                log.readAfter(0, 1000).stream().anyMatch(e -> e.type().equals("recovery/applied")));
        assertTrue(log.readAfter(0, 1000).stream().anyMatch(e -> e.type().equals("turn/resumed")));
        assertTrue(SessionProjection.read(log).activeRuns().isEmpty());
    }

    @Test
    void metadataAvoidsRepeatedGrowingRequestPayloads() {
        long full = requestBytes(SessionRequestRecording.FULL);
        long metadata = requestBytes(SessionRequestRecording.METADATA);
        System.out.printf(
                "Synthetic request/prepared bytes (12 calls, +4096 characters/call): FULL=%d"
                        + " METADATA=%d%n",
                full, metadata);
        assertTrue(
                metadata * 20 < full,
                "Only request/prepared bytes are compared, not total session storage");
    }

    private long requestBytes(SessionRequestRecording mode) {
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), context());
        var recorder = new SessionRecorder(log, "turn", "run", null, mode);
        var messages = new ArrayList<Msg>();
        try {
            for (int i = 0; i < 12; i++) {
                messages.add(new UserMessage("x".repeat(4096)));
                recorder.prepareModel(
                                new ModelCallInput(
                                        messages,
                                        input().tools(),
                                        input().options(),
                                        new CaptureModel()),
                                "call-" + i,
                                "reasoning",
                                AgentState.builder().build())
                        .block(WAIT);
            }
            return requests(log).stream()
                    .mapToLong(e -> e.payloadJson().getBytes(StandardCharsets.UTF_8).length)
                    .sum();
        } finally {
            recorder.close();
        }
    }

    private List<SessionEvent> requests(SessionLog log) {
        return log.readAfter(0, 1000).stream()
                .filter(e -> e.type().equals("request/prepared"))
                .toList();
    }

    @Test
    void nullRecordingPolicyIsRejected() {
        assertThrows(
                NullPointerException.class,
                () -> ReActAgent.builder().sessionRequestRecording(null));
    }
}
