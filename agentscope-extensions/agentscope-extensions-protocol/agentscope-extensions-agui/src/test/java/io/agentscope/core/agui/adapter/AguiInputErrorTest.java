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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.DocumentInputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.InputContentSource;
import io.agentscope.core.agui.model.InputContentUrlSource;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AguiInputErrorTest {

    @ParameterizedTest
    @MethodSource("documentInputs")
    void rejectsDocumentsWithSafeErrorEvents(
            Class<? extends Agent> agentType, InputContentSource source, boolean legacyFinish) {
        Agent agent = mock(agentType);
        RunAgentInput input =
                input(
                        AguiMessage.userMessage(
                                "msg-input",
                                List.of(
                                        new DocumentInputContent(
                                                source, Map.of("private", "private-metadata")))));

        List<AguiEvent> events =
                new AguiAgentAdapter(
                                agent,
                                AguiAdapterConfig.builder()
                                        .emitRunFinishedAfterError(legacyFinish)
                                        .build())
                        .run(input)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertInputError(
                events,
                "Unsupported AG-UI input content type 'document': document input is not supported"
                        + " yet",
                legacyFinish);
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        String sourceValue =
                source instanceof InputContentDataSource data
                        ? data.value()
                        : ((InputContentUrlSource) source).value();
        assertFalse(error.message().contains(sourceValue));
        assertFalse(error.message().contains("private-metadata"));
        verify(agent).getToolkit();
        verifyNoMoreInteractions(agent);
    }

    @Test
    void mapsOtherMessageConversionErrorsWithoutInvokingAgent() {
        ReActAgent agent = mock(ReActAgent.class);
        AguiMessage message =
                new AguiMessage(
                        "msg-input",
                        "assistant",
                        new MessageContent.Blocks(List.of(new TextInputContent("text"))),
                        null,
                        null);

        List<AguiEvent> events =
                new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .run(input(message))
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertInputError(
                events,
                "Structured content blocks are only supported for AG-UI user messages",
                false);
        verify(agent).getToolkit();
        verifyNoMoreInteractions(agent);
    }

    private static Stream<Arguments> documentInputs() {
        List<Arguments> cases = new ArrayList<>();
        List<Class<? extends Agent>> agents =
                List.of(Agent.class, ReActAgent.class, HarnessAgent.class);
        List<InputContentSource> sources =
                List.of(
                        new InputContentUrlSource(
                                "https://example.invalid/private.pdf?token=secret"),
                        new InputContentDataSource("cHJpdmF0ZS1kb2N1bWVudA==", "application/pdf"));
        for (Class<? extends Agent> agent : agents) {
            for (InputContentSource source : sources) {
                cases.add(Arguments.of(agent, source, false));
                cases.add(Arguments.of(agent, source, true));
            }
        }
        return cases.stream();
    }

    private static RunAgentInput input(AguiMessage message) {
        return RunAgentInput.builder()
                .threadId("thread-input")
                .runId("run-input")
                .messages(List.of(message))
                .build();
    }

    private static void assertInputError(
            List<AguiEvent> events, String message, boolean legacyFinish) {
        assertNotNull(events);
        assertEquals(
                legacyFinish
                        ? List.of(
                                AguiEventType.RUN_STARTED,
                                AguiEventType.RUN_ERROR,
                                AguiEventType.RUN_FINISHED)
                        : List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                events.stream().map(AguiEvent::getType).toList());
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(message, error.message());
        assertEquals("INVALID_INPUT_ERROR", error.code());
        assertEquals("thread-input", error.threadId());
        assertEquals("run-input", error.runId());
        assertNotNull(error.timestamp());
    }
}
