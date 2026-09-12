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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStatus;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ModelException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ResponsesResponseParser}, covering non-streaming response assembly,
 * reasoning extraction, text concatenation, tool-use parsing, refusal gating,
 * usage mapping, finish-reason mapping, and metadata preservation.
 */
class ResponsesResponseParserTest {

    private static final String MODEL = TestSdkFixtures.MODEL_NAME;

    private static ChatResponse parse(Response response) {
        return ResponsesResponseParser.parse(response, MODEL, Instant.now());
    }

    // ── Fixed-order assembly ──────────────────────────────────────────────

    @Test
    void interleavedItemsAssembledInFixedOrder() {
        Response response = TestSdkFixtures.interleavedResponse();
        ChatResponse result = parse(response);

        List<ContentBlock> blocks = result.getContent();
        // Expected order: ThinkingBlock -> TextBlock -> ToolUseBlock
        assertEquals(3, blocks.size());
        assertInstanceOf(ThinkingBlock.class, blocks.get(0));
        assertInstanceOf(TextBlock.class, blocks.get(1));
        assertInstanceOf(ToolUseBlock.class, blocks.get(2));
    }

    @Test
    void thinkingBlockFirstWhenSummaryPresent() {
        Response response = TestSdkFixtures.textResponse("hello");
        // Add reasoning before the message by building a custom response
        Response reasoningResponse =
                TestSdkFixtures.completedResponse(
                        List.of(
                                TestSdkFixtures.reasoningItem("summary", null, null),
                                TestSdkFixtures.messageItem("hello")));
        ChatResponse result = parse(reasoningResponse);
        List<ContentBlock> blocks = result.getContent();
        assertEquals(2, blocks.size());
        assertInstanceOf(ThinkingBlock.class, blocks.get(0));
        assertInstanceOf(TextBlock.class, blocks.get(1));
    }

    @Test
    void textBlockSecondWhenNoReasoning() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        List<ContentBlock> blocks = result.getContent();
        assertEquals(1, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
    }

    @Test
    void toolUseBlockLast() {
        Response response =
                TestSdkFixtures.completedResponse(
                        List.of(
                                TestSdkFixtures.functionCallItem("call_1", "get_weather", "{}"),
                                TestSdkFixtures.messageItem("result")));
        ChatResponse result = parse(response);
        List<ContentBlock> blocks = result.getContent();
        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertInstanceOf(ToolUseBlock.class, blocks.get(1));
    }

    // ── Tool use state ────────────────────────────────────────────────────

    @Test
    void toolUseBlockStateIsPending() {
        Response response = TestSdkFixtures.functionCallResponse("call_1", "get_weather", "{}");
        ChatResponse result = parse(response);
        ToolUseBlock block = (ToolUseBlock) result.getContent().get(0);
        assertEquals(ToolCallState.PENDING, block.getState());
    }

    // ── Reasoning extraction ──────────────────────────────────────────────

    @Test
    void reasoningSummaryCreatesThinkingBlock() {
        Response response = TestSdkFixtures.reasoningResponse("thinking about it", "enc123", null);
        ChatResponse result = parse(response);
        List<ContentBlock> blocks = result.getContent();
        assertEquals(1, blocks.size());
        assertInstanceOf(ThinkingBlock.class, blocks.get(0));
        ThinkingBlock tb = (ThinkingBlock) blocks.get(0);
        assertEquals("thinking about it", tb.getThinking());
    }

    @Test
    void encryptedReasoningNotInThinkingBlock() {
        Response response =
                TestSdkFixtures.reasoningResponse("summary text", "encrypted_data", null);
        ChatResponse result = parse(response);
        ThinkingBlock tb = (ThinkingBlock) result.getContent().get(0);
        // ThinkingBlock.getThinking() must contain only summary, not encrypted content
        assertEquals("summary text", tb.getThinking());
        assertFalse(tb.getThinking().contains("encrypted_data"));
        // Encrypted content is in metadata, not in ThinkingBlock
        assertEquals(
                "encrypted_data",
                tb.getMetadata().get(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT));
    }

    @Test
    void reasoningTextNotConcatenatedWithSummary() {
        Response response =
                TestSdkFixtures.reasoningResponse("summary", "enc", "raw reasoning text");
        ChatResponse result = parse(response);
        ThinkingBlock tb = (ThinkingBlock) result.getContent().get(0);
        // ThinkingBlock contains only summary, not raw reasoning text
        assertEquals("summary", tb.getThinking());
        assertFalse(tb.getThinking().contains("raw reasoning text"));
        // Raw reasoning text is in metadata
        assertEquals(
                "raw reasoning text",
                tb.getMetadata().get(OpenAIOfficialConstants.MD_REASONING_TEXT));
    }

