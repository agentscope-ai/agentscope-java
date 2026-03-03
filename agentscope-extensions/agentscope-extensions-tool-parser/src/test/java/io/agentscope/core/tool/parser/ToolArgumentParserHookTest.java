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
package io.agentscope.core.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link ToolArgumentParserHook}.
 *
 * <p>Tests cover automatic tool argument correction via content field processing.
 *
 * @since 1.0.10
 */
@DisplayName("Tool Argument Parser Hook Tests")
class ToolArgumentParserHookTest {

    private ToolArgumentParserHook hook;
    private PreActingEvent testEvent;

    @BeforeEach
    void setUp() {
        hook = new ToolArgumentParserHook();
        // Create event with valid JSON content
        testEvent = createTestEventWithContent("{\"query\":\"test\",\"limit\":10}");
    }

    /**
     * Creates a test PreActingEvent with the given content string.
     */
    private PreActingEvent createTestEventWithContent(String content) {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("test-id")
                        .name("searchTool")
                        .input(
                                Map.of(
                                        "query", "test", "limit",
                                        10)) // Provide input for compatibility
                        .content(content)
                        .build();

        Agent mockAgent = mock(Agent.class);
        Toolkit mockToolkit = mock(Toolkit.class);

        return new PreActingEvent(mockAgent, mockToolkit, toolUse);
    }

    @Nested
    @DisplayName("Basic Hook Functionality Tests")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Should create hook with default constructor")
        void shouldCreateHookWithDefaultConstructor() {
            ToolArgumentParserHook defaultHook = new ToolArgumentParserHook();

            assertNotNull(defaultHook);
            assertEquals(100, defaultHook.priority());
        }

