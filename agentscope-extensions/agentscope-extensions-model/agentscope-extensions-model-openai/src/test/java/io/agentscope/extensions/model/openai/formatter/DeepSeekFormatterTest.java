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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIFunction;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DeepSeekFormatter.
 *
 * <p>Tests verify DeepSeek-specific requirements:
 * <ul>
 *   <li>No name field in messages</li>
 *   <li>System message roles are preserved</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>reasoning_content preservation and backfill in thinking mode (issue #3246)</li>
 *   <li>Optional empty user message appending</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("DeepSeekFormatter Unit Tests")
class DeepSeekFormatterTest {

    private DeepSeekFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DeepSeekFormatter();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor should not append empty user message")
        void testDefaultConstructor() {
            DeepSeekFormatter defaultFormatter = new DeepSeekFormatter();
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = defaultFormatter.format(messages);

            // Should not append empty user message by default
            assertEquals(2, result.size());
            assertEquals("assistant", result.get(result.size() - 1).getRole());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=true")
        void testConstructorWithAppendEmptyUser() {
            DeepSeekFormatter appendFormatter = new DeepSeekFormatter(true);
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = appendFormatter.format(messages);

            // Should append empty user message
            assertEquals(3, result.size());
            assertEquals("user", result.get(result.size() - 1).getRole());
            assertEquals("", result.get(result.size() - 1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=false")
        void testConstructorWithoutAppendEmptyUser() {
            DeepSeekFormatter noAppendFormatter = new DeepSeekFormatter(false);
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = noAppendFormatter.format(messages);

            assertEquals(2, result.size());
            assertEquals("assistant", result.get(result.size() - 1).getRole());
        }
    }

    @Nested
    @DisplayName("supportsStrict Tests")
    class SupportsStrictTests {

        @Test
        @DisplayName("supportsStrict should return false")
        void testSupportsStrictReturnsFalse() {
            assertFalse(formatter.supportsStrict());
        }

        @Test
        @DisplayName("applyTools should not include strict parameter")
        void testApplyToolsWithoutStrict() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("deepseek-chat").messages(List.of()).build();

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("test_tool")
                            .description("Test tool")
                            .strict(true)
                            .build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertEquals(1, request.getTools().size());
            // Strict should not be set because DeepSeek doesn't support it
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }

    @Nested
    @DisplayName("applyDeepSeekFixes Tests")
    class ApplyDeepSeekFixesTests {

        @Test
        @DisplayName("Should remove name field from messages")
        void testRemoveNameField() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("user")
                                    .name("Alice")
                                    .content("Hello")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            assertNull(result.get(0).getName());
            assertEquals("Hello", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should preserve system message role")
        void testPreserveSystemRole() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("system")
                                    .content("You are helpful")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            assertEquals("system", result.get(0).getRole());
            assertEquals("You are helpful", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should keep reasoning_content on assistant messages")
        void testKeepReasoningContent() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Answer")
                                    .reasoningContent("My thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(2, result.size());
            // Current turn (after last user) should keep reasoning_content
            assertEquals("My thinking", result.get(1).getReasoningContent());
        }

        @Test
        @DisplayName("Should preserve reasoning_content across assistant turns")
        void testPreserveReasoningContentAcrossAssistantTurns() {
            // DeepSeek requires reasoning_content to be fully passed back for ALL assistant
            // turns when the request carries tools — even turns without tool calls
            // (issue #3246).
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("First question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("First answer")
                                    .reasoningContent("First thinking")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Second question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Second answer")
                                    .reasoningContent("Second thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(4, result.size());
            // Previous turn reasoning_content must be preserved (issue #3246)
            assertEquals("First thinking", result.get(1).getReasoningContent());
            // Current turn should keep reasoning_content
            assertEquals("Second thinking", result.get(3).getReasoningContent());
        }

        @Test
        @DisplayName("Should preserve tool calls in messages")
        void testPreserveToolCalls() {
            OpenAIToolCall toolCall =
                    OpenAIToolCall.builder()
                            .id("call_123")
                            .type("function")
                            .function(OpenAIFunction.of("test_tool", "{}"))
                            .build();

            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .toolCalls(List.of(toolCall))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            assertNull(result.get(0).getName());
            assertNotNull(result.get(0).getToolCalls());
            assertEquals(1, result.get(0).getToolCalls().size());
            assertEquals("call_123", result.get(0).getToolCalls().get(0).getId());
        }

        @Test
        @DisplayName("Should preserve tool call ID in tool messages")
        void testPreserveToolCallId() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("tool")
                                    .toolCallId("call_123")
                                    .content("Tool result")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            assertEquals("call_123", result.get(0).getToolCallId());
        }

        @Test
        @DisplayName("Should preserve system role and list content when removing name")
        void testHandleContentAsList() {
            List<OpenAIContentPart> contentParts =
                    List.of(OpenAIContentPart.text("Hello"), OpenAIContentPart.text("World"));

            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("system")
                                    .name("System")
                                    .content(contentParts)
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            assertEquals("system", result.get(0).getRole());
            assertNull(result.get(0).getName());
            assertTrue(result.get(0).getContent() instanceof List);
        }

        @Test
        @DisplayName("Should return message unchanged if no fixes needed")
        void testReturnUnchangedIfNoFixesNeeded() {
            OpenAIMessage original = OpenAIMessage.builder().role("user").content("Hello").build();
            List<OpenAIMessage> messages = List.of(original);

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            // Same object reference if no changes
            assertEquals(original, result.get(0));
        }
    }

    @Nested
    @DisplayName("Reasoning Preservation for Thinking Mode")
    class ReasoningPreservationTests {

        @Test
        @DisplayName("Should use original behavior when thinking mode is not enabled")
        void testShouldUseOriginalBehaviorWithoutThinkingMode() {
            // No reasoning_content in any message → thinking mode is off.
            // No backfill happens; only the legacy name-field removal applies.
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Search it").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("call_1")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "web_search",
                                                                            "{\"q\":\"x\"}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Search again").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("call_2")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "web_search",
                                                                            "{\"q\":\"y\"}"))
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(4, result.size());
            // name removed in both messages (original behavior still applies)
            assertNull(result.get(1).getName());
            assertNull(result.get(3).getName());
            // tool_calls preserved
            assertNotNull(result.get(1).getToolCalls());
            assertNotNull(result.get(3).getToolCalls());
        }

        @Test
        @DisplayName("Should preserve reasoning_content across multiple rounds with tool calls")
        void testShouldPreserveReasoningAcrossMultipleRounds() {
            // Three consecutive rounds, each with a tool call.
            // When thinking mode is enabled and tool calls were made, DeepSeek API
            // requires reasoning_content to be preserved for all rounds (not just
            // the current turn), even when there are no tool_calls in the message
            // itself.
            // See: https://api-docs.deepseek.com/guides/thinking_mode#tool-calls
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Question 1").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Reasoning round 1")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("c1")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "tool_a", "{}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Question 2").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Reasoning round 2")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("c2")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "tool_b", "{}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Question 3").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Reasoning round 3")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("c3")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "tool_c", "{}"))
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(6, result.size());
            // All three rounds had tool calls, so all reasoning should be preserved
            assertEquals("Reasoning round 1", result.get(1).getReasoningContent());
            assertEquals("Reasoning round 2", result.get(3).getReasoningContent());
            assertEquals("Reasoning round 3", result.get(5).getReasoningContent());
        }

        @Test
        @DisplayName(
                "Should preserve reasoning_content for all assistant turns regardless of"
                        + " tool calls")
        void testShouldPreserveReasoningForAllAssistantTurns() {
            // DeepSeek requires reasoning_content to be fully passed back for ALL assistant
            // turns when the request carries tools — even turns without tool calls
            // (issue #3246). Rounds with and without tool calls must all keep it.
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Hello").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Hi, how can I help?")
                                    .reasoningContent("Just greeting, reply directly")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Search DeepSeek").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Need to call search tool")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("c1")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "web_search",
                                                                            "{\"q\":\"DeepSeek\"}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Check wiki too").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Query wiki")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("c2")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "wiki_search",
                                                                            "{\"q\":\"DeepSeek\"}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Summarize").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("DeepSeek is...")
                                    .reasoningContent("Summarizing results")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(8, result.size());
            // Text-only historical turn: reasoning preserved (issue #3246)
            assertEquals("Just greeting, reply directly", result.get(1).getReasoningContent());
            assertEquals("Need to call search tool", result.get(3).getReasoningContent());
            assertEquals("Query wiki", result.get(5).getReasoningContent());
            assertEquals("Summarizing results", result.get(7).getReasoningContent());
        }

        @Test
        @DisplayName(
                "Should backfill empty reasoning_content for assistant lacking it in"
                        + " thinking mode")
        void testShouldBackfillEmptyReasoningForAssistantLackingIt() {
            // Thinking mode detected from history; an assistant message lacking
            // reasoning_content must be backfilled with an empty value so tools-carrying
            // requests are not rejected (issue #3246). Tool calls must survive the rebuild.
            OpenAIToolCall toolCall =
                    OpenAIToolCall.builder()
                            .id("call_bf")
                            .type("function")
                            .function(OpenAIFunction.of("notify", "{}"))
                            .build();

            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("First").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("First answer")
                                    .reasoningContent("history reasoning")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Next").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("notification")
                                    .toolCalls(List.of(toolCall))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals("history reasoning", result.get(1).getReasoningContent());
            assertEquals("", result.get(3).getReasoningContent());
            assertNotNull(result.get(3).getToolCalls());
            assertEquals("call_bf", result.get(3).getToolCalls().get(0).getId());
        }

        @Test
        @DisplayName("Should remove name but preserve reasoning_content in thinking mode")
        void testShouldRemoveNameButPreserveReasoningInThinkingMode() {
            // In thinking mode the legacy name removal still applies, while the assistant's
            // own reasoning_content must be kept (issue #3246).
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("Answer")
                                    .reasoningContent("keep me")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertNull(result.get(1).getName());
            assertEquals("keep me", result.get(1).getReasoningContent());
            assertEquals("Answer", result.get(1).getContentAsString());
        }

        @Test
        @DisplayName("Should preserve reasoning_content across tool-call and text-only rounds")
        void testShouldPreserveReasoningAcrossToolCallAndTextOnlyRounds() {
            // Within a single user turn, the model first calls a tool, then gives a final text
            // answer. All assistant messages must keep their reasoning_content
            // (issue #3246).
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("What time is it").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Need to call get_time tool")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("call_1")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "get_time", "{}"))
                                                            .build()))
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("tool")
                                    .toolCallId("call_1")
                                    .content("14:06:51")
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("It is now 14:06")
                                    .reasoningContent(
                                            "The current time based on the tool result is 14:06")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Check again").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .reasoningContent("Fetch time again")
                                    .toolCalls(
                                            List.of(
                                                    OpenAIToolCall.builder()
                                                            .id("call_2")
                                                            .type("function")
                                                            .function(
                                                                    OpenAIFunction.of(
                                                                            "get_time", "{}"))
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(6, result.size());
            assertEquals("Need to call get_time tool", result.get(1).getReasoningContent());
            assertEquals(
                    "The current time based on the tool result is 14:06",
                    result.get(3).getReasoningContent());
            assertEquals("Fetch time again", result.get(5).getReasoningContent());
        }

        @Test
        @DisplayName(
                "Should preserve reasoning_content for text-only assistant turns without tool"
                        + " calls")
        void testShouldPreserveReasoningForTextOnlyAssistantTurns() {
            // DeepSeek requires reasoning_content to be fully passed back for ALL assistant
            // turns when the request carries tools — even text-only turns (issue #3246).
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Hello").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Hi there!")
                                    .reasoningContent("Just greeting, reply directly")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Goodbye").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Goodbye!")
                                    .reasoningContent("User is saying goodbye")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(4, result.size());
            // Text-only assistant turn → reasoning preserved (issue #3246)
            assertEquals("Just greeting, reply directly", result.get(1).getReasoningContent());
            // Current turn → reasoning preserved
            assertEquals("User is saying goodbye", result.get(3).getReasoningContent());
        }
    }

    @Nested
    @DisplayName("applyDeepSeekFixes Tests (continued)")
    class ApplyDeepSeekFixesContinued {

        @Test
        @DisplayName("Should keep reasoning_content regardless of user-message position")
        void testNoUserMessages() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Answer")
                                    .reasoningContent("Thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals(1, result.size());
            // reasoning_content is never stripped; no backfill needed here
            assertEquals("Thinking", result.get(0).getReasoningContent());
            assertSame(messages.get(0), result.get(0));
        }
    }

    @Nested
    @DisplayName("appendEmptyUserIfNeeded Tests")
    class AppendEmptyUserIfNeededTests {

        @Test
        @DisplayName("Should append empty user if ends with assistant")
        void testAppendWhenEndsWithAssistant() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(3, result.size());
            assertEquals("user", result.get(2).getRole());
            assertEquals("", result.get(2).getContentAsString());
        }

        @Test
        @DisplayName("Should not append if ends with user")
        void testNoAppendWhenEndsWithUser() {
            List<OpenAIMessage> messages =
                    List.of(OpenAIMessage.builder().role("user").content("Hello").build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Should not append if list is empty")
        void testNoAppendWhenEmpty() {
            List<OpenAIMessage> messages = List.of();

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should not append if ends with tool")
        void testNoAppendWhenEndsWithTool() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("tool")
                                    .toolCallId("call_123")
                                    .content("Result")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(1, result.size());
            assertEquals("tool", result.get(0).getRole());
        }
    }

    @Nested
    @DisplayName("doFormat Integration Tests")
    class DoFormatTests {

        @Test
        @DisplayName("Should apply all DeepSeek fixes during format")
        void testFormatAppliesFixes() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("You are helpful")
                                                            .build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            // System role preserved
            assertEquals("system", result.get(0).getRole());
            // Name removed
            assertNull(result.get(1).getName());
        }

        @Test
        @DisplayName("Should format empty message list")
        void testFormatEmptyList() {
            List<OpenAIMessage> result = formatter.format(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle multiple system messages")
        void testMultipleSystemMessages() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(List.of(TextBlock.builder().text("System 1").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(List.of(TextBlock.builder().text("System 2").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            // Both system roles should be preserved
            assertEquals("system", result.get(0).getRole());
            assertEquals("system", result.get(1).getRole());
        }
    }

    @Nested
    @DisplayName("Thinking-Mode Backfill Correctness (issue #3246)")
    class ThinkingModeBackfillTests {

        @Test
        @DisplayName("Should drop name and backfill empty reasoning_content in thinking mode")
        void testDropNameAndBackfillInThinkingMode() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("First").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("First answer")
                                    .reasoningContent("history reasoning")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Next").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            OpenAIMessage fixed = result.get(3);
            assertNull(fixed.getName());
            assertEquals("", fixed.getReasoningContent());
            assertEquals("notification", fixed.getContentAsString());
        }

        @Test
        @DisplayName("Should keep reasoning_details and refusal when backfilling")
        void testBackfillKeepsReasoningDetailsAndRefusal() {
            // The rebuild must be lossless: fields unrelated to reasoning must survive the
            // reasoning_content backfill (code review follow-up).
            OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
            detail.setId("detail-1");
            detail.setText("reasoning delta");
            List<OpenAIReasoningDetail> details = List.of(detail);
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Hi").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("history")
                                    .reasoningContent("history reasoning")
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("notification")
                                    .refusal("none")
                                    .reasoningDetails(details)
                                    .build());

            OpenAIMessage fixed = DeepSeekFormatter.applyDeepSeekFixes(messages, null).get(2);

            assertEquals("", fixed.getReasoningContent());
            assertEquals(details, fixed.getReasoningDetails());
            assertEquals("none", fixed.getRefusal());
        }

        @Test
        @DisplayName("Should serialize backfilled empty reasoning_content")
        void testBackfilledReasoningContentIsSerialized() throws Exception {
            // With tools in the request, an omitted reasoning_content field triggers HTTP 400;
            // the backfilled empty string must actually be serialized (NON_NULL keeps "").
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("First").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("First answer")
                                    .reasoningContent("history reasoning")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Next").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("notification")
                                    .build());

            String json =
                    new ObjectMapper()
                            .writeValueAsString(
                                    DeepSeekFormatter.applyDeepSeekFixes(messages, null));

            assertTrue(json.contains("\"reasoning_content\":\"\""));
        }

        @Test
        @DisplayName("Thinking option enabled backfills and strips name")
        void testThinkingOptionEnabledBackfillsAndStripsName() {
            GenerateOptions options =
                    GenerateOptions.builder()
                            .additionalBodyParam("thinking", Map.of("type", "enabled"))
                            .build();
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Hi").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, options);

            assertEquals("", result.get(1).getReasoningContent());
            assertNull(result.get(1).getName());
        }

        @Test
        @DisplayName("Thinking option disabled skips backfill but still strips name")
        void testThinkingOptionDisabledSkipsBackfill() {
            // The request's thinking option decides the mode: with thinking explicitly
            // disabled there is no pass-back requirement, so nothing is backfilled. The
            // legacy name-field removal still applies.
            GenerateOptions options =
                    GenerateOptions.builder()
                            .additionalBodyParam("thinking", Map.of("type", "disabled"))
                            .build();
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("history")
                                    .reasoningContent("r")
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, options);

            assertNull(result.get(1).getReasoningContent());
            assertNull(result.get(0).getName());
            assertNull(result.get(1).getName());
        }

        @Test
        @DisplayName("Unknown thinking option shape is treated as thinking mode on")
        void testUnknownThinkingOptionShapeIsTreatedAsThinkingOn() {
            GenerateOptions options =
                    GenerateOptions.builder().additionalBodyParam("thinking", "not-a-map").build();
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("history")
                                    .reasoningContent("r")
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, options);

            assertEquals("", result.get(1).getReasoningContent());
        }

        @Test
        @DisplayName("Thinking option map with unknown type is treated as thinking mode on")
        void testThinkingOptionMapWithUnknownTypeIsTreatedAsThinkingOn() {
            GenerateOptions options =
                    GenerateOptions.builder()
                            .additionalBodyParam("thinking", Map.of("type", "auto"))
                            .build();
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("history")
                                    .reasoningContent("r")
                                    .build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, options);

            assertEquals("", result.get(1).getReasoningContent());
        }

        @Test
        @DisplayName("Empty user message appended only when conversation ends with assistant")
        void testAppendEmptyUserOnlyWhenEndingWithAssistant() {
            List<OpenAIMessage> endingWithUser =
                    List.of(OpenAIMessage.builder().role("user").content("Hi").build());
            assertSame(endingWithUser, DeepSeekFormatter.appendEmptyUserIfNeeded(endingWithUser));

            List<OpenAIMessage> endingWithAssistant =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Hi").build(),
                            OpenAIMessage.builder().role("assistant").content("hello").build());
            List<OpenAIMessage> result =
                    DeepSeekFormatter.appendEmptyUserIfNeeded(endingWithAssistant);
            assertEquals(3, result.size());
            assertEquals("user", result.get(2).getRole());
            assertEquals("", result.get(2).getContent());
        }

        @Test
        @DisplayName("Unknown thinking state still backfills a synthesized assistant turn")
        void testUnknownThinkingStateStillBackfillsSynthesizedTurn() {
            // Regression for issue #3246: no thinking option is set and the history carries
            // no reasoning_content at all (framework-synthesized turn / lost traces), yet
            // DeepSeek enables thinking server-side by default. The synthesized assistant
            // message must still be backfilled so tool-carrying requests are not rejected.
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("继续").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .content("notification")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages, null);

            assertEquals("", result.get(1).getReasoningContent());
            assertNull(result.get(1).getName());
        }
    }

    @Nested
    @DisplayName("Two-Arg Format Path Tests (production path)")
    class TwoArgFormatPathTests {

        @Test
        @DisplayName("Should backfill missing reasoning_content via format(msgs, options)")
        void testTwoArgFormatBackfillsReasoning() {
            // Production requests flow through format(msgs, options) → doFormat(msgs,
            // options). The DeepSeek fixes must apply on this path too.
            DeepSeekFormatter formatter = new DeepSeekFormatter();
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Q1").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(
                                            List.of(
                                                    ThinkingBlock.builder()
                                                            .thinking("history thinking")
                                                            .build(),
                                                    TextBlock.builder().text("A1").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Q2").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("agent-1")
                                    .content(List.of(TextBlock.builder().text("notify").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages, null);

            assertEquals("history thinking", result.get(1).getReasoningContent());
            assertEquals(
                    "",
                    result.get(3).getReasoningContent(),
                    "Hint-only assistant must be backfilled on the two-arg production path");
            assertNull(result.get(3).getName());
        }

        @Test
        @DisplayName("Should append empty user via format(msgs, options) when enabled")
        void testTwoArgFormatAppendsEmptyUser() {
            DeepSeekFormatter appendFormatter = new DeepSeekFormatter(true);
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = appendFormatter.format(messages, null);

            assertEquals(3, result.size());
            assertEquals("user", result.get(2).getRole());
            assertEquals("", result.get(2).getContentAsString());
        }
    }
}