    @Test
    void reasoningSummaryWrittenToThinkingBlock() {
        Response response = TestSdkFixtures.reasoningResponse("my summary", "enc", null);
        ChatResponse result = parse(response);
        ThinkingBlock tb = (ThinkingBlock) result.getContent().get(0);
        assertEquals("my summary", tb.getThinking());
    }

    // ── Text concatenation ───────────────────────────────────────────────

    @Test
    void multipleMessageItemsConcatenatedIntoSingleTextBlock() {
        Response response =
                TestSdkFixtures.completedResponse(
                        List.of(
                                TestSdkFixtures.messageItem("Hello"),
                                TestSdkFixtures.messageItem("World")));
        ChatResponse result = parse(response);
        List<ContentBlock> blocks = result.getContent();
        assertEquals(1, blocks.size());
        TextBlock tb = (TextBlock) blocks.get(0);
        assertEquals("HelloWorld", tb.getText());
    }

    @Test
    void multipleOutputTextPartsConcatenated() {
        Response response =
                TestSdkFixtures.completedResponse(
                        List.of(TestSdkFixtures.messageItemMultiText("Hello", " ", "World")));
        ChatResponse result = parse(response);
        TextBlock tb = (TextBlock) result.getContent().get(0);
        assertEquals("Hello World", tb.getText());
    }

    @Test
    void emptyTextDoesNotProduceTextBlock() {
        Response response = TestSdkFixtures.textResponse("");
        ChatResponse result = parse(response);
        assertTrue(result.getContent().isEmpty());
    }

    // ── Tool args parse failure ───────────────────────────────────────────

    @Test
    void invalidJsonArgumentsYieldEmptyInputAndRawContent() {
        Response response =
                TestSdkFixtures.functionCallResponse("call_1", "get_weather", "not valid json");
        ChatResponse result = parse(response);
        ToolUseBlock block = (ToolUseBlock) result.getContent().get(0);
        assertEquals("not valid json", block.getContent());
        assertTrue(block.getInput().isEmpty());
    }

    @Test
    void validJsonArgumentsParsedIntoInput() {
        Response response =
                TestSdkFixtures.functionCallResponse(
                        "call_1", "get_weather", "{\"city\":\"SF\",\"unit\":\"c\"}");
        ChatResponse result = parse(response);
        ToolUseBlock block = (ToolUseBlock) result.getContent().get(0);
        assertEquals("SF", block.getInput().get("city"));
        assertEquals("c", block.getInput().get("unit"));
        assertEquals("{\"city\":\"SF\",\"unit\":\"c\"}", block.getContent());
    }

    // ── Refusal ───────────────────────────────────────────────────────────

    @Test
    void refusalThrowsModelException() {
        Response response = TestSdkFixtures.refusalResponse("I cannot help with that");
        assertThrows(ModelException.class, () -> parse(response));
    }

    @Test
    void refusalExceptionContainsRefusalText() {
        Response response = TestSdkFixtures.refusalResponse("content policy violation");
        ModelException ex = assertThrows(ModelException.class, () -> parse(response));
        assertTrue(ex.getMessage().contains("content policy violation"));
    }

    @Test
    void refusalExceptionHasOpenaiOfficialProvider() {
        Response response = TestSdkFixtures.refusalResponse("denied");
        ModelException ex = assertThrows(ModelException.class, () -> parse(response));
        assertEquals("openai-official", ex.getProvider());
    }

    // ── Usage mapping ─────────────────────────────────────────────────────

    @Test
    void usageFieldsMappedToChatUsage() {
        Response response = TestSdkFixtures.usageResponse(100L, 50L, 20L, 10L);
        ChatResponse result = parse(response);
        ChatUsage usage = result.getUsage();
        assertNotNull(usage);
        assertEquals(100, usage.getInputTokens());
        assertEquals(50, usage.getOutputTokens());
        assertEquals(20, usage.getCachedTokens());
        assertEquals(150, usage.getTotalTokens());
    }

    @Test
    void reasoningTokensInMetadata() {
        Response response = TestSdkFixtures.usageResponse(100L, 50L, 0L, 15L);
        ChatResponse result = parse(response);
        assertEquals(
                15, result.getMetadata().get(OpenAIOfficialConstants.MD_USAGE_REASONING_TOKENS));
    }

