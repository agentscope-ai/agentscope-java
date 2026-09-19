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

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ModelException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ResponsesStreamingAssembler}, covering text deltas, reasoning
 * summary/text separation, tool-call fragment assembly, terminal re-extraction,
 * refusal gating, usage mapping, and error event handling.
 */
class ResponsesStreamingAssemblerTest {

    private static final String MODEL = TestSdkFixtures.MODEL_NAME;

    private static List<ChatResponse> assemble(List<ResponseStreamEvent> events) {
        StreamResponse<ResponseStreamEvent> stream = TestSdkFixtures.streamOf(events);
        return ResponsesStreamingAssembler.assemble(stream, MODEL, Instant.now())
                .collectList()
                .block();
    }

    private static ChatResponse terminalBlock(List<ChatResponse> results) {
        assertFalse(results.isEmpty(), "Expected at least one result");
        return results.get(results.size() - 1);
    }

    // ── Text streaming ────────────────────────────────────────────────────

    @Test
    void textDeltaProducesTextBlock() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Hello", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // First block is a text delta
        assertEquals(2, results.size());
        ChatResponse textBlock = results.get(0);
        assertNull(textBlock.getUsage());
        assertEquals(1, textBlock.getContent().size());
        assertInstanceOf(TextBlock.class, textBlock.getContent().get(0));
        assertEquals("Hello", ((TextBlock) textBlock.getContent().get(0)).getText());
    }

    @Test
    void textDoneDoesNotProduceExtraBlock() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Hi", "msg_001"),
                        TestSdkFixtures.noopEvent(), // represents output_text.done
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // Only 1 text delta + 1 terminal = 2 blocks (text.done produced nothing)
        assertEquals(2, results.size());
        assertInstanceOf(TextBlock.class, results.get(0).getContent().get(0));
    }

    @Test
    void multipleTextDeltasProduceMultipleBlocks() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Hello ", "msg_001"),
                        TestSdkFixtures.textDeltaEvent("World", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        assertEquals(3, results.size());
        assertEquals("Hello ", ((TextBlock) results.get(0).getContent().get(0)).getText());
        assertEquals("World", ((TextBlock) results.get(1).getContent().get(0)).getText());
    }

    // ── Block order and intermediate blocks ────────────────────────────────

    @Test
    void blocksEmittedInEventOrder() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("text", "msg_001"),
                        TestSdkFixtures.reasoningSummaryDeltaEvent("thinking", "rs_001"),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"a\"", "fc_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // Order: TextBlock, ThinkingBlock, ToolUseBlock, terminal
        assertEquals(4, results.size());
        assertInstanceOf(TextBlock.class, results.get(0).getContent().get(0));
        assertInstanceOf(ThinkingBlock.class, results.get(1).getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, results.get(2).getContent().get(0));

        // Terminal block has empty content
        assertTrue(terminalBlock(results).getContent().isEmpty());
    }

    @Test
    void intermediateBlockUsageIsNull() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Hi", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.usageResponse(100L, 50L, 0L, 0L)));
        List<ChatResponse> results = assemble(events);

        assertEquals(2, results.size());
        assertNull(results.get(0).getUsage(), "Intermediate block usage must be null");
        assertNotNull(results.get(1).getUsage(), "Terminal block usage must be non-null");
    }

    // ── Reasoning summary ──────────────────────────────────────────────────

    @Test
    void reasoningSummaryDeltaProducesThinkingBlock() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningSummaryDeltaEvent("thinking hard", "rs_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        assertEquals(2, results.size());
        assertInstanceOf(ThinkingBlock.class, results.get(0).getContent().get(0));
        assertEquals(
                "thinking hard",
                ((ThinkingBlock) results.get(0).getContent().get(0)).getThinking());
    }

    // ── Reasoning text delta separation ────────────────────────────────────

    @Test
    void reasoningTextDeltaWrittenToTerminalMetadata() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningTextDeltaEvent("raw reasoning", "rs_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // reasoning_text.delta produces no intermediate block
        assertEquals(1, results.size());
        // Reasoning text is on the terminal ThinkingBlock's metadata
        ChatResponse terminal = terminalBlock(results);
        ThinkingBlock terminalTb = (ThinkingBlock) terminal.getContent().get(0);
        assertEquals(
                "raw reasoning",
                terminalTb.getMetadata().get(OpenAIOfficialConstants.MD_REASONING_TEXT));
    }

    @Test
    void reasoningSummaryAndTextDeltaNotConcatenated() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningSummaryDeltaEvent("summary", "rs_001"),
                        TestSdkFixtures.reasoningTextDeltaEvent("raw", "rs_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // ThinkingBlock contains only "summary", not "raw"
        ThinkingBlock tb = (ThinkingBlock) results.get(0).getContent().get(0);
        assertEquals("summary", tb.getThinking());
        assertFalse(tb.getThinking().contains("raw"));

        // Terminal ThinkingBlock metadata has reasoning text
        ChatResponse terminal = terminalBlock(results);
        ThinkingBlock terminalTb = (ThinkingBlock) terminal.getContent().get(0);
        assertEquals(
                "raw", terminalTb.getMetadata().get(OpenAIOfficialConstants.MD_REASONING_TEXT));
    }

    @Test
    void reasoningDoneEventsProduceNoBlocks() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningSummaryDeltaEvent("summary", "rs_001"),
                        TestSdkFixtures.noopEvent(), // reasoning_summary_text.done
                        TestSdkFixtures.reasoningTextDeltaEvent("raw", "rs_001"),
                        TestSdkFixtures.noopEvent(), // reasoning_text.done
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // Only: 1 ThinkingBlock (summary delta) + 1 terminal = 2
        // done events produced no extra blocks
        assertEquals(2, results.size());
    }

    // ── Tool call streaming ───────────────────────────────────────────────

    @Test
    void toolArgsDeltaProducesFragmentToolUseBlock() {
        ResponseOutputItem item =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .arguments("{}")
                                .callId("call_123")
                                .name("get_weather")
                                .id("fc_001")
                                .build());
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.outputItemAddedEvent(item),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"city\"", "fc_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        assertEquals(2, results.size());
        ToolUseBlock block = (ToolUseBlock) results.get(0).getContent().get(0);
        assertEquals("call_123", block.getId());
        assertEquals("get_weather", block.getName());
        assertEquals("{\"city\"", block.getContent());
        assertTrue(block.getInput().isEmpty());
        assertEquals(ToolCallState.PENDING, block.getState());
    }

    @Test
    void toolArgsDoneDoesNotProduceCompleteBlock() {
        ResponseOutputItem item =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .arguments("{}")
                                .callId("call_123")
                                .name("get_weather")
                                .id("fc_001")
                                .build());
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.outputItemAddedEvent(item),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"city\"", "fc_001"),
                        TestSdkFixtures.functionCallArgsDoneEvent(
                                "{\"city\":\"SF\"}", "fc_001", "get_weather"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        // delta + terminal = 2 blocks (done produced nothing)
        assertEquals(2, results.size());
        assertInstanceOf(ToolUseBlock.class, results.get(0).getContent().get(0));
    }

    @Test
    void deltaThenDoneNoDuplicateToolArgs() {
        ResponseOutputItem item =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .arguments("{}")
                                .callId("call_123")
                                .name("get_weather")
                                .id("fc_001")
                                .build());
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.outputItemAddedEvent(item),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"city\"", "fc_001"),
                        TestSdkFixtures.functionCallArgsDoneEvent(
                                "{\"city\":\"SF\"}", "fc_001", "get_weather"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        ToolUseBlock block = (ToolUseBlock) results.get(0).getContent().get(0);
        assertEquals("{\"city\"", block.getContent(), "Fragment content should be delta, not done");
    }

    @Test
    void toolArgsDeltaNoItemAddedUsesPlaceholder() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"a\"", "fc_unknown"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        assertEquals(2, results.size());
        ToolUseBlock block = (ToolUseBlock) results.get(0).getContent().get(0);
        assertEquals("", block.getId(), "Fragment id should be empty when item not registered");
        assertEquals(
                "__fragment__",
                block.getName(),
                "Fragment name should be placeholder when item not registered");
    }

    @Test
    void multipleToolCallsRoutedByItemId() {
        ResponseOutputItem item1 =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .arguments("{}")
                                .callId("call_1")
                                .name("tool_a")
                                .id("fc_1")
                                .build());
        ResponseOutputItem item2 =
                ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .arguments("{}")
                                .callId("call_2")
                                .name("tool_b")
                                .id("fc_2")
                                .build());
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.outputItemAddedEvent(item1),
                        TestSdkFixtures.outputItemAddedEvent(item2),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"a\"", "fc_1"),
                        TestSdkFixtures.functionCallArgsDeltaEvent("{\"b\"", "fc_2"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);

        assertEquals(3, results.size());
        ToolUseBlock block1 = (ToolUseBlock) results.get(0).getContent().get(0);
        ToolUseBlock block2 = (ToolUseBlock) results.get(1).getContent().get(0);
        assertEquals("call_1", block1.getId());
        assertEquals("tool_a", block1.getName());
        assertEquals("call_2", block2.getId());
        assertEquals("tool_b", block2.getName());
    }

    // ── Terminal re-extraction ────────────────────────────────────────────

    @Test
    void terminalReextractsEncryptedContent() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningSummaryDeltaEvent("my summary", "rs_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.reasoningResponse(
                                        "my summary", "enc_data_123", null)));
        List<ChatResponse> results = assemble(events);

        ChatResponse terminal = terminalBlock(results);
        // Encrypted content is on the terminal ThinkingBlock's metadata
        ThinkingBlock terminalTb = (ThinkingBlock) terminal.getContent().get(0);
        assertEquals(
                "enc_data_123",
                terminalTb
                        .getMetadata()
                        .get(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT));
    }

    @Test
    void encryptedContentNotInThinkingBlock() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningSummaryDeltaEvent("summary", "rs_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.reasoningResponse("summary", "enc_data", null)));
        List<ChatResponse> results = assemble(events);

        ThinkingBlock tb = (ThinkingBlock) results.get(0).getContent().get(0);
        assertEquals("summary", tb.getThinking());
        assertFalse(tb.getThinking().contains("enc_data"));
    }

    @Test
    void terminalReextractsResponseMetadata() {
        List<ResponseStreamEvent> events =
                List.of(TestSdkFixtures.completedEvent(TestSdkFixtures.fullMetadataResponse()));
        List<ChatResponse> results = assemble(events);

        ChatResponse terminal = terminalBlock(results);
        assertEquals(
                "resp_test_123",
                terminal.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_ID));
        assertEquals(
                "incomplete",
                terminal.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_STATUS));
        assertEquals("incomplete", terminal.getFinishReason());
        assertEquals(
                "max_output_tokens",
                terminal.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_INCOMPLETE_REASON));
        assertEquals(
                "priority",
                terminal.getMetadata().get(OpenAIOfficialConstants.MD_RESPONSE_SERVICE_TIER));
    }

    @Test
    void cancelBeforeTerminalProducesNoTerminalBlock() {
        // Empty event list (simulates cancel before any terminal event)
        List<ResponseStreamEvent> events = List.of();
        List<ChatResponse> results = assemble(events);

        assertTrue(results.isEmpty(), "No terminal block when stream ends before terminal event");
    }

    // ── Refusal ───────────────────────────────────────────────────────────

    @Test
    void refusalInTerminalThrowsModelException() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Some text", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.refusalResponse("content policy violation")));
        ModelException ex = assertThrows(ModelException.class, () -> assemble(events));
        assertTrue(ex.getMessage().contains("content policy violation"));
        assertEquals("openai-official", ex.getProvider());
    }

    // ── Usage ─────────────────────────────────────────────────────────────

    @Test
    void terminalBlockCarriesUsage() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.textDeltaEvent("Hi", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.usageResponse(100L, 50L, 20L, 10L)));
        List<ChatResponse> results = assemble(events);

        ChatResponse terminal = terminalBlock(results);
        ChatUsage usage = terminal.getUsage();
        assertNotNull(usage);
        assertEquals(100, usage.getInputTokens());
        assertEquals(50, usage.getOutputTokens());
        assertEquals(20, usage.getCachedTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals(
                10, terminal.getMetadata().get(OpenAIOfficialConstants.MD_USAGE_REASONING_TOKENS));
    }

    @Test
    void usageAbsentYieldsNullInTerminal() {
        List<ResponseStreamEvent> events =
                List.of(TestSdkFixtures.completedEvent(TestSdkFixtures.textResponse("hello")));
        List<ChatResponse> results = assemble(events);

        ChatResponse terminal = terminalBlock(results);
        assertNull(terminal.getUsage());
    }

    // ── Failed and error events ───────────────────────────────────────────

    @Test
    void failedEventThrowsModelException() {
        List<ResponseStreamEvent> events =
                List.of(TestSdkFixtures.failedEvent(TestSdkFixtures.fullMetadataResponse()));
        assertThrows(ModelException.class, () -> assemble(events));
    }

    @Test
    void errorEventThrowsModelException() {
        List<ResponseStreamEvent> events =
                List.of(TestSdkFixtures.errorEvent("stream error occurred", "ERR_001"));
        ModelException ex = assertThrows(ModelException.class, () -> assemble(events));
        assertTrue(ex.getMessage().contains("stream error occurred"));
        assertTrue(ex.getMessage().contains("ERR_001"));
        assertEquals("openai-official", ex.getProvider());
    }

    // ── Stream close on completion ─────────────────────────────────────────

    @Test
    void streamClosedOnCompletion() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        StreamResponse<ResponseStreamEvent> stream = TestSdkFixtures.streamOf(events);
        ResponsesStreamingAssembler.assemble(stream, MODEL, Instant.now()).collectList().block();
        // If the stream wasn't closed, the test would hang on certain platforms
        // because the underlying Stream resource leaks. No assertion needed beyond
        // successful completion.
    }

    // ── No opt-in: no ThinkingBlock ──────────────────────────────────

    @Test
    void noOptinProducesNoThinkingBlock() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.reasoningTextDeltaEvent("raw reasoning", "rs_001"),
                        TestSdkFixtures.textDeltaEvent("Hello", "msg_001"),
                        TestSdkFixtures.completedEvent(
                                TestSdkFixtures.completedResponse(List.of())));
        List<ChatResponse> results = assemble(events);
        for (int i = 0; i < results.size() - 1; i++) {
            for (Object block : results.get(i).getContent()) {
                assertFalse(
                        block instanceof ThinkingBlock,
                        "No ThinkingBlock should be created without summary opt-in");
            }
        }
        // Terminal block carries reasoning metadata on a ThinkingBlock with no
        // visible thinking text (reasoning text is metadata-only, not content)
        ChatResponse terminal = terminalBlock(results);
        assertFalse(terminal.getContent().isEmpty());
        ThinkingBlock terminalTb = (ThinkingBlock) terminal.getContent().get(0);
        assertTrue(terminalTb.getThinking().isEmpty());
    }

    // ── Incomplete event ─────────────────────────────────────────────

    @Test
    void incompleteEventCarriesUsageAndFinishReason() {
        List<ResponseStreamEvent> events =
                List.of(
                        TestSdkFixtures.incompleteEvent(
                                TestSdkFixtures.response(
                                        List.of(TestSdkFixtures.messageItem("partial")),
                                        ResponseStatus.INCOMPLETE,
                                        TestSdkFixtures.usage(100L, 50L, 0L, 0L))));
        List<ChatResponse> results = assemble(events);
        ChatResponse terminal = terminalBlock(results);
        assertEquals("incomplete", terminal.getFinishReason());
        assertNotNull(terminal.getUsage());
        assertEquals(100, terminal.getUsage().getInputTokens());
        assertEquals(50, terminal.getUsage().getOutputTokens());
    }

    private static void assertInstanceOf(Class<?> expected, Object actual) {
        assertTrue(
                expected.isInstance(actual),
                "Expected "
                        + expected.getSimpleName()
                        + " but got "
                        + (actual != null ? actual.getClass().getSimpleName() : "null"));
    }
}
