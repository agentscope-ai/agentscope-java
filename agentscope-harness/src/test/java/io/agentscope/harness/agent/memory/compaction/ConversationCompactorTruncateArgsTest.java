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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests that argument truncation preserves message identity and tool-call context. */
class ConversationCompactorTruncateArgsTest {

    private final ConversationCompactor compactor = new ConversationCompactor(null, null);
    private final CompactionConfig.TruncateArgsConfig config =
            CompactionConfig.TruncateArgsConfig.builder()
                    .triggerMessages(1)
                    .triggerTokens(0)
                    .keepMessages(1)
                    .maxArgLength(40)
                    .build();

    @Test
    void preservesMessageIdentityMetadataTimestampAndUsage() {
        Msg original = assistant(tool("x".repeat(100)));
        Msg truncated = truncate(original);

        assertAll(
                () -> assertEquals(original.getId(), truncated.getId(), "message id"),
                () -> assertEquals(original.getTimestamp(), truncated.getTimestamp(), "timestamp"),
                () -> assertEquals(original.getMetadata(), truncated.getMetadata(), "metadata"),
                () -> assertSame(original.getUsage(), truncated.getUsage(), "usage"));
    }

    @ParameterizedTest
    @EnumSource(ToolCallState.class)
    void preservesToolProviderMetadataAndExecutionState(ToolCallState state) {
        ToolUseBlock original = tool("x".repeat(100)).withState(state);
        ToolUseBlock truncated = toolBlock(truncate(assistant(original)));

        assertAll(
                () ->
                        assertEquals(
                                original.getMetadata(), truncated.getMetadata(), "tool metadata"),
                () -> assertEquals(original.getState(), truncated.getState(), "tool state"),
                () -> assertEquals(original.getId(), truncated.getId(), "tool id"),
                () -> assertEquals(original.getName(), truncated.getName(), "tool name"));
    }

    @Test
    void truncatesOnlyLongArgumentsWithoutMutatingOriginalOrSiblingBlocks() {
        ToolUseBlock original = tool("x".repeat(100));
        Msg originalMessage = assistant(original);
        Msg truncatedMessage = truncate(originalMessage);
        ToolUseBlock truncated = toolBlock(truncatedMessage);

        assertEquals("x".repeat(20) + config.getTruncationText(), truncated.getInput().get("code"));
        assertEquals("short", truncated.getInput().get("label"));
        assertEquals(7, truncated.getInput().get("count"));
        assertEquals("x".repeat(100), original.getInput().get("code"));
        assertSame(originalMessage.getContent().get(1), truncatedMessage.getContent().get(1));
        assertEquals("original-message", originalMessage.getId());
    }

    @Test
    void preservesRecentKeepWindow() {
        Msg old = assistant(tool("x".repeat(100)));
        Msg recent = assistant(tool("y".repeat(100)));
        List<Msg> result = compactor.truncateArgs(List.of(old, recent), config);

        assertSame(recent, result.get(1));
        assertEquals("y".repeat(100), toolBlock(result.get(1)).getInput().get("code"));
        assertFalse(toolBlock(result.get(0)).getInput().equals(toolBlock(old).getInput()));
    }

    @Test
    void returnsOriginalWhenNoArgumentNeedsTruncation() {
        Msg original = assistant(tool("short"));
        assertSame(original, truncate(original));
    }

    @Test
    void skipsNonAssistantMessages() {
        Msg original = Msg.builder().role(MsgRole.USER).textContent("x".repeat(100)).build();
        assertSame(original, truncate(original));
    }

    @Test
    void skipsProviderServerToolCalls() {
        ToolUseBlock original =
                ToolUseBlock.builder()
                        .id("call-server")
                        .name("code_interpreter")
                        .input(Map.of("code", "x".repeat(100)))
                        .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                        .state(ToolCallState.FINISHED)
                        .build();
        Msg originalMessage = assistant(original);

        assertSame(originalMessage, truncate(originalMessage));
        assertSame(original, toolBlock(originalMessage));
    }

    @Test
    void rejectsMaxArgLengthBelowTwentyCharacters() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                CompactionConfig.TruncateArgsConfig.builder()
                                        .maxArgLength(19)
                                        .build());

        assertEquals("maxArgLength must be at least 20 characters", error.getMessage());
    }

    @Test
    void effectiveFormatterArgumentsMatchTruncatedInput() throws Exception {
        ToolUseBlock truncated = toolBlock(truncate(assistant(tool("x".repeat(100)))));
        assertNull(truncated.getContent(), "Old raw arguments must not override truncated input");
        String resolved = JsonUtils.resolveToolCallArgsJson(truncated);

        assertEquals(truncated.getInput(), new ObjectMapper().readValue(resolved, Map.class));
        assertFalse(resolved.contains("x".repeat(100)));
    }

    private Msg truncate(Msg original) {
        Msg tail = Msg.builder().role(MsgRole.USER).textContent("keep this recent turn").build();
        return compactor.truncateArgs(List.of(original, tail), config).get(0);
    }

    private static Msg assistant(ToolUseBlock block) {
        return Msg.builder()
                .id("original-message")
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .timestamp("2026-10-01 12:00:00.000")
                .metadata(Map.of("applicationMarker", "preserve-me"))
                .usage(new ChatUsage(100, 20, 0.25))
                .content(List.of(block, TextBlock.builder().text("unchanged sibling").build()))
                .build();
    }

    private static ToolUseBlock tool(String value) {
        Map<String, Object> input = Map.of("code", value, "label", "short", "count", 7);
        return ToolUseBlock.builder()
                .id("call-original")
                .name("execute")
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .metadata(Map.of(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "AQID"))
                .state(ToolCallState.FINISHED)
                .build();
    }

    private static ToolUseBlock toolBlock(Msg msg) {
        return (ToolUseBlock) msg.getContent().get(0);
    }
}
