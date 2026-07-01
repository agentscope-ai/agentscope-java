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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.SuggestionResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link SuggestionMiddleware}: exercise the happy path (single
 * {@link SuggestionResultEvent} between {@link AgentResultEvent} and {@link AgentEndEvent}), the
 * error-swallowing branch, and the empty-message skip branch.
 */
class SuggestionMiddlewareTest {

    private static final String REPLY_ID = "reply-abc";

    @Test
    @DisplayName(
            "Happy path (JSON array): emits SuggestionResultEvent before the preserved AgentEnd")
    void happyPathEmitsResultEvent() {
        Model model = streamingModel("[\"Ask about X\", \"Explore option Y\", \"Consider Z\"]");
        SuggestionMiddleware mw = new SuggestionMiddleware(model, 4);

        List<AgentEvent> events = runMiddleware(mw, assistantMsg("Here is your answer."));

        int result = indexOf(events, AgentResultEvent.class);
        int suggestion = indexOf(events, SuggestionResultEvent.class);
        int agentEnd = indexOf(events, AgentEndEvent.class);
        assertTrue(result >= 0, "AgentResultEvent must be present");
        assertTrue(suggestion > result, "SuggestionResultEvent must follow AgentResult");
        assertEquals(events.size() - 1, agentEnd, "AgentEndEvent must be the last event");

        SuggestionResultEvent res = (SuggestionResultEvent) events.get(suggestion);
        assertEquals(
                List.of("Ask about X", "Explore option Y", "Consider Z"),
                res.getSuggestions(),
                "JSON array parsed verbatim");
        assertEquals(REPLY_ID, res.getReplyId(), "replyId propagated from AgentEndEvent");
    }

    @Test
    @DisplayName("Line-based fallback: markdown-wrapped or plain-text output still parses cleanly")
    void lineFallbackWhenModelIgnoresJsonContract() {
        Model model =
                streamingModel(
                        "1. First on-topic follow-up\n"
                                + "- Second on-topic follow-up\n"
                                + "* Third on-topic follow-up");
        SuggestionMiddleware mw = new SuggestionMiddleware(model, 4);

        List<AgentEvent> events = runMiddleware(mw, assistantMsg("Here is your answer."));

        SuggestionResultEvent res =
                (SuggestionResultEvent) events.get(indexOf(events, SuggestionResultEvent.class));
        assertEquals(
                List.of(
                        "First on-topic follow-up",
                        "Second on-topic follow-up",
                        "Third on-topic follow-up"),
                res.getSuggestions());
    }

