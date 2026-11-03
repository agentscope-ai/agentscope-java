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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ModelRequestPreparerIntegrationTest {
    @Test
    void synchronousProviderFailureStillHasModelStartAfterBuild() {
        Model provider =
                new Model() {
                    public String getModelName() {
                        return "synchronous-failure";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        throw new IllegalStateException("provider failure");
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("sync-test")
                        .model(provider)
                        .modelRequestPreparer((a, rc, input, id, purpose) -> Mono.just(input))
                        .build();
        List<AgentEvent> events = new ArrayList<>();
        agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()))
                .doOnNext(events::add)
                .onErrorResume(ignored -> Flux.empty())
                .blockLast();
        assertTrue(events.stream().anyMatch(ModelCallStartEvent.class::isInstance));
        assertTrue(
                events.stream()
                        .anyMatch(
                                event ->
                                        event instanceof CustomEvent custom
                                                && custom.getName().equals("context_build")
                                                && custom.getValue()
                                                        .get("status")
                                                        .equals("passed")));
    }

    @Test
    void buildOutcomesIncludeRejectionsAndClearFailedManifest() {
        List<AgentEvent> events = new ArrayList<>();
        RuntimeContext rc = RuntimeContext.empty();
        ModelCallInput input =
                new ModelCallInput(List.of(), List.of(), null, model("m", new ArrayList<>()));
        ModelRequestPreparer preparer =
                (a, ctx, request, id, purpose) -> {
                    ctx.put(
                            ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + id,
                            Map.of("validation", "invalid_tool_pairs"));
                    return Mono.error(
                            new IllegalArgumentException("private body must not enter telemetry"));
                };
        StepVerifier.create(
                        preparer.prepareObserved(
                                null,
                                rc,
                                input,
                                "failed-call",
                                ModelRequestPreparer.Purpose.REASONING,
                                events::add))
                .expectError()
                .verify();
        assertEquals(1, events.size());
        CustomEvent event = (CustomEvent) events.get(0);
        assertEquals("context_build", event.getName());
        assertEquals("failed", event.getValue().get("status"));
        assertEquals("failed-call", event.getValue().get("model_call_id"));
        assertFalse(event.getValue().toString().contains("private body"));
        assertNull(rc.get(ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + "failed-call"));
        events.clear();
        ModelRequestPreparer success = (a, ctx, request, id, purpose) -> Mono.just(request);
        success.prepareObserved(
                        null,
                        rc,
                        input,
                        "success",
                        ModelRequestPreparer.Purpose.SUMMARY,
                        events::add)
                .block();
        assertEquals("passed", ((CustomEvent) events.get(0)).getValue().get("status"));
    }

    @Test
    void builtInFallbackReentersPreparationWithActualModel() {
        List<List<Msg>> calls = new ArrayList<>();
        List<String> preparedModels = new ArrayList<>();
        Model failing =
                new Model() {
                    public String getModelName() {
                        return "primary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("unavailable"));
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("fallback-test")
                        .model(failing)
                        .fallbackModel(model("small-fallback", calls))
                        .modelRequestPreparer(
                                (a, rc, input, callId, purpose) -> {
                                    preparedModels.add(input.model().getModelName());
                                    if ("small-fallback".equals(input.model().getModelName())) {
                                        return Mono.just(
                                                new ModelCallInput(
                                                        List.of(
                                                                Msg.builder()
                                                                        .role(MsgRole.USER)
                                                                        .textContent(
                                                                                "rebuilt for"
                                                                                    + " fallback")
                                                                        .build()),
                                                        input.tools(),
                                                        input.options(),
                                                        input.model()));
                                    }
                                    return Mono.just(input);
                                })
                        .build();
        var events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("hello")
                                                .build()))
                        .collectList()
                        .block();
        assertEquals(List.of("primary", "small-fallback"), preparedModels);
        assertEquals(
                2,
                events.stream()
                        .filter(
                                event ->
                                        event instanceof CustomEvent custom
                                                && custom.getName().equals("context_build"))
                        .count());
        assertEquals("rebuilt for fallback", calls.get(0).get(0).getTextContent());
    }

    private static Model model(String name, List<List<Msg>> calls) {
        return new Model() {
            public String getModelName() {
                return name;
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.add(messages);
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build());
            }
        };
    }

    @Test
    void preparerSeesMiddlewareModelAndIsCorrelatedToCall() {
        List<List<Msg>> originalCalls = new ArrayList<>();
        List<List<Msg>> replacementCalls = new ArrayList<>();
        Model replacement = model("replacement", replacementCalls);
        AtomicInteger prepared = new AtomicInteger();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .model(model("original", originalCalls))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onModelCall(
                                            Agent a,
                                            RuntimeContext rc,
                                            ModelCallInput input,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
                                        return next.apply(
                                                new ModelCallInput(
                                                        input.messages(),
                                                        input.tools(),
                                                        input.options(),
                                                        replacement));
                                    }
                                })
                        .modelRequestPreparer(
                                (a, rc, input, callId, purpose) -> {
                                    assertSame(replacement, input.model());
                                    assertFalse(callId.isBlank());
                                    assertEquals(ModelRequestPreparer.Purpose.REASONING, purpose);
                                    prepared.incrementAndGet();
                                    return Mono.just(
                                            new ModelCallInput(
                                                    List.of(
                                                            Msg.builder()
                                                                    .role(MsgRole.USER)
                                                                    .textContent("prepared")
                                                                    .build()),
                                                    input.tools(),
                                                    input.options(),
                                                    input.model()));
                                })
                        .build();
        var events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("hello")
                                                .build()))
                        .collectList()
                        .block();
        CustomEvent build =
                events.stream()
                        .filter(
                                event ->
                                        event instanceof CustomEvent custom
                                                && custom.getName().equals("context_build"))
                        .map(CustomEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        ModelCallStartEvent start =
                events.stream()
                        .filter(ModelCallStartEvent.class::isInstance)
                        .map(ModelCallStartEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(start.getReplyId(), build.getValue().get("model_call_id"));
        assertTrue(events.indexOf(build) < events.indexOf(start));
        assertEquals(1, prepared.get());
        assertTrue(originalCalls.isEmpty());
        assertEquals("prepared", replacementCalls.get(0).get(0).getTextContent());
    }

    @Test
    void rejectedPreparationNeverCallsProvider() {
        List<List<Msg>> calls = new ArrayList<>();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .model(model("m", calls))
                        .modelRequestPreparer(
                                (a, rc, input, callId, purpose) ->
                                        Mono.error(new IllegalArgumentException("invalid input")))
                        .build();
        List<AgentEvent> events = new ArrayList<>();
        agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()))
                .doOnNext(events::add)
                .onErrorResume(ignored -> Flux.empty())
                .blockLast();
        assertTrue(
                events.stream()
                        .anyMatch(
                                event ->
                                        event instanceof CustomEvent custom
                                                && custom.getName().equals("context_build")
                                                && custom.getValue()
                                                        .get("status")
                                                        .equals("failed")));
        assertFalse(events.stream().anyMatch(ModelCallStartEvent.class::isInstance));
        assertTrue(calls.isEmpty());
    }
}
