/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatChoice}.
 */
@DisplayName("ChatChoice Tests")
class ChatChoiceTest {

    @Nested
    @DisplayName("Default State Tests")
    class DefaultStateTests {

        @Test
        @DisplayName("Should have default values")
        void shouldHaveDefaultValues() {
            ChatChoice choice = new ChatChoice();

            assertEquals(0, choice.getIndex());
            assertNull(choice.getMessage());
            assertNull(choice.getDelta());
            assertNull(choice.getFinishReason());
            assertNull(choice.getLogprobs());
        }
    }

    @Nested
    @DisplayName("Non-Streaming Response Tests")
    class NonStreamingResponseTests {

        @Test
        @DisplayName("Should set message for non-streaming")
        void shouldSetMessageForNonStreaming() {
            ChatChoice choice = new ChatChoice();
            ChatMessage message = new ChatMessage("assistant", "Hello!");

            choice.setIndex(0);
            choice.setMessage(message);
            choice.setFinishReason("stop");

            assertEquals(0, choice.getIndex());
            assertNotNull(choice.getMessage());
            assertEquals("assistant", choice.getMessage().getRole());
            assertEquals("Hello!", choice.getMessage().getContent());
            assertEquals("stop", choice.getFinishReason());
            assertNull(choice.getDelta());
        }

        @Test
        @DisplayName("Should handle tool_calls finish reason")
        void shouldHandleToolCallsFinishReason() {
            ChatChoice choice = new ChatChoice();
            choice.setFinishReason("tool_calls");

            assertEquals("tool_calls", choice.getFinishReason());
        }

        @Test
        @DisplayName("Should handle length finish reason")
        void shouldHandleLengthFinishReason() {
            ChatChoice choice = new ChatChoice();
            choice.setFinishReason("length");

            assertEquals("length", choice.getFinishReason());
        }

        @Test
        @DisplayName("Should handle content_filter finish reason")
        void shouldHandleContentFilterFinishReason() {
            ChatChoice choice = new ChatChoice();
            choice.setFinishReason("content_filter");

            assertEquals("content_filter", choice.getFinishReason());
        }
    }

    @Nested
    @DisplayName("Streaming Response Tests")
    class StreamingResponseTests {

        @Test
        @DisplayName("Should set delta for streaming")
        void shouldSetDeltaForStreaming() {
            ChatChoice choice = new ChatChoice();
            ChatMessage delta = new ChatMessage();
            delta.setContent("Hello");

            choice.setIndex(0);
            choice.setDelta(delta);

            assertEquals(0, choice.getIndex());
            assertNotNull(choice.getDelta());
            assertEquals("Hello", choice.getDelta().getContent());
            assertNull(choice.getMessage());
        }

        @Test
        @DisplayName("Should handle delta with tool calls")
        void shouldHandleDeltaWithToolCalls() {
            ChatChoice choice = new ChatChoice();
            ChatMessage delta = new ChatMessage();
            delta.setRole("assistant");
            delta.setToolCalls(java.util.List.of(new ToolCall("call-1", "func", "{}")));

            choice.setDelta(delta);

            assertNotNull(choice.getDelta().getToolCalls());
            assertEquals(1, choice.getDelta().getToolCalls().size());
        }
    }

    @Nested
    @DisplayName("Logprobs Tests")
    class LogprobsTests {

        @Test
        @DisplayName("Should set and get logprobs")
        void shouldSetAndGetLogprobs() {
            ChatChoice choice = new ChatChoice();
            Object logprobs = new Object();

            choice.setLogprobs(logprobs);

            assertNotNull(choice.getLogprobs());
            assertEquals(logprobs, choice.getLogprobs());
        }
    }

    @Nested
    @DisplayName("Index Tests")
    class IndexTests {

        @Test
        @DisplayName("Should handle different index values")
        void shouldHandleDifferentIndexValues() {
            ChatChoice choice1 = new ChatChoice();
            choice1.setIndex(0);
            assertEquals(0, choice1.getIndex());

            ChatChoice choice2 = new ChatChoice();
            choice2.setIndex(1);
            assertEquals(1, choice2.getIndex());

            ChatChoice choice3 = new ChatChoice();
            choice3.setIndex(5);
            assertEquals(5, choice3.getIndex());
        }
    }
}