        @Test
        @DisplayName("Should return priority as 100")
        void shouldReturnPriority100() {
            assertEquals(100, hook.priority());
        }
    }

    @Nested
    @DisplayName("PreActingEvent Processing Tests")
    class PreActingEventProcessingTests {

        @Test
        @DisplayName("Should pass through valid JSON content unchanged")
        void shouldPassThroughValidJson() {
            PreActingEvent event = createTestEventWithContent("{\"query\":\"test\",\"limit\":10}");

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertEquals("searchTool", processedEvent.getToolUse().getName());
            // Valid JSON should pass through unchanged
            assertEquals(
                    "{\"query\":\"test\",\"limit\":10}", processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should correct markdown-wrapped JSON content")
        void shouldCorrectMarkdownWrappedJson() {
            String markdownJson = "```json\n{\"query\":\"test\"}\n```";
            PreActingEvent event = createTestEventWithContent(markdownJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            // Markdown wrapper should be removed
            String correctedContent = processedEvent.getToolUse().getContent();
            assertNotNull(correctedContent);
            // The corrected content should not contain markdown wrapper
            assertEquals(false, correctedContent.startsWith("```"));
        }

        @Test
        @DisplayName("Should handle JSON with comments")
        void shouldHandleJsonWithComments() {
            String jsonWithComment = "{\"query\":\"test\", // search query\n\"limit\":10}";
            PreActingEvent event = createTestEventWithContent(jsonWithComment);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should handle single-quoted JSON")
        void shouldHandleSingleQuotedJson() {
            String singleQuotedJson = "{'query':'test','limit':10}";
            PreActingEvent event = createTestEventWithContent(singleQuotedJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should handle JSON with trailing comma")
        void shouldHandleTrailingComma() {
            String trailingCommaJson = "{\"query\":\"test\",}";
            PreActingEvent event = createTestEventWithContent(trailingCommaJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle invalid JSON gracefully")
        void shouldHandleInvalidJson() {
            PreActingEvent event = createTestEventWithContent("definitely not json");

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            // Hook should not throw exception, return original event
            assertNotNull(processedEvent);
            assertEquals("searchTool", processedEvent.getToolUse().getName());
        }

        @Test
        @DisplayName("Should handle null content gracefully")
        void shouldHandleNullContent() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("test-id")
                            .name("searchTool")
                            .input(Map.of())
                            .content(null) // Null content
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            // Should return original event without processing
            assertNotNull(processedEvent);
            assertSame(event, processedEvent);
        }

        @Test
        @DisplayName("Should handle empty content")
        void shouldHandleEmptyContent() {
            PreActingEvent event = createTestEventWithContent("");

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
        }
    }

    @Nested
    @DisplayName("Event Type Filtering Tests")
    class EventTypeFilteringTests {

        @Test
        @DisplayName("Should pass through PostActingEvent unchanged")
        void shouldPassThroughPostActingEvent() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("test-id")
                            .name("searchTool")
                            .input(Map.of("query", "test"))
                            .content("{\"query\":\"test\"}")
                            .build();

            ToolResultBlock toolResult = mock(ToolResultBlock.class);

            PostActingEvent postEvent =
                    new PostActingEvent(
                            mock(Agent.class), mock(Toolkit.class), toolUse, toolResult);

            // Process event
            Mono<PostActingEvent> result = hook.onEvent(postEvent);
            PostActingEvent processedEvent = result.block();

            // Verify the event is returned unchanged (same instance)
            assertNotNull(processedEvent);
            assertSame(postEvent, processedEvent, "PostActingEvent should pass through unchanged");
        }

        @Test
        @DisplayName("Should process PreActingEvent")
        void shouldProcessPreActingEvent() {
            Mono<PreActingEvent> result = hook.onEvent(testEvent);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertEquals("searchTool", processedEvent.getToolUse().getName());
        }
    }

    @Nested
    @DisplayName("Special Content Tests")
    class SpecialContentTests {

        @Test
        @DisplayName("Should handle unicode and special characters")
        void shouldHandleUnicodeAndSpecialCharacters() {
            String specialContent =
                    "{\"text\":\"测试\\n\\t\\\"quoted\\\" 'single' \\\\escaped /slash\"}";
            PreActingEvent event = createTestEventWithContent(specialContent);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should handle nested JSON structures")
        void shouldHandleNestedJsonStructures() {
            String nestedJson = "{\"data\":{\"items\":[1,2,3],\"meta\":{\"count\":3}}}";
            PreActingEvent event = createTestEventWithContent(nestedJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should handle array content")
        void shouldHandleArrayContent() {
            String arrayJson = "{\"items\":[1,2,3,4,5],\"tags\":[\"java\",\"test\",\"parser\"]}";
            PreActingEvent event = createTestEventWithContent(arrayJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }

        @Test
        @DisplayName("Should handle different data types")
        void shouldHandleDifferentDataTypes() {
            String mixedTypesJson =
                    "{\"enabled\":true,\"count\":42,\"price\":19.99,\"ratio\":0.75,\"name\":\"test\"}";
            PreActingEvent event = createTestEventWithContent(mixedTypesJson);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            assertNotNull(processedEvent.getToolUse().getContent());
        }
    }

    @Nested
    @DisplayName("Reactive Programming Tests")
    class ReactiveProgrammingTests {

        @Test
        @DisplayName("Should return Mono that completes successfully")
        void shouldReturnCompletingMono() {
            Mono<PreActingEvent> result = hook.onEvent(testEvent);

            assertNotNull(result);
            assertNotNull(result.block());
        }

        @Test
        @DisplayName("Should handle multiple sequential events")
        void shouldHandleSequentialEvents() {
            PreActingEvent event1 = createTestEventWithContent("{\"query\":\"test1\"}");
            PreActingEvent event2 = createTestEventWithContent("{\"query\":\"test2\"}");

            Mono<PreActingEvent> result1 = hook.onEvent(event1);
            Mono<PreActingEvent> result2 = hook.onEvent(event2);

            assertNotNull(result1.block());
            assertNotNull(result2.block());
        }
    }

    @Nested
    @DisplayName("ToolUseBlock Update Tests")
    class ToolUseBlockUpdateTests {

        @Test
        @DisplayName("Should preserve ToolUseBlock metadata after correction")
        void shouldPreserveMetadataAfterCorrection() {
            ToolUseBlock originalToolUse =
                    ToolUseBlock.builder()
                            .id("test-id")
                            .name("searchTool")
                            .input(Map.of())
                            .content("```json\n{\"query\":\"test\"}\n```")
                            .metadata(Map.of("key1", "value1", "key2", "value2"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, originalToolUse);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            ToolUseBlock correctedToolUse = processedEvent.getToolUse();

            // Verify metadata is preserved
            assertNotNull(correctedToolUse.getMetadata());
            assertEquals("value1", correctedToolUse.getMetadata().get("key1"));
            assertEquals("value2", correctedToolUse.getMetadata().get("key2"));

            // Verify ID and name are preserved
            assertEquals("test-id", correctedToolUse.getId());
            assertEquals("searchTool", correctedToolUse.getName());
        }

        @Test
        @DisplayName("Should update only content field when correction applied")
        void shouldUpdateOnlyContentField() {
            String originalContent = "```json\n{\"query\":\"test\"}\n```";
            PreActingEvent event = createTestEventWithContent(originalContent);

            Mono<PreActingEvent> result = hook.onEvent(event);
            PreActingEvent processedEvent = result.block();

            assertNotNull(processedEvent);
            ToolUseBlock correctedToolUse = processedEvent.getToolUse();

            // Verify fields are preserved or updated correctly
            assertEquals("test-id", correctedToolUse.getId());
            assertEquals("searchTool", correctedToolUse.getName());
            assertNotNull(correctedToolUse.getContent());
        }
    }
}
