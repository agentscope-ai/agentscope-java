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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests that aggregate tool-result pruning trims only the output text: the rebuilt block keeps
 * the identity fields (metadata markers, execution state, usage) that downstream middleware and
 * the HITL runtime branch on.
 */
class ConversationCompactorPruneTest {

    private final ConversationCompactor compactor =
            new ConversationCompactor(mock(Model.class), mock(MemoryFlushManager.class));

    @Test
    void pruneToolResults_usesConfiguredCounterForProtectionAndMinimum() {
        ConversationCompactor custom =
                new ConversationCompactor(
                        null, null, messages -> 1000L * messages.size(), message -> 1000L);
        Msg older =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "older",
                                        "read",
                                        TextBlock.builder().text("x".repeat(1000)).build()))
                        .build();
        Msg recent =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "recent",
                                        "read",
                                        TextBlock.builder().text("y".repeat(1000)).build()))
                        .build();

        List<Msg> result =
                custom.pruneToolResults(
                        List.of(older, recent), pruneConfig(100, 800, 1000, Set.of()));

        ToolResultBlock pruned = (ToolResultBlock) result.get(0).getContent().get(0);
        assertTrue(((TextBlock) pruned.getOutput().get(0)).getText().contains("chars pruned"));
        assertSame(recent, result.get(1));
    }

    /**
     * Verifies a pruned tool result keeps metadata markers (eviction, suspension), the execution
     * state, and the carrying message keeps its id/usage/timestamp.
     */
    @Test
    void pruneToolResults_preservesMetadataStateAndUsageWhenTrimming() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentscope.tool_result_evicted", Boolean.TRUE);
        metadata.put(ToolResultBlock.METADATA_SUSPENDED, Boolean.TRUE);
        metadata.put("custom_key", "custom_value");
        ToolExecutionDetails details =
                new ToolExecutionDetails("process", ToolExecutionDetails.Outcome.FAILED, 1, false);
        ToolResultBlock original =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("execute")
                        .output(TextBlock.builder().text("x".repeat(1_000)).build())
                        .metadata(metadata)
                        .state(ToolResultState.ERROR)
                        .executionDetails(details)
                        .build();
        Msg toolMsg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.TOOL)
                        .content(original)
                        .metadata(Map.of("msg_meta", "kept"))
                        .timestamp("2026-09-28T10:00:00Z")
                        .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                        .build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        ToolResultBlock pruned = (ToolResultBlock) result.get(0).getContent().get(0);
        String prunedText = ((TextBlock) pruned.getOutput().get(0)).getText();
        assertTrue(prunedText.contains("chars pruned"));
        assertTrue(prunedText.length() < 1_000);

        assertEquals(metadata, pruned.getMetadata());
        assertTrue(pruned.isSuspended());
        assertEquals(ToolResultState.ERROR, pruned.getState());
        assertEquals(details, pruned.getExecutionDetails());
        assertEquals("call-1", pruned.getId());
        assertEquals("execute", pruned.getName());

        Msg rebuilt = result.get(0);
        assertEquals("msg-1", rebuilt.getId());
        assertEquals(MsgRole.TOOL, rebuilt.getRole());
        assertEquals(Map.of("msg_meta", "kept"), rebuilt.getMetadata());
        assertEquals("2026-09-28T10:00:00Z", rebuilt.getTimestamp());
        assertEquals(10, rebuilt.getUsage().getInputTokens());
        assertEquals(5, rebuilt.getUsage().getOutputTokens());
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"SUCCESS", "ERROR"})
    void pruneToolResults_preservesRichBlockOrderWithOneTextBudget(ToolResultState state) {
        ImageBlock leading = image("AA==");
        AudioBlock audio =
                AudioBlock.builder().source(new Base64Source("audio/wav", "AQ==")).build();
        ImageBlock middle = image("Ag==");
        ImageBlock trailing = image("Aw==");
        List<ContentBlock> output =
                List.of(
                        leading,
                        text("H".repeat(30)),
                        text("A".repeat(30)),
                        audio,
                        text("M".repeat(100)),
                        middle,
                        text("T".repeat(30)),
                        text("Z".repeat(30)),
                        trailing);
        ToolExecutionDetails details =
                new ToolExecutionDetails("process", ToolExecutionDetails.Outcome.PARTIAL, 7, true);
        ToolResultBlock original =
                ToolResultBlock.builder()
                        .id("rich-call")
                        .name("execute")
                        .output(output)
                        .metadata(Map.of("source", "rich-tool"))
                        .state(state)
                        .executionDetails(details)
                        .build();
        Msg message = Msg.builder().role(MsgRole.TOOL).content(original).build();

        ToolResultBlock pruned =
                compactor
                        .pruneToolResults(List.of(message), pruneConfig(100, 1, 0, Set.of()))
                        .get(0)
                        .getContentBlocks(ToolResultBlock.class)
                        .get(0);

        List<ContentBlock> preview = pruned.getOutput();
        assertEquals(8, preview.size(), "Fully removed text must not leave an empty block");
        assertSame(leading, preview.get(0));
        assertEquals("H".repeat(30), ((TextBlock) preview.get(1)).getText());
        assertEquals(
                "A".repeat(20) + "\n\n...(120 chars pruned)...\n\n",
                ((TextBlock) preview.get(2)).getText());
        assertSame(audio, preview.get(3));
        assertSame(middle, preview.get(4));
        assertEquals("T".repeat(20), ((TextBlock) preview.get(5)).getText());
        assertEquals("Z".repeat(30), ((TextBlock) preview.get(6)).getText());
        assertSame(trailing, preview.get(7));
        assertEquals(original.getId(), pruned.getId());
        assertEquals(original.getName(), pruned.getName());
        assertEquals(original.getMetadata(), pruned.getMetadata());
        assertEquals(state, pruned.getState());
        assertEquals(details, pruned.getExecutionDetails());
        assertEquals(output, original.getOutput(), "Pruning must not mutate the source result");
        assertSame(original, message.getContent().get(0));
    }

    @Test
    void pruneToolResults_leavesMediaOnlyAndWithinBudgetResultsUntouched() {
        ImageBlock image = image("AA==");
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.of("media", "execute", image),
                                        ToolResultBlock.of(
                                                "blank",
                                                "execute",
                                                List.of(text(" \t".repeat(100)), image)),
                                        ToolResultBlock.of(
                                                "small",
                                                "execute",
                                                List.of(
                                                        text("a".repeat(50)),
                                                        image,
                                                        text("b".repeat(50)))),
                                        ToolResultBlock.of(
                                                "mixed-whitespace",
                                                "execute",
                                                List.of(
                                                        text(" ".repeat(50)),
                                                        image,
                                                        text("b".repeat(50)))))
                                .build());

        assertSame(
                messages, compactor.pruneToolResults(messages, pruneConfig(100, 1, 0, Set.of())));
    }

    /** Verifies excluded tools are never pruned even when oversized and outside protection. */
    @Test
    void pruneToolResults_skipsExcludedTools() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-2",
                                        "read_file",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                        .build();
        List<Msg> messages = List.of(toolMsg);

        List<Msg> result =
                compactor.pruneToolResults(messages, pruneConfig(100, 1, 0, Set.of("read_file")));

        assertSame(messages, result);
    }

    /** Verifies a conversation whose prunable total is below the threshold is returned unchanged. */
    @Test
    void pruneToolResults_returnsOriginalWhenBelowMinimum() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-3",
                                        "execute",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                        .build();
        List<Msg> messages = List.of(toolMsg);

        List<Msg> result =
                compactor.pruneToolResults(
                        messages, pruneConfig(100, Integer.MAX_VALUE, 0, Set.of()));

        assertSame(messages, result);
    }

    /** Verifies a block without metadata or explicit state prunes without failing. */
    @Test
    void pruneToolResults_handlesBlockWithoutMetadataOrState() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-4",
                                        "execute",
                                        TextBlock.builder().text("y".repeat(500)).build()))
                        .build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        ToolResultBlock pruned = (ToolResultBlock) result.get(0).getContent().get(0);
        assertTrue(pruned.getMetadata().isEmpty());
        assertEquals(ToolResultState.RUNNING, pruned.getState());
        assertTrue(((TextBlock) pruned.getOutput().get(0)).getText().contains("chars pruned"));
    }

    /**
     * Verifies a message carrying several tool results is rebuilt with only the oversized block
     * replaced; untouched blocks keep their original reference.
     */
    @Test
    void pruneToolResults_multiBlockMessagePrunesOnlyOversizedBlock() {
        ToolResultBlock small =
                ToolResultBlock.builder()
                        .id("call-small")
                        .name("execute")
                        .output(TextBlock.builder().text("short output").build())
                        .metadata(Map.of("small_meta", "v"))
                        .state(ToolResultState.SUCCESS)
                        .build();
        ToolResultBlock large =
                ToolResultBlock.builder()
                        .id("call-large")
                        .name("execute")
                        .output(TextBlock.builder().text("x".repeat(1_000)).build())
                        .metadata(Map.of("large_meta", "v"))
                        .state(ToolResultState.ERROR)
                        .build();
        Msg toolMsg =
                Msg.builder().id("msg-5").role(MsgRole.TOOL).content(List.of(small, large)).build();

        List<Msg> result =
                compactor.pruneToolResults(List.of(toolMsg), pruneConfig(100, 1, 0, Set.of()));

        assertEquals(1, result.size());
        List<ContentBlock> blocks = result.get(0).getContent();
        assertEquals(2, blocks.size());
        assertSame(small, blocks.get(0));
        ToolResultBlock prunedLarge = (ToolResultBlock) blocks.get(1);
        assertTrue(((TextBlock) prunedLarge.getOutput().get(0)).getText().contains("chars pruned"));
        assertEquals(Map.of("large_meta", "v"), prunedLarge.getMetadata());
        assertEquals(ToolResultState.ERROR, prunedLarge.getState());
        assertEquals("call-large", prunedLarge.getId());
        assertEquals("msg-5", result.get(0).getId());
    }

    @Test
    void compactIfNeeded_protectsTheSameRecentOutputsWhenResultsAreBatched() {
        List<ContentBlock> blocks = largeResults();
        List<Msg> separate =
                blocks.stream()
                        .map(b -> Msg.builder().role(MsgRole.TOOL).content(b).build())
                        .toList();
        List<Msg> batched = List.of(Msg.builder().role(MsgRole.TOOL).content(blocks).build());
        CompactionConfig config = compactionConfig(pruneConfig(100, 1, 700, Set.of()));

        List<ContentBlock> separateOutput =
                compact(separate, config).stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .toList();
        List<ContentBlock> batchedOutput = compact(batched, config).get(0).getContent();

        assertEquals(6, batchedOutput.size());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(
                    ((TextBlock) ((ToolResultBlock) separateOutput.get(i)).getOutput().get(0))
                            .getText(),
                    ((TextBlock) ((ToolResultBlock) batchedOutput.get(i)).getOutput().get(0))
                            .getText(),
                    "result " + i);
        }
        assertSame(blocks.get(4), batchedOutput.get(4));
        assertSame(blocks.get(5), batchedOutput.get(5));
    }

    @Test
    void compactIfNeeded_doesNotMultiplyPrunableTokensByResultsPerMessage() {
        List<Msg> messages =
                List.of(Msg.builder().role(MsgRole.TOOL).content(largeResults()).build());
        CompactionConfig config = compactionConfig(pruneConfig(100, 3_000, 0, Set.of()));

        // Six 1,000-character outputs are about 2,400 tokens, below the threshold.
        assertTrue(
                compactor
                        .compactIfNeeded(null, messages, config, "agent", "session")
                        .block()
                        .isEmpty());
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static ImageBlock image(String data) {
        return ImageBlock.builder().source(new Base64Source("image/png", data)).build();
    }

    private static List<ContentBlock> largeResults() {
        return IntStream.range(0, 6)
                .<ContentBlock>mapToObj(
                        i ->
                                ToolResultBlock.of(
                                        "call-" + i,
                                        "execute",
                                        TextBlock.builder().text("x".repeat(1_000)).build()))
                .toList();
    }

    private static CompactionConfig compactionConfig(CompactionConfig.PruneConfig prune) {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(Integer.MAX_VALUE)
                .prune(prune)
                .build();
    }

    private List<Msg> compact(List<Msg> messages, CompactionConfig config) {
        return compactor
                .compactIfNeeded(null, messages, config, "agent", "session")
                .block()
                .orElse(messages);
    }

    /** Creates a prune config: maxOutputChars, minimumTokens, protectTokens, excludedTools. */
    private static CompactionConfig.PruneConfig pruneConfig(
            int maxOutputChars, int minimumTokens, int protectTokens, Set<String> excludedTools) {
        return CompactionConfig.PruneConfig.builder()
                .maxOutputChars(maxOutputChars)
                .minimumTokens(minimumTokens)
                .protectTokens(protectTokens)
                .excludedTools(excludedTools)
                .build();
    }
}