    @Test
    void usageAbsentYieldsNullChatUsage() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        assertNull(result.getUsage());
    }

    // ── Finish reason mapping ─────────────────────────────────────────────

    @Test
    void statusCompletedTransmittedAsFinishReason() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        assertEquals("completed", result.getFinishReason());
    }

    @Test
    void statusIncompleteTransmittedAsFinishReason() {
        Response response =
                TestSdkFixtures.response(
                        List.of(TestSdkFixtures.messageItem("partial")),
                        ResponseStatus.INCOMPLETE,
                        null);
        ChatResponse result = parse(response);
        assertEquals("incomplete", result.getFinishReason());
    }

    @Test
    void statusFailedTransmittedAsFinishReason() {
        Response response =
                TestSdkFixtures.response(
                        List.of(TestSdkFixtures.messageItem("")), ResponseStatus.FAILED, null);
        ChatResponse result = parse(response);
        assertEquals("failed", result.getFinishReason());
    }

    @Test
    void statusAbsentYieldsNullFinishReason() {
        Response response =
                TestSdkFixtures.response(List.of(TestSdkFixtures.messageItem("hello")), null, null);
        ChatResponse result = parse(response);
        assertNull(result.getFinishReason());
    }

    // ── Metadata preservation ────────────────────────────────────────────

    @Test
    void responseIdInMetadata() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        assertEquals(
                "resp_test_123", result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_ID));
    }

    @Test
    void responseStatusInMetadata() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        assertEquals(
                "completed", result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_STATUS));
    }

    @Test
    void createdAtInMetadata() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        Object createdAt = result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_CREATED_AT);
        assertTrue(
                createdAt instanceof Double,
                "createdAt should be Double, got " + createdAt.getClass());
        assertEquals(1697000000.5, createdAt);
    }

    @Test
    void completedAtInMetadata() {
        Response response = TestSdkFixtures.fullMetadataResponse();
        ChatResponse result = parse(response);
        Object completedAt =
                result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_COMPLETED_AT);
        assertTrue(
                completedAt instanceof Double,
                "completedAt should be Double, got " + completedAt.getClass());
        assertEquals(1697000001.5, completedAt);
    }

    @Test
    void serviceTierInMetadata() {
        Response response = TestSdkFixtures.fullMetadataResponse();
        ChatResponse result = parse(response);
        assertEquals(
                "priority",
                result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_SERVICE_TIER));
    }

    @Test
    void incompleteReasonInMetadata() {
        Response response = TestSdkFixtures.fullMetadataResponse();
        ChatResponse result = parse(response);
        assertEquals(
                "max_output_tokens",
                result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_INCOMPLETE_REASON));
    }

    @Test
    void errorInMetadata() {
        Response response = TestSdkFixtures.fullMetadataResponse();
        ChatResponse result = parse(response);
        Object error = result.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_ERROR);
        assertTrue(error instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> errorMap = (Map<String, Object>) error;
        assertEquals("Something went wrong", errorMap.get("message"));
        assertEquals("server_error", errorMap.get("code"));
    }

    @Test
    void metadataValuesAreJsonCompatible() {
        Response response = TestSdkFixtures.usageResponse(100L, 50L, 20L, 10L);
        ChatResponse result = parse(response);
        for (Map.Entry<String, Object> entry : result.getMetadata().entrySet()) {
            Object value = entry.getValue();
            assertTrue(
                    value instanceof String
                            || value instanceof Number
                            || value instanceof Map
                            || value instanceof List);
        }
    }

    // ── Unknown output item handling ─────────────────────────────────────

    @Test
    void emptyOutputProducesNoContentBlocks() {
        Response response = TestSdkFixtures.completedResponse(List.of());
        ChatResponse result = parse(response);
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    void unknownOutputItemTypeSilentlyIgnored() {
        Response response =
                TestSdkFixtures.completedResponse(
                        List.of(
                                TestSdkFixtures.messageItem("hello"),
                                TestSdkFixtures.fileSearchItem()));
        ChatResponse result = parse(response);
        // Only the message item produces a TextBlock; file_search_call is ignored
        assertEquals(1, result.getContent().size());
        assertInstanceOf(TextBlock.class, result.getContent().get(0));
    }

    @Test
    void reasoningOnlyProducesThinkingBlock() {
        Response response =
                TestSdkFixtures.completedResponse(
                        List.of(TestSdkFixtures.reasoningItem("summary", "enc", null)));
        ChatResponse result = parse(response);
        assertEquals(1, result.getContent().size());
        assertInstanceOf(ThinkingBlock.class, result.getContent().get(0));
    }

    // ── Response id ───────────────────────────────────────────────────────

    @Test
    void responseIdTransmittedToChatResponseId() {
        Response response = TestSdkFixtures.textResponse("hello");
        ChatResponse result = parse(response);
        assertEquals("resp_test_123", result.getId());
    }

    private static <T> void assertInstanceOf(Class<T> expected, Object actual) {
        assertTrue(
                expected.isInstance(actual),
                "Expected "
                        + expected.getSimpleName()
                        + " but got "
                        + (actual != null ? actual.getClass().getSimpleName() : "null"));
    }
}
