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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class StructuredOutputRequestPreparationTest {
    private static final String REMINDER = "You MUST call the `generate_response`";

    public static class Output {
        public String value;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @SuppressWarnings("removal")
    void primarySendsPreparedMessagesSchemasAndOptions(boolean nativeOutput, boolean streaming) {
        CapturingModel model = new CapturingModel("primary", nativeOutput, false);
        List<ModelCallInput> inputs = new ArrayList<>();
        List<ModelCallInput> prepared = new ArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("structured")
                        .model(model)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onModelCall(
                                            Agent a,
                                            RuntimeContext rc,
                                            ModelCallInput input,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
                                        // The required per-call schema must be restored before
                                        // preparation.
                                        return next.apply(
                                                new ModelCallInput(
                                                        input.messages(),
                                                        List.of(),
                                                        input.options(),
                                                        input.model()));
                                    }
                                })
                        .modelRequestPreparer(
                                (a, rc, input, id, purpose) -> {
                                    inputs.add(input);
                                    List<Msg> messages = new ArrayList<>(input.messages());
                                    messages.add(message("approved"));
                                    ModelCallInput result =
                                            new ModelCallInput(
                                                    List.copyOf(messages),
                                                    input.tools(),
                                                    GenerateOptions.mergeOptions(
                                                            input.options(),
                                                            GenerateOptions.builder()
                                                                    .maxTokens(64)
                                                                    .build()),
                                                    input.model());
                                    prepared.add(result);
                                    return Mono.just(result);
                                })
                        .build();
        Msg input = message("hello");
        Msg result;
        if (streaming) {
            result =
                    agent.stream(List.of(input), StreamOptions.defaults(), Output.class)
                            .filter(event -> event.getType() == EventType.AGENT_RESULT)
                            .map(Event::getMessage)
                            .blockLast();
        } else {
            result = agent.call(input, Output.class).block();
        }

        assertNotNull(result);
        assertEquals("ok", result.getStructuredData(Output.class).value);
        assertEquals(1, inputs.size());
        assertEquals(nativeOutput ? 0 : 1, reminderCount(inputs.get(0).messages()));
        if (!nativeOutput) {
            Msg reminder =
                    inputs.get(0).messages().stream()
                            .filter(msg -> msg.getTextContent().contains(REMINDER))
                            .findFirst()
                            .orElseThrow();
            assertTrue(reminder.isRequestProjection(agent.getAgentState().getContext()));
            assertFalse(reminder.getId().equals(input.getId()));
        }
        assertEquals(nativeOutput ? 0 : 1, inputs.get(0).tools().size());
        assertEquals(nativeOutput, inputs.get(0).options().getResponseFormat() != null);
        assertPreparedRequest(prepared.get(0), model.calls.get(0));
        assertEquals("hello", input.getTextContent());
        assertEquals(0, reminderCount(agent.getAgentState().getContext()));
        assertFalse(
                agent.getAgentState().getContext().stream()
                        .anyMatch(msg -> "approved".equals(msg.getTextContent())));

        agent.streamEvents(message("normal"))
                .ofType(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .blockLast();
        assertEquals(0, reminderCount(model.calls.get(1).messages()));
        assertEquals(0, model.calls.get(1).tools().size());
    }

    @Test
    void fallbackRepreparesCompleteRequestWithoutDuplicatingReminderOrSchema() {
        CapturingModel primary = new CapturingModel("primary", false, true);
        CapturingModel fallback = new CapturingModel("fallback", false, false);
        List<ModelCallInput> inputs = new ArrayList<>();
        List<String> preparedModels = new ArrayList<>();
        List<ModelCallInput> prepared = new ArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("fallback")
                        .model(primary)
                        .fallbackModel(fallback)
                        .modelRequestPreparer(
                                (a, rc, input, id, purpose) -> {
                                    inputs.add(input);
                                    preparedModels.add(input.model().getModelName());
                                    List<Msg> messages = new ArrayList<>(input.messages());
                                    messages.add(
                                            message("approved by " + input.model().getModelName()));
                                    ModelCallInput result =
                                            new ModelCallInput(
                                                    List.copyOf(messages),
                                                    input.tools(),
                                                    input.options(),
                                                    input.model());
                                    prepared.add(result);
                                    return Mono.just(result);
                                })
                        .build();

        Msg result = agent.call(message("hello"), Output.class).block();

        assertEquals("ok", result.getStructuredData(Output.class).value);
        assertEquals(List.of("primary", "fallback"), preparedModels);
        for (ModelCallInput input : inputs) {
            assertEquals(1, reminderCount(input.messages()));
            assertEquals(
                    1,
                    input.tools().stream()
                            .filter(tool -> "generate_response".equals(tool.getName()))
                            .count());
        }
        assertPreparedRequest(prepared.get(0), primary.calls.get(0));
        assertPreparedRequest(prepared.get(1), fallback.calls.get(0));
        assertEquals(0, reminderCount(agent.getAgentState().getContext()));
    }

    private static void assertPreparedRequest(ModelCallInput prepared, ModelCallInput actual) {
        assertEquals(prepared.messages(), actual.messages());
        assertEquals(prepared.tools(), actual.tools());
        assertSame(prepared.options(), actual.options());
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static long reminderCount(List<Msg> messages) {
        return messages.stream().filter(msg -> msg.getTextContent().contains(REMINDER)).count();
    }

    private static final class CapturingModel implements Model {
        private final String name;
        private final boolean nativeOutput;
        private final boolean fail;
        private final List<ModelCallInput> calls = new ArrayList<>();

        private CapturingModel(String name, boolean nativeOutput, boolean fail) {
            this.name = name;
            this.nativeOutput = nativeOutput;
            this.fail = fail;
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return nativeOutput;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(new ModelCallInput(List.copyOf(messages), List.copyOf(tools), options, this));
            if (fail) return Flux.error(new IllegalStateException("unavailable"));
            if (tools.stream().anyMatch(tool -> "generate_response".equals(tool.getName()))) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("output")
                                                        .name("generate_response")
                                                        .content(
                                                                "{\"response\":{\"value\":\"ok\"}}")
                                                        .input(
                                                                Map.of(
                                                                        "response",
                                                                        Map.of("value", "ok")))
                                                        .build()))
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            nativeOutput
                                                                    ? "{\"value\":\"ok\"}"
                                                                    : "done")
                                                    .build()))
                            .build());
        }
    }
}
