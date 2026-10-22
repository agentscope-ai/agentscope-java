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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
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
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Shape contract for the summarization leg: the summary input must carry tool-call traces
 * (name + argument preview + result identity), the default prompt must encode the extraction
 * disciplines, and the summary message must read as established background to continue from.
 */
class ConversationCompactorSummaryShapeTest {

    // -------------------------------------------------------------------------
    //  Summarization input: real message shape
    // -------------------------------------------------------------------------

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
        assertTrue(formatted.contains("HEAD_MARKER"), "result preview must keep the head");
        assertTrue(formatted.contains("TAIL_MARKER"), "result preview must keep the tail");
        assertTrue(formatted.contains("chars pruned"), "elision must be marked");
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
        assertTrue(
                formatted.contains("[tool_result: edit_file (error)] string not found"),
                "error results must be identifiable so error strings survive compaction");
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

        assertTrue(formatted.contains(rawArgs), "short raw arguments must retain exact numbers");
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

            assertTrue(
                    formatted.contains("\"path\":\"src/main/java/example/OrderService.java\""),
                    "short arguments must survive between long values: " + call.getId());
            assertTrue(formatted.contains("OLD_HEAD"));
            assertTrue(formatted.contains("OLD_TAIL"));
            assertTrue(formatted.contains("NEW_HEAD"));
            assertTrue(formatted.contains("NEW_TAIL"));
            assertTrue(formatted.contains("chars pruned"));
        }
    }

    // -------------------------------------------------------------------------
    //  Default prompt: structure + disciplines
    // -------------------------------------------------------------------------

    @Test
    void defaultSummaryPromptCarriesSectionsAndDisciplines() {
        String prompt = CompactionConfig.DEFAULT_SUMMARY_PROMPT;
        for (String section :
                List.of(
                        "## SESSION INTENT",
                        "## USER PREFERENCES AND CORRECTIONS",
                        "## SUMMARY",
                        "## ARTIFACTS",
                        "## COMMANDS AND ERRORS",
                        "## NEXT STEPS")) {
            assertTrue(prompt.contains(section), "missing section: " + section);
        }
        assertTrue(prompt.contains("Preserve verbatim"), "verbatim preservation rule");
        assertTrue(prompt.contains("merge it with the new material"), "re-compaction merge rule");
        assertTrue(prompt.contains("Do NOT mention this summarization"), "secrecy rule");
        assertTrue(prompt.contains("{messages}"), "custom-prompt placeholder contract");
    }

    // -------------------------------------------------------------------------
    //  Summary message: continuation wording
    // -------------------------------------------------------------------------

    @Test
    void summaryMessageContinuesFromEstablishedBackgroundWithoutNativeSession() {
        List<Msg> compacted = compactWith(mock(RuntimeContext.class));

        String text = text(compacted.get(0));
        assertTrue(
                text.contains("You are continuing a conversation that has been summarized."),
                "preamble, got: " + text);
        assertTrue(text.contains("Treat it as established background"));
        assertTrue(text.contains("do not mention the summarization"));
        assertFalse(text.contains("session_history"), "no durable history must be claimed");
        assertTrue(text.contains("<summary>"), "summary payload present");
    }

    @Test
    void summaryMessageKeepsHistoryReferenceAndContinuationWithNativeSession() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext rc = RuntimeContext.builder().userId("user").sessionId("session").build();
        var log = store.open(new SessionKey("user", "shape-agent", "session"), rc);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        rc.put(SessionRecorder.CONTEXT_KEY, recorder);
        try {
            List<Msg> compacted = compactWith(rc);

            String text = text(compacted.get(0));
            assertTrue(text.contains("session_history(agentId="), "retrieval pointer present");
            assertTrue(text.contains("Treat it as established background"));
        } finally {
            recorder.close();
        }
    }

    // -------------------------------------------------------------------------
    //  Fixtures
    // -------------------------------------------------------------------------

    /** Runs one compaction pass that always triggers and keeps a two-message tail. */
    private static List<Msg> compactWith(RuntimeContext rc) {
        RecordingModel model = new RecordingModel();
        ConversationCompactor compactor =
                new ConversationCompactor(model, new MemoryFlushManager(null, null));
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .triggerTokens(0)
                        .keepMessages(2)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(null)
                        .build();
        return compactor
                .compactIfNeeded(
                        rc,
                        List.of(
                                message(MsgRole.USER, "M1"),
                                message(MsgRole.USER, "M2"),
                                message(MsgRole.USER, "TAIL_1"),
                                message(MsgRole.USER, "TAIL_2")),
                        config,
                        "shape-agent",
                        "session")
                .block()
                .orElseThrow();
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

    private static String text(Msg message) {
        return ((TextBlock) message.getContent().get(0)).getText();
    }

    private static final class RecordingModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            TextBlock.builder().text("SUMMARY_TEXT").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }
    }
}
