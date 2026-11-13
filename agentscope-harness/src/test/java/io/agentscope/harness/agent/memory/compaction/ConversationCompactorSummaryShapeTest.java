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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/** Summary input and continuation contracts using the 2.0.x conversation/offload APIs. */
class ConversationCompactorSummaryShapeTest {

    @Test
    void formatMessagesForSummaryKeepsToolCallNameArgsAndResultPreview() {
        String formatted =
                ConversationCompactor.formatMessagesForSummary(
                        List.of(
                                message(MsgRole.USER, "please run the build"),
                                toolCall(
                                        "execute",
                                        Map.of("command", "mvn -T1 verify", "timeout", 600)),
                                toolResult(
                                        "execute",
                                        "HEAD_MARKER " + "x".repeat(2_000) + " TAIL_MARKER",
                                        ToolResultState.SUCCESS)));

        assertTrue(formatted.contains("[tool_call: execute {"));
        assertTrue(formatted.contains("\"command\":\"mvn -T1 verify\""));
        assertTrue(formatted.contains("\"timeout\":600"));
        assertTrue(formatted.contains("[tool_result: execute]"));
        assertTrue(formatted.contains("HEAD_MARKER"));
        assertTrue(formatted.contains("TAIL_MARKER"));
        assertTrue(formatted.contains("chars pruned"));
    }

    @ParameterizedTest
    @ValueSource(ints = {599, 600, 601})
    void formatMessagesForSummaryPreservesTextPreviewBoundary(int length) {
        String text = "H".repeat(300) + "T".repeat(length - 300);
        String formatted =
                ConversationCompactor.formatMessagesForSummary(
                        List.of(toolResult("execute", text, ToolResultState.SUCCESS)));
        String expected =
                length <= 600
                        ? text
                        : "H".repeat(300) + "\n\n...(1 chars pruned)...\n\n" + "T".repeat(300);
        assertEquals("Tool: [tool_result: execute] " + expected, formatted);
    }

    @Test
    void formatMessagesForSummaryPreviewsRawArgsAndKeepsErrorDetails() {
        String rawArgs =
                "{\"command\":\"HEAD_ARG "
                        + "x".repeat(1_000)
                        + " MIDDLE_ARG "
                        + "x".repeat(1_000)
                        + " TAIL_ARG\"}";
        String formatted =
                ConversationCompactor.formatMessagesForSummary(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                ToolUseBlock.builder()
                                                        .id("call-edit_file")
                                                        .name("edit_file")
                                                        .content(rawArgs)
                                                        .build())
                                        .build(),
                                toolResult(
                                        "edit_file", "string not found", ToolResultState.ERROR)));

        assertTrue(formatted.contains("HEAD_ARG"));
        assertTrue(formatted.contains("TAIL_ARG"));
        assertFalse(formatted.contains("MIDDLE_ARG"));
        assertTrue(formatted.contains("chars pruned"));
        assertTrue(formatted.contains("[tool_result: edit_file (error)] string not found"));
    }

    @Test
    void formatMessagesForSummaryPreservesShortRawArgsVerbatim() {
        String rawArgs =
                "{\"ratio\":0.123456789012345678901234567890,\"threshold\":1.2300,\"path\":\"src/A.java\"}";
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("raw")
                        .name("probe")
                        .input(Map.of("path", "ignored/structured.java"))
                        .content(rawArgs)
                        .build();
        String formatted =
                ConversationCompactor.formatMessagesForSummary(
                        List.of(Msg.builder().role(MsgRole.ASSISTANT).content(call).build()));
        assertTrue(formatted.contains(rawArgs), "Short raw arguments must retain exact numbers");
    }

    @Test
    void formatMessagesForSummaryKeepsPathBetweenLongArgumentValues() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("old_string", "OLD_HEAD " + "a".repeat(1200) + " OLD_TAIL");
        args.put("path", "src/main/java/example/OrderService.java");
        args.put("new_string", "NEW_HEAD " + "b".repeat(1200) + " NEW_TAIL");
        List<ToolUseBlock> calls =
                List.of(
                        ToolUseBlock.builder()
                                .id("structured")
                                .name("edit_file")
                                .input(args)
                                .build(),
                        ToolUseBlock.builder()
                                .id("raw")
                                .name("edit_file")
                                .content(JsonUtils.getJsonCodec().toJson(args))
                                .build());
        for (ToolUseBlock call : calls) {
            String formatted =
                    ConversationCompactor.formatMessagesForSummary(
                            List.of(Msg.builder().role(MsgRole.ASSISTANT).content(call).build()));
            assertTrue(formatted.contains("\"path\":\"src/main/java/example/OrderService.java\""));
            assertTrue(formatted.contains("OLD_HEAD"));
            assertTrue(formatted.contains("OLD_TAIL"));
            assertTrue(formatted.contains("NEW_HEAD"));
            assertTrue(formatted.contains("NEW_TAIL"));
            assertTrue(formatted.contains("chars pruned"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void summaryContinuesDirectlyAndReferencesOnlyActualOffload(boolean offload) {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session").build();
        MemoryFlushManager flushManager = mock(MemoryFlushManager.class);
        String path = "sessions/session.log.jsonl";
        when(flushManager.resolveOffloadPath(any(RuntimeContext.class), anyString(), anyString()))
                .thenReturn(path);
        ConversationCompactor compactor =
                new ConversationCompactor(new RecordingModel(), flushManager);
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .triggerTokens(0)
                        .keepMessages(2)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(offload)
                        .truncateArgs(null)
                        .prune(null)
                        .build();
        String pad = " earlier conversation detail".repeat(40);
        List<Msg> input =
                List.of(
                        message(MsgRole.USER, "M1" + pad),
                        message(MsgRole.USER, "M2" + pad),
                        message(MsgRole.USER, "TAIL_1"),
                        message(MsgRole.USER, "TAIL_2"));
        List<Msg> compacted =
                compactor
                        .compactIfNeeded(rc, input, config, "shape-agent", "session")
                        .block()
                        .orElseThrow();
        String text = compacted.get(0).getTextContent();
        assertTrue(text.contains("You are continuing a conversation that has been summarized."));
        assertTrue(text.contains("Treat it as established background"));
        assertTrue(text.contains("continue the work directly"));
        assertTrue(text.contains("do not recap"));
        assertTrue(text.contains("do not greet the user anew"));
        assertTrue(text.contains("do not mention the summarization"));
        assertTrue(text.contains("<summary>\nSUMMARY_TEXT\n</summary>"));
        assertEquals(offload, text.contains(path));
        assertFalse(text.contains("session_history"));
        assertEquals(
                input.subList(2, 4).stream().map(Msg::getId).toList(),
                compacted.subList(1, 3).stream().map(Msg::getId).toList());
        assertTrue(
                TokenCounterUtil.calculateToken(compacted)
                        < TokenCounterUtil.calculateToken(input));
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg toolCall(String name, Map<String, Object> input) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("call-" + name).name(name).input(input).build())
                .build();
    }

    private static Msg toolResult(String name, String output, ToolResultState state) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id("call-" + name)
                                .name(name)
                                .output(TextBlock.builder().text(output).build())
                                .state(state)
                                .build())
                .build();
    }

    private static final class RecordingModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("SUMMARY_TEXT").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }
    }
}
