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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class StructuredOutputValidationMiddlewareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ResponseFormat schemaFormat() {
        return ResponseFormat.jsonSchema(
                JsonSchema.builder()
                        .name("Answer")
                        .schema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("answer", Map.of("type", "number")),
                                        "required",
                                        List.of("answer"),
                                        "additionalProperties",
                                        false))
                        .strict(true)
                        .build());
    }

    private static ModelCallInput input(String responseText) {
        return new ModelCallInput(
                List.of(
                        Msg.builderForRole(io.agentscope.core.message.MsgRole.USER)
                                .content(
                                        io.agentscope.core.message.TextBlock.builder()
                                                .text("q")
                                                .build())
                                .build()),
                List.<ToolSchema>of(),
                GenerateOptions.builder().responseFormat(schemaFormat()).stream(false).build(),
                null);
    }

    /** Emits one delta + one usage event carrying the given text/usage. */
    private static Function<ModelCallInput, Flux<AgentEvent>> stubModel(
            AtomicInteger counter, List<String> responses) {
        return in -> {
            int attempt = counter.getAndIncrement();
            String text = responses.get(Math.min(attempt, responses.size() - 1));
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent("r" + attempt, "b" + attempt, text);
            ChatUsage usage = new ChatUsage(10, 5, 0);
            ModelCallEndEvent end = new ModelCallEndEvent("r" + attempt, usage);
            return Flux.just(delta, end);
        };
    }

    @Test
    void passesThroughWhenNoResponseFormat() {
        MiddlewareBase middleware = new StructuredOutputValidationMiddleware();
        ModelCallInput plain =
                new ModelCallInput(List.of(), List.of(), GenerateOptions.builder().build(), null);
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                plain,
                                in -> {
                                    calls.incrementAndGet();
                                    return Flux.just(new TextBlockDeltaEvent("r", "b", "anything"));
                                }))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, calls.get());
    }

    @Test
    void firstAttemptConformingPassesThroughUntouched() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input("{}"),
                                stubModel(generations, List.of("{\"answer\": 42}"))))
                .assertNext(e -> assertTrue(e instanceof TextBlockDeltaEvent))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, generations.get());
    }

    @Test
    void retriesWithErrorFeedbackUntilConforming() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        List<FailedAttempt> observed = new java.util.ArrayList<>();
        StructuredOutputRetryPolicy policy =
                StructuredOutputRetryPolicy.builder()
                        .maxAttempts(3)
                        .onFailedAttempt(observed::add)
                        .build();
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware(policy);
        // attempts: [invalid] -> [invalid] -> [valid]
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input(null),
                                stubModel(
                                        generations,
                                        List.of(
                                                "{\"wrong\": true}",
                                                "{\"x\": 1}",
                                                "{\"answer\": 7}"))))
                .assertNext(
                        e -> { // failed attempt #1: custom event, original events swallowed
                            assertTrue(e instanceof CustomEvent);
                            assertEquals(
                                    StructuredOutputValidationMiddleware.FAILED_ATTEMPT_EVENT,
                                    ((CustomEvent) e).getName());
                        })
                .assertNext(e -> assertTrue(e instanceof CustomEvent)) // failed attempt #2
                .expectNextCount(2) // conforming attempt: original events released
                .verifyComplete();
        assertEquals(3, generations.get());
        assertEquals(2, observed.size());
        assertEquals(FailedAttempt.Kind.VALIDATION_ERROR, observed.get(0).kind());
    }

    @Test
    void exhaustsRetriesThenFails() throws Exception {
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input(null),
                                stubModel(new AtomicInteger(), List.of("{\"wrong\": true}"))))
                .expectNextCount(3) // one custom event per failed attempt
                .verifyErrorSatisfies(
                        e -> {
                            StructuredOutputValidationException ex =
                                    (StructuredOutputValidationException) e;
                            // every failed attempt is retained chronologically
                            assertEquals(3, ex.getFailedAttempts().size());
                            assertEquals(1, ex.getFailedAttempts().get(0).attemptNumber());
                            assertEquals(3, ex.getFailedAttempts().get(2).attemptNumber());
                        });
    }

    @Test
    void parseErrorsEnterTheSameRetryLoop() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input(null),
                                stubModel(
                                        generations,
                                        List.of("I am thinking... no json", "{\"answer\": 9}"))))
                .assertNext(e -> assertTrue(e instanceof CustomEvent)) // parse failure
                .expectNextCount(2) // conforming retry: original events released
                .verifyComplete();
        assertEquals(2, generations.get());
    }

    @Test
    void emitAttemptEventsDisabledSuppressesCustomEvents() throws Exception {
        StructuredOutputRetryPolicy policy =
                StructuredOutputRetryPolicy.builder().emitAttemptEvents(false).build();
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware(policy);
        AtomicInteger generations = new AtomicInteger();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input(null),
                                stubModel(
                                        generations,
                                        List.of("{\"wrong\": true}", "{\"answer\": 1}"))))
                .expectNextCount(2) // only the conforming attempt's events
                .verifyComplete();
        assertEquals(2, generations.get());
    }

    @Test
    void tokenBudgetStopsGenerationEarly() throws Exception {
        StructuredOutputRetryPolicy policy =
                StructuredOutputRetryPolicy.builder()
                        .maxAttempts(5)
                        .tokenBudget(
                                15L) // each attempt costs 15; budget reached after first failure
                        .build();
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware(policy);
        AtomicInteger generations = new AtomicInteger();
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                RuntimeContext.empty(),
                                input(null),
                                stubModel(generations, List.of("{\"wrong\": true}"))))
                .expectNextCount(1) // failed-attempt custom event
                .verifyError(StructuredOutputValidationException.class);
        assertEquals(1, generations.get()); // stopped before second attempt
    }

    @Test
    void streamingCallsPassThroughUnvalidated() {
        ModelCallInput streamed =
                new ModelCallInput(
                        List.of(),
                        List.of(),
                        GenerateOptions.builder().responseFormat(schemaFormat()).stream(true)
                                .build(),
                        null);
        StructuredOutputValidationMiddleware middleware =
                new StructuredOutputValidationMiddleware();
        AtomicInteger calls = new AtomicInteger();
        Function<ModelCallInput, Flux<AgentEvent>> next =
                in -> {
                    calls.incrementAndGet();
                    return Flux.just(new TextBlockDeltaEvent("r", "b", "not even json"));
                };
        StepVerifier.create(middleware.onModelCall(null, RuntimeContext.empty(), streamed, next))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, calls.get());
    }

    @Test
    void formatAssertionsValidateEmails() throws Exception {
        JsonSchema email =
                JsonSchema.builder()
                        .name("Contact")
                        .schema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "email",
                                                Map.of("type", "string", "format", "email")),
                                        "required",
                                        List.of("email")))
                        .build();
        JsonNode badEmail = MAPPER.readTree("{\"email\": \"not-an-email\"}");
        assertFalse(StructuredOutputValidator.validate(badEmail, email).isEmpty());
    }
}
