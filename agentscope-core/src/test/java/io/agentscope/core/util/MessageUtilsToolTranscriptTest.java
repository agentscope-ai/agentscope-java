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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageUtilsToolTranscriptTest {

    @Test
    void returnsEmptyTranscriptForNullOrEmptyInput() {
        assertTrue(MessageUtils.normalizeToolCallResults(null).isEmpty());
        assertTrue(MessageUtils.normalizeToolCallResults(List.of()).isEmpty());
    }

    @Test
    void skipsNullMessagesWithoutDroppingOtherTranscriptEntries() {
        Msg userMessage = Msg.builder().role(MsgRole.USER).textContent("continue").build();

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(Arrays.asList(null, userMessage));

        assertEquals(List.of(userMessage), normalized);
    }

    @Test
    void normalizesDeferredToolResultNextToOwningToolUse() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call-1").name("search").input(Map.of()).build();
        ToolResultBlock result =
                ToolResultBlock.of("call-1", "search", TextBlock.builder().text("found").build());
        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(
                                Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build(),
                                Msg.builder().role(MsgRole.USER).textContent("continue").build(),
                                Msg.builder().role(MsgRole.TOOL).content(result).build()));

        assertEquals(3, normalized.size());
        assertEquals(MsgRole.ASSISTANT, normalized.get(0).getRole());
        assertEquals(MsgRole.TOOL, normalized.get(1).getRole());
        assertEquals(
                "call-1", normalized.get(1).getFirstContentBlock(ToolResultBlock.class).getId());
        assertEquals(MsgRole.USER, normalized.get(2).getRole());
    }

    @Test
    void addsTerminalErrorResultForToolUseWithoutAnyResult() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call-2").name("search").input(Map.of()).build();

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build()));

        assertEquals(2, normalized.size());
        ToolResultBlock result = normalized.get(1).getFirstContentBlock(ToolResultBlock.class);
        assertEquals("call-2", result.getId());
        assertEquals("search", result.getName());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(block -> block.getText().contains("interrupted")));
    }

    @Test
    void movesInlineToolResultOutOfAssistantMessage() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call-3").name("search").input(Map.of()).build();
        ToolResultBlock result =
                ToolResultBlock.of("call-3", "search", TextBlock.builder().text("pending").build());

        Msg source =
                Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolUse, result)).build();
        List<Msg> normalized = MessageUtils.normalizeToolCallResults(List.of(source));

        assertEquals(2, normalized.size());
        assertTrue(normalized.get(0).getContentBlocks(ToolResultBlock.class).isEmpty());
        assertEquals(1, source.getContentBlocks(ToolResultBlock.class).size());
        assertEquals(
                "call-3", normalized.get(1).getFirstContentBlock(ToolResultBlock.class).getId());
    }

    @Test
    void preservesToolResultOrderAndStateForMultipleToolUses() {
        ToolUseBlock firstUse =
                ToolUseBlock.builder().id("call-4a").name("search").input(Map.of()).build();
        ToolUseBlock secondUse =
                ToolUseBlock.builder().id("call-4b").name("calculate").input(Map.of()).build();
        ToolResultBlock firstResult =
                ToolResultBlock.of("call-4a", "search", TextBlock.builder().text("failed").build())
                        .withState(ToolResultState.ERROR);
        ToolResultBlock secondResult =
                ToolResultBlock.of(
                                "call-4b", "calculate", TextBlock.builder().text("denied").build())
                        .withState(ToolResultState.DENIED);
        Msg source =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(firstUse, firstResult, secondUse))
                        .build();

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(
                                source,
                                Msg.builder().role(MsgRole.USER).textContent("continue").build(),
                                Msg.builder().role(MsgRole.TOOL).content(secondResult).build()));

        assertEquals(4, normalized.size());
        assertToolResult(normalized.get(1), "call-4a", ToolResultState.ERROR);
        assertToolResult(normalized.get(2), "call-4b", ToolResultState.DENIED);
        assertEquals(MsgRole.USER, normalized.get(3).getRole());
        assertEquals(1, source.getContentBlocks(ToolResultBlock.class).size());
    }

    @Test
    void retainsUnrelatedToolResultsAfterRelocatingMatchedResults() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call-5").name("search").input(Map.of()).build();
        ToolResultBlock matchedResult =
                ToolResultBlock.of("call-5", "search", TextBlock.builder().text("found").build())
                        .withState(ToolResultState.SUCCESS);
        ToolResultBlock unrelatedResult =
                ToolResultBlock.of("call-6", "calculate", TextBlock.builder().text("42").build())
                        .withState(ToolResultState.DENIED);

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(
                                Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build(),
                                Msg.builder()
                                        .role(MsgRole.TOOL)
                                        .content(
                                                List.of(
                                                        matchedResult,
                                                        TextBlock.builder()
                                                                .text("metadata")
                                                                .build(),
                                                        unrelatedResult))
                                        .build()));

        assertEquals(3, normalized.size());
        assertToolResult(normalized.get(1), "call-5", ToolResultState.SUCCESS);
        assertToolResult(normalized.get(2), "call-6", ToolResultState.DENIED);
        assertEquals("metadata", normalized.get(2).getFirstContentBlock(TextBlock.class).getText());
    }

    @Test
    void preservesToolBlocksWithoutCallIds() {
        ToolUseBlock toolUse = ToolUseBlock.builder().name("search").input(Map.of()).build();
        ToolResultBlock result =
                ToolResultBlock.of(null, "search", TextBlock.builder().text("found").build());

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(
                                Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build(),
                                Msg.builder().role(MsgRole.TOOL).content(result).build()));

        assertEquals(2, normalized.size());
        assertEquals(MsgRole.ASSISTANT, normalized.get(0).getRole());
        assertEquals(MsgRole.TOOL, normalized.get(1).getRole());
        assertEquals(1, normalized.get(1).getContentBlocks(ToolResultBlock.class).size());
    }

    @Test
    void keepsInlineToolResultsThatBelongToAnotherCall() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call-7").name("search").input(Map.of()).build();
        ToolResultBlock unrelatedResult =
                ToolResultBlock.of("call-8", "calculate", TextBlock.builder().text("42").build());

        List<Msg> normalized =
                MessageUtils.normalizeToolCallResults(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(List.of(toolUse, unrelatedResult))
                                        .build()));

        assertEquals(2, normalized.size());
        assertEquals(1, normalized.get(0).getContentBlocks(ToolResultBlock.class).size());
        assertToolResult(normalized.get(1), "call-7", ToolResultState.ERROR);
    }

    private static void assertToolResult(Msg message, String id, ToolResultState state) {
        assertEquals(MsgRole.TOOL, message.getRole());
        List<ToolResultBlock> results = message.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, results.size());
        assertEquals(id, results.get(0).getId());
        assertEquals(state, results.get(0).getState());
    }
}