    @Test
    @DisplayName("Model failure is swallowed: no SuggestionResult, AgentEndEvent still emitted")
    void modelErrorDegradesGracefully() {
        Model failing =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new RuntimeException("boom"));
                    }

                    @Override
                    public String getModelName() {
                        return "failing-mock";
                    }
                };
        SuggestionMiddleware mw = new SuggestionMiddleware(failing);

        List<AgentEvent> events = runMiddleware(mw, assistantMsg("Non-empty reply."));

        assertFalse(
                events.stream().anyMatch(e -> e instanceof SuggestionResultEvent),
                "no SuggestionResult on error");
        AgentEndEvent last = (AgentEndEvent) events.get(events.size() - 1);
        assertNotNull(last, "AgentEndEvent still terminates the stream");
        assertEquals(REPLY_ID, last.getReplyId());
    }

    @Test
    @DisplayName("Blank final message: suggestion event skipped entirely")
    void blankFinalMessageSkipsSuggestion() {
        Model model = streamingModel("[\"should never be surfaced\"]");
        SuggestionMiddleware mw = new SuggestionMiddleware(model);

        List<AgentEvent> events = runMiddleware(mw, assistantMsg("   "));

        assertFalse(events.stream().anyMatch(e -> e instanceof SuggestionResultEvent));
        assertInstanceOf(AgentEndEvent.class, events.get(events.size() - 1), "AgentEndEvent still last");
    }

    @Test
    @DisplayName("parseSuggestions caps output and strips numbered / bulleted markers")
    void parseSuggestionsRespectsCap() {
        List<String> parsed =
                SuggestionMiddleware.parseSuggestions(
                        "1. First\n" + "* Second\n" + "- Third\n" + "Fourth\n" + "Fifth\n", 3);
        assertEquals(List.of("First", "Second", "Third"), parsed);
    }

    @Test
    @DisplayName("parseSuggestions strips ```json fences before parsing")
    void parseSuggestionsUnfences() {
        List<String> parsed =
                SuggestionMiddleware.parseSuggestions("```json\n[\"a\", \"b\"]\n```", 4);
        assertEquals(List.of("a", "b"), parsed);
    }

    @Test
    @DisplayName("ExceedMaxItersEvent upstream triggers skip (no LLM, AgentEnd still emitted)")
    void exceedMaxItersSkipsLlm() {
        Model failingIfCalled = neverCalledModel();
        SuggestionMiddleware mw = new SuggestionMiddleware(failingIfCalled);

        Flux<AgentEvent> core =
                Flux.just(
                        new ExceedMaxItersEvent(REPLY_ID, 10, 10),
                        new AgentResultEvent(assistantMsg("A long enough final reply.")),
                        (AgentEvent) new AgentEndEvent(REPLY_ID));
        List<AgentEvent> events = runWith(mw, core);

        assertFalse(events.stream().anyMatch(e -> e instanceof SuggestionResultEvent));
        assertInstanceOf(AgentEndEvent.class, events.get(events.size() - 1));
    }

    @Test
    @DisplayName("RequestStopEvent upstream triggers skip (no LLM, AgentEnd still emitted)")
    void requestStopSkipsLlm() {
        Model failingIfCalled = neverCalledModel();
        SuggestionMiddleware mw = new SuggestionMiddleware(failingIfCalled);

        Flux<AgentEvent> core =
                Flux.just(
                        new RequestStopEvent("user"),
                        new AgentResultEvent(assistantMsg("A long enough final reply.")),
                        (AgentEvent) new AgentEndEvent(REPLY_ID));
        List<AgentEvent> events = runWith(mw, core);

        assertFalse(events.stream().anyMatch(e -> e instanceof SuggestionResultEvent));
        assertInstanceOf(AgentEndEvent.class, events.get(events.size() - 1));
    }

    @Test
    @DisplayName("Reply shorter than MIN_ANCHOR_CHARS skips LLM entirely")
    void shortReplySkipsLlm() {
        Model failingIfCalled = neverCalledModel();
        SuggestionMiddleware mw = new SuggestionMiddleware(failingIfCalled);

        List<AgentEvent> events = runMiddleware(mw, assistantMsg("too short"));

        assertFalse(events.stream().anyMatch(e -> e instanceof SuggestionResultEvent));
        assertInstanceOf(AgentEndEvent.class, events.get(events.size() - 1));
    }

    // ---- helpers ----

    private static List<AgentEvent> runMiddleware(SuggestionMiddleware mw, Msg finalMsg) {
        Flux<AgentEvent> core =
                Flux.just(new AgentResultEvent(finalMsg), (AgentEvent) new AgentEndEvent(REPLY_ID));
        return runWith(mw, core);
    }

    private static List<AgentEvent> runWith(SuggestionMiddleware mw, Flux<AgentEvent> core) {
        List<AgentEvent> events =
                mw.onAgent(null, null, new AgentInput(List.of()), input -> core)
                        .collectList()
                        .block();
        assertNotNull(events, "stream must complete");
        return events;
    }

    /** Model stub that throws if invoked — used to prove a skip branch avoided the LLM call. */
    private static Model neverCalledModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                throw new AssertionError(
                        "SuggestionMiddleware must not call the model in a skip branch");
            }

            @Override
            public String getModelName() {
                return "never-called";
            }
        };
    }

    private static Msg assistantMsg(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static int indexOf(List<AgentEvent> events, Class<? extends AgentEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Model stub streaming a single ChatResponse carrying the given text as one TextBlock. */
    private static Model streamingModel(String text) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ChatResponse resp =
                        ChatResponse.builder()
                                .id("msg_" + UUID.randomUUID())
                                .content(List.of(TextBlock.builder().text(text).build()))
                                .usage(new ChatUsage(1, 1, 2))
                                .build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
    }
}
