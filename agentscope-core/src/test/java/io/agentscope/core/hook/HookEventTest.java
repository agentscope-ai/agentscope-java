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
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for HookEvent classes.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Event creation with valid data</li>
 *   <li>Null validation in constructors and setters</li>
 *   <li>Context access and modifier functionality</li>
 * </ul>
 */
@DisplayName("HookEvent Tests")
class HookEventTest {

    private Agent testAgent;
    private Msg testMessage;
    private List<Msg> testMessages;
    private GenerateOptions generateOptions;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        testAgent =
                new AgentBase("TestAgent") {
                    @Override
                    protected Mono<Msg> doCall(List<Msg> msgs) {
                        return Mono.just(msgs.get(0));
                    }

                    @Override
                    protected Mono<Void> doObserve(Msg msg) {
                        return Mono.empty();
                    }

                    @Override
                    protected Mono<Msg> handleInterrupt(
                            InterruptContext context, Msg... originalArgs) {
                        return Mono.just(
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Interrupted").build())
                                        .build());
                    }
                };

        testMessage =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        testMessages = List.of(testMessage);
        generateOptions = GenerateOptions.builder().temperature(0.7).build();
        toolkit = new Toolkit();
    }

    @Nested
    @DisplayName("PreCallEvent Tests")
    class PreCallEventTests {

        @Test
        @DisplayName("Should create and access event")
        void testCreationAndAccess() {
            PreCallEvent event = new PreCallEvent(testAgent, null);

            assertEquals(HookEventType.PRE_CALL, event.getType());
            assertEquals(testAgent, event.getAgent());
            assertNotNull(event.getTimestamp());
            assertNull(event.getMemory());
        }

        @Test
        @DisplayName("Should reject null agent")
        void testNullAgent() {
            assertThrows(NullPointerException.class, () -> new PreCallEvent(null, null));
        }
    }

    @Nested
    @DisplayName("PostCallEvent Tests")
    class PostCallEventTests {

        @Test
        @DisplayName("Should create and modify message")
        void testModification() {
            PostCallEvent event = new PostCallEvent(testAgent, testMessage);

            assertEquals(testMessage, event.getFinalMessage());

            Msg newMsg =
                    Msg.builder()
                            .name("Modified")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("New").build())
                            .build();

            event.setFinalMessage(newMsg);
            assertEquals(newMsg, event.getFinalMessage());
        }

        @Test
        @DisplayName("Should reject null in constructor and setter")
        void testNullValidation() {
            assertThrows(NullPointerException.class, () -> new PostCallEvent(null, testMessage));
            assertThrows(NullPointerException.class, () -> new PostCallEvent(testAgent, null));

            PostCallEvent event = new PostCallEvent(testAgent, testMessage);
            assertThrows(NullPointerException.class, () -> event.setFinalMessage(null));
        }
    }

    @Nested
    @DisplayName("PreReasoningEvent Tests")
    class PreReasoningEventTests {

        @Test
        @DisplayName("Should create and modify input messages")
        void testModification() {
            PreReasoningEvent event =
                    new PreReasoningEvent(testAgent, "gpt-4", generateOptions, testMessages);

            assertEquals("gpt-4", event.getModelName());
            assertEquals(generateOptions, event.getGenerateOptions());
            assertEquals(1, event.getInputMessages().size());

            List<Msg> newMsgs =
                    List.of(
                            Msg.builder()
                                    .name("System")
                                    .role(MsgRole.SYSTEM)
                                    .content(TextBlock.builder().text("Modified").build())
                                    .build());

            event.setInputMessages(newMsgs);
            assertEquals(1, event.getInputMessages().size());
            assertEquals("System", event.getInputMessages().get(0).getName());
        }

        @Test
        @DisplayName("Should reject null parameters")
        void testNullValidation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new PreReasoningEvent(null, "gpt-4", generateOptions, testMessages));
            assertThrows(
                    NullPointerException.class,
                    () -> new PreReasoningEvent(testAgent, null, generateOptions, testMessages));
            assertThrows(
                    NullPointerException.class,
                    () -> new PreReasoningEvent(testAgent, "gpt-4", generateOptions, null));

            PreReasoningEvent event =
                    new PreReasoningEvent(testAgent, "gpt-4", generateOptions, testMessages);
            assertThrows(NullPointerException.class, () -> event.setInputMessages(null));
        }
    }

    @Nested
    @DisplayName("PostReasoningEvent Tests")
    class PostReasoningEventTests {

        @Test
        @DisplayName("Should create and modify reasoning message")
        void testModification() {
            PostReasoningEvent event =
                    new PostReasoningEvent(testAgent, "gpt-4", generateOptions, testMessage);

            assertEquals(testMessage, event.getReasoningMessage());

            Msg newMsg =
                    Msg.builder()
                            .name("Modified")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("New reasoning").build())
                            .build();

            event.setReasoningMessage(newMsg);
            assertEquals(newMsg, event.getReasoningMessage());
        }
    }

    @Nested
    @DisplayName("ReasoningChunkEvent Tests")
    class ReasoningChunkEventTests {

        @Test
        @DisplayName("Should provide both incremental and accumulated chunks")
        void testChunkAccess() {
            Msg incrementalChunk =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("chunk").build())
                            .build();
            Msg accumulatedChunk =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("accumulated").build())
                            .build();

            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            testAgent,
                            "gpt-4",
                            generateOptions,
                            incrementalChunk,
                            accumulatedChunk);

            assertEquals(HookEventType.REASONING_CHUNK, event.getType());
            assertEquals(incrementalChunk, event.getIncrementalChunk());
            assertEquals(accumulatedChunk, event.getAccumulated());
        }
    }

    @Nested
    @DisplayName("PreActingEvent Tests")
    class PreActingEventTests {

        @Test
        @DisplayName("Should create and modify tool use")
        void testModification() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("call-1")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing"))
                            .build();

            PreActingEvent event = new PreActingEvent(testAgent, toolkit, toolUse);

            assertEquals(HookEventType.PRE_ACTING, event.getType());
            assertEquals(toolkit, event.getToolkit());
            assertEquals(toolUse, event.getToolUse());

            ToolUseBlock newToolUse =
                    ToolUseBlock.builder()
                            .id("call-2")
                            .name("search")
                            .input(Map.of("query", "test"))
                            .build();

            event.setToolUse(newToolUse);
            assertEquals(newToolUse, event.getToolUse());
        }
    }

    @Nested
    @DisplayName("PostActingEvent Tests")
    class PostActingEventTests {

        @Test
        @DisplayName("Should create and modify tool result")
        void testModification() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("call-1").name("get_weather").input(Map.of()).build();
            ToolResultBlock result = ToolResultBlock.text("Sunny, 25°C");

            PostActingEvent event = new PostActingEvent(testAgent, toolkit, toolUse, result);

            assertEquals(HookEventType.POST_ACTING, event.getType());
            assertEquals(toolkit, event.getToolkit());
            assertEquals(toolUse, event.getToolUse());
            assertEquals(result, event.getToolResult());

            ToolResultBlock newResult = ToolResultBlock.text("Modified result");
            event.setToolResult(newResult);
            assertEquals(newResult, event.getToolResult());
        }
    }

    @Nested
    @DisplayName("ActingChunkEvent Tests")
    class ActingChunkEventTests {

        @Test
        @DisplayName("Should create and access chunk")
        void testCreationAndAccess() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("call-1")
                            .name("process_data")
                            .input(Map.of())
                            .build();
            ToolResultBlock chunk = ToolResultBlock.text("Processing step 1");

            ActingChunkEvent event = new ActingChunkEvent(testAgent, toolkit, toolUse, chunk);

            assertEquals(HookEventType.ACTING_CHUNK, event.getType());
            assertEquals(toolkit, event.getToolkit());
            assertEquals(toolUse, event.getToolUse());
            assertEquals(chunk, event.getChunk());
        }
    }

    @Nested
    @DisplayName("ErrorEvent Tests")
    class ErrorEventTests {

        @Test
        @DisplayName("Should create and access error")
        void testCreationAndAccess() {
            Throwable error = new RuntimeException("Test error");
            ErrorEvent event = new ErrorEvent(testAgent, error);

            assertEquals(HookEventType.ERROR, event.getType());
            assertEquals(error, event.getError());
            assertEquals("Test error", event.getError().getMessage());
        }

        @Test
        @DisplayName("Should reject null parameters")
        void testNullValidation() {
            Throwable error = new RuntimeException("Test");
            assertThrows(NullPointerException.class, () -> new ErrorEvent(null, error));
            assertThrows(NullPointerException.class, () -> new ErrorEvent(testAgent, null));
        }
    }
}
