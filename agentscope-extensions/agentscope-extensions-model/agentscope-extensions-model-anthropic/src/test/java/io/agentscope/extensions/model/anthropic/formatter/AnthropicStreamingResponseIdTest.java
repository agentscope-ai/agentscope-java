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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

class AnthropicStreamingResponseIdTest {

    @Test
    void preservesProviderIdAcrossContentUsageAndFinalMessage() throws Exception {
        StepVerifier.create(parse(events("msg_provider")).collectList())
                .assertNext(responses -> assertResponseIds(responses, "msg_provider"))
                .verifyComplete();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void usesOneGeneratedIdWhenProviderIdIsAbsent(String providerId) throws Exception {
        StepVerifier.create(parse(events(providerId)).collectList())
                .assertNext(
                        responses -> {
                            String generatedId = responses.get(0).getId();
                            assertFalse(generatedId.isBlank());
                            assertResponseIds(responses, generatedId);
                        })
                .verifyComplete();
    }

    @Test
    void generatesANewFallbackIdForEachSubscription() throws Exception {
        Flux<ChatResponse> responses = parse(events(null));

        StepVerifier.create(
                        Flux.concat(responses.collectList(), responses.collectList()).collectList())
                .assertNext(
                        subscriptions -> {
                            List<ChatResponse> first = subscriptions.get(0);
                            List<ChatResponse> second = subscriptions.get(1);
                            assertResponseIds(first, first.get(0).getId());
                            assertResponseIds(second, second.get(0).getId());
                            assertNotEquals(first.get(0).getId(), second.get(0).getId());
                        })
                .verifyComplete();
    }

    @Test
    void switchesToTheNextProviderIdAtEachMessageStart() throws Exception {
        StepVerifier.create(parse(concat(events("msg_first"), events("msg_second"))).collectList())
                .assertNext(
                        responses -> {
                            assertResponseIds(responses.subList(0, 4), "msg_first");
                            assertResponseIds(responses.subList(4, 8), "msg_second");
                        })
                .verifyComplete();
    }

    @Test
    void generatesAFreshFallbackIdAtEachMessageStartWithoutProviderId() throws Exception {
        StepVerifier.create(parse(concat(events(null), events(null))).collectList())
                .assertNext(
                        responses -> {
                            String firstId = responses.get(0).getId();
                            String secondId = responses.get(4).getId();
                            assertResponseIds(responses.subList(0, 4), firstId);
                            assertResponseIds(responses.subList(4, 8), secondId);
                            assertNotEquals(firstId, secondId);
                        })
                .verifyComplete();
    }

    @Test
    void isolatesIdsBetweenOverlappingSubscriptions() throws Exception {
        TestPublisher<RawMessageStreamEvent> firstSource = TestPublisher.create();
        TestPublisher<RawMessageStreamEvent> secondSource = TestPublisher.create();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<RawMessageStreamEvent> events =
                Flux.defer(
                        () ->
                                subscriptions.getAndIncrement() == 0
                                        ? firstSource.flux()
                                        : secondSource.flux());
        Flux<ChatResponse> responses =
                AnthropicResponseParser.parseStreamEvents(events, Instant.now());
        List<RawMessageStreamEvent> firstEvents = events("msg_first");
        List<RawMessageStreamEvent> secondEvents = events("msg_second");

        StepVerifier.create(Mono.zip(responses.collectList(), responses.collectList()))
                .then(
                        () -> {
                            firstSource.next(firstEvents.get(0));
                            secondSource.next(secondEvents.get(0));
                            firstSource.emit(
                                    firstEvents
                                            .subList(1, firstEvents.size())
                                            .toArray(RawMessageStreamEvent[]::new));
                            secondSource.emit(
                                    secondEvents
                                            .subList(1, secondEvents.size())
                                            .toArray(RawMessageStreamEvent[]::new));
                        })
                .assertNext(
                        pair -> {
                            assertResponseIds(pair.getT1(), "msg_first");
                            assertResponseIds(pair.getT2(), "msg_second");
                        })
                .verifyComplete();
    }

    private static Flux<ChatResponse> parse(List<RawMessageStreamEvent> events) {
        return AnthropicResponseParser.parseStreamEvents(Flux.fromIterable(events), Instant.now());
    }

    private static List<RawMessageStreamEvent> concat(
            List<RawMessageStreamEvent> first, List<RawMessageStreamEvent> second) {
        List<RawMessageStreamEvent> events = new ArrayList<>(first);
        events.addAll(second);
        return events;
    }

    private static void assertResponseIds(List<ChatResponse> responses, String expectedId) {
        // The message_start is filtered; text, tool start, tool input and final usage remain.
        assertEquals(4, responses.size());
        ReasoningContext context = new ReasoningContext("test-agent");
        for (ChatResponse response : responses) {
            assertEquals(expectedId, response.getId());
            context.processChunk(response);
        }
        assertEquals(expectedId, context.buildFinalMessage().getId());
    }

    private static List<RawMessageStreamEvent> events(String providerId) throws Exception {
        String idProperty =
                providerId == null
                        ? ""
                        : "\"id\":"
                                + ObjectMappers.jsonMapper().writeValueAsString(providerId)
                                + ",";
        return List.of(
                event(
                        "{\"type\":\"message_start\",\"message\":{"
                                + idProperty
                                + "\"type\":\"message\",\"role\":\"assistant\","
                                + "\"model\":\"claude-sonnet-4-5\",\"content\":[],"
                                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}"),
                event(
                        """
                        {"type":"content_block_delta","index":0,
                         "delta":{"type":"text_delta","text":"Checking the weather."}}
                        """),
                event(
                        """
                        {"type":"content_block_start","index":1,
                         "content_block":{"type":"tool_use","id":"tool_1",
                         "name":"weather","input":{}}}
                        """),
                event(
                        """
                        {"type":"content_block_delta","index":1,
                         "delta":{"type":"input_json_delta","partial_json":"{\\"city\\":\\"Paris\\"}"}}
                        """),
                event(
                        """
                        {"type":"message_delta",
                         "delta":{"stop_reason":"tool_use","stop_sequence":null},
                         "usage":{"output_tokens":12}}
                        """));
    }

    private static RawMessageStreamEvent event(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, RawMessageStreamEvent.class);
    }
}
