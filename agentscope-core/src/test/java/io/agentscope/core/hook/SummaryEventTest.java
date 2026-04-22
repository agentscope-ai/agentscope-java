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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for SummaryEvent classes.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Event creation with valid data</li>
 *   <li>Null validation in constructors and setters</li>
 *   <li>Context access and modifier functionality</li>
 * </ul>
 */
@DisplayName("SummaryEvent Tests")
class SummaryEventTest {

    private Agent testAgent;
    private Msg testMessage;
    private List<Msg> testMessages;
    private GenerateOptions generateOptions;

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

        testMessages = new ArrayList<>(List.of(testMessage));
        generateOptions = GenerateOptions.builder().temperature(0.7).build();
    }

    @Nested
    @DisplayName("PreSummaryEvent Tests")
    class PreSummaryEventTests {

        @Test
        @DisplayName("Should create event with valid data")
        void shouldCreateEventWithValidData() {
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);

            assertEquals(HookEventType.PRE_SUMMARY, event.getType());
            assertEquals(testAgent, event.getAgent());
            assertEquals("qwen-plus", event.getModelName());
            assertEquals(generateOptions, event.getGenerateOptions());
            assertEquals(testMessages, event.getInputMessages());
            assertEquals(10, event.getMaxIterations());
            assertEquals(10, event.getCurrentIteration());
            assertTrue(event.getTimestamp() > 0);
        }

        @Test
        @DisplayName("Should allow modifying input messages")
        void shouldAllowModifyingInputMessages() {
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);

            Msg newMessage =
                    Msg.builder()
                            .name("System")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text("New instruction").build())
                            .build();
            List<Msg> newMessages = List.of(newMessage);
            event.setInputMessages(newMessages);

            assertEquals(newMessages, event.getInputMessages());
        }

        @Test
        @DisplayName("Should allow overriding generate options")
        void shouldAllowOverridingGenerateOptions() {
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);

            GenerateOptions newOptions = GenerateOptions.builder().temperature(0.9).build();
            event.setGenerateOptions(newOptions);

            assertEquals(newOptions, event.getEffectiveGenerateOptions());
        }

        @Test
        @DisplayName("Should return original options when not overridden")
        void shouldReturnOriginalOptionsWhenNotOverridden() {
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);

            assertEquals(generateOptions, event.getEffectiveGenerateOptions());
        }

        @Test
        @DisplayName("Should throw NullPointerException when inputMessages is null")
        void shouldThrowWhenInputMessagesNull() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            new PreSummaryEvent(
                                    testAgent, "qwen-plus", generateOptions, null, 10, 10));
        }

        @Test
        @DisplayName("Should throw NullPointerException when setting null inputMessages")
        void shouldThrowWhenSettingNullInputMessages() {
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);

            assertThrows(NullPointerException.class, () -> event.setInputMessages(null));
        }
    }

    @Nested
    @DisplayName("SummaryChunkEvent Tests")
    class SummaryChunkEventTests {

        @Test
        @DisplayName("Should create event with valid data")
        void shouldCreateEventWithValidData() {
            Msg incrementalChunk =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("chunk").build())
                            .build();
            Msg accumulated =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("accumulated chunk").build())
                            .build();

            SummaryChunkEvent event =
                    new SummaryChunkEvent(
                            testAgent, "qwen-plus", generateOptions, incrementalChunk, accumulated);

            assertEquals(HookEventType.SUMMARY_CHUNK, event.getType());
            assertEquals(testAgent, event.getAgent());
            assertEquals("qwen-plus", event.getModelName());
            assertEquals(generateOptions, event.getGenerateOptions());
            assertEquals(incrementalChunk, event.getIncrementalChunk());
            assertEquals(accumulated, event.getAccumulated());
        }

        @Test
        @DisplayName("Should throw NullPointerException when incrementalChunk is null")
        void shouldThrowWhenIncrementalChunkNull() {
            Msg accumulated =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("accumulated").build())
                            .build();

            assertThrows(
                    NullPointerException.class,
                    () ->
                            new SummaryChunkEvent(
                                    testAgent, "qwen-plus", generateOptions, null, accumulated));
        }

        @Test
        @DisplayName("Should throw NullPointerException when accumulated is null")
        void shouldThrowWhenAccumulatedNull() {
            Msg incrementalChunk =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("chunk").build())
                            .build();

            assertThrows(
                    NullPointerException.class,
                    () ->
                            new SummaryChunkEvent(
                                    testAgent,
                                    "qwen-plus",
                                    generateOptions,
                                    incrementalChunk,
                                    null));
        }
    }

    @Nested
    @DisplayName("PostSummaryEvent Tests")
    class PostSummaryEventTests {

        @Test
        @DisplayName("Should create event with valid data")
        void shouldCreateEventWithValidData() {
            Msg summaryMessage =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Summary").build())
                            .build();

            PostSummaryEvent event =
                    new PostSummaryEvent(testAgent, "qwen-plus", generateOptions, summaryMessage);

            assertEquals(HookEventType.POST_SUMMARY, event.getType());
            assertEquals(testAgent, event.getAgent());
            assertEquals("qwen-plus", event.getModelName());
            assertEquals(generateOptions, event.getGenerateOptions());
            assertEquals(summaryMessage, event.getSummaryMessage());
            assertFalse(event.isStopRequested());
        }

        @Test
        @DisplayName("Should allow null summary message")
        void shouldAllowNullSummaryMessage() {
            PostSummaryEvent event =
                    new PostSummaryEvent(testAgent, "qwen-plus", generateOptions, null);

            assertNull(event.getSummaryMessage());
        }

        @Test
        @DisplayName("Should allow modifying summary message")
        void shouldAllowModifyingSummaryMessage() {
            Msg originalMessage =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Original").build())
                            .build();

            PostSummaryEvent event =
                    new PostSummaryEvent(testAgent, "qwen-plus", generateOptions, originalMessage);

            Msg newMessage =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Modified").build())
                            .build();
            event.setSummaryMessage(newMessage);

            assertEquals(newMessage, event.getSummaryMessage());
        }

        @Test
        @DisplayName("Should support stopAgent functionality")
        void shouldSupportStopAgentFunctionality() {
            PostSummaryEvent event =
                    new PostSummaryEvent(testAgent, "qwen-plus", generateOptions, testMessage);

            assertFalse(event.isStopRequested());
            event.stopAgent();
            assertTrue(event.isStopRequested());
        }
    }

    @Nested
    @DisplayName("Common SummaryEvent Tests")
    class CommonSummaryEventTests {

        @Test
        @DisplayName("Should throw NullPointerException when agent is null")
        void shouldThrowWhenAgentNull() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            new PreSummaryEvent(
                                    null, "qwen-plus", generateOptions, testMessages, 10, 10));
        }

        @Test
        @DisplayName("Should throw NullPointerException when modelName is null")
        void shouldThrowWhenModelNameNull() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            new PreSummaryEvent(
                                    testAgent, null, generateOptions, testMessages, 10, 10));
        }

        @Test
        @DisplayName("Should allow null generateOptions")
        void shouldAllowNullGenerateOptions() {
            PreSummaryEvent event =
                    new PreSummaryEvent(testAgent, "qwen-plus", null, testMessages, 10, 10);

            assertNull(event.getGenerateOptions());
            assertNull(event.getEffectiveGenerateOptions());
        }

        @Test
        @DisplayName("All summary events should have correct event types")
        void allSummaryEventsShouldHaveCorrectEventTypes() {
            PreSummaryEvent preSummary =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);
            assertEquals(HookEventType.PRE_SUMMARY, preSummary.getType());

            Msg msg =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("test").build())
                            .build();

            SummaryChunkEvent chunkEvent =
                    new SummaryChunkEvent(testAgent, "qwen-plus", generateOptions, msg, msg);
            assertEquals(HookEventType.SUMMARY_CHUNK, chunkEvent.getType());

            PostSummaryEvent postSummary =
                    new PostSummaryEvent(testAgent, "qwen-plus", generateOptions, msg);
            assertEquals(HookEventType.POST_SUMMARY, postSummary.getType());
        }

        @Test
        @DisplayName("Timestamp should be set automatically")
        void timestampShouldBeSetAutomatically() {
            long before = System.currentTimeMillis();
            PreSummaryEvent event =
                    new PreSummaryEvent(
                            testAgent, "qwen-plus", generateOptions, testMessages, 10, 10);
            long after = System.currentTimeMillis();

            assertTrue(event.getTimestamp() >= before);
            assertTrue(event.getTimestamp() <= after);
        }
    }
}
