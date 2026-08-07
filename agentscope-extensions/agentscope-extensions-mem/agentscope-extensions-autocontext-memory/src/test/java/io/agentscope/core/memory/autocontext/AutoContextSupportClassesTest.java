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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AutoContextSupportClassesTest {

    @Test
    void promptProviderUsesCustomPromptsAndFallsBackForBlankValues() {
        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt("tool")
                        .previousRoundSummaryPrompt("summary")
                        .currentRoundLargeMessagePrompt("large")
                        .currentRoundCompressPrompt("current")
                        .build();

        assertEquals("tool", PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt));
        assertEquals("summary", PromptProvider.getPreviousRoundSummaryPrompt(customPrompt));
        assertEquals("large", PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt));
        assertEquals("current", PromptProvider.getCurrentRoundCompressPrompt(customPrompt));

        PromptConfig blankPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt(" ")
                        .previousRoundSummaryPrompt("")
                        .currentRoundLargeMessagePrompt(" ")
                        .currentRoundCompressPrompt("")
                        .build();

        assertEquals(
                Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT,
                PromptProvider.getPreviousRoundToolCompressPrompt(blankPrompt));
        assertEquals(
                Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT,
                PromptProvider.getPreviousRoundSummaryPrompt(blankPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT,
                PromptProvider.getCurrentRoundLargeMessagePrompt(blankPrompt));
        assertEquals(
                Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT,
                PromptProvider.getCurrentRoundCompressPrompt(blankPrompt));
    }

    @Test
    void compressionEventAccessorsReflectMetadataAndDefensiveCopies() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tokenBefore", 12);
        metadata.put("tokenAfter", 5);
        metadata.put("inputToken", 9);
        metadata.put("outputToken", 3);
        metadata.put("time", 1.5d);
        CompressionEvent event =
                new CompressionEvent("type", 10L, 2, "prev", "next", "compressed", metadata);

        metadata.put("tokenBefore", 100);

        assertEquals("type", event.getEventType());
        assertEquals(10L, event.getTimestamp());
        assertEquals(2, event.getCompressedMessageCount());
        assertEquals("prev", event.getPreviousMessageId());
        assertEquals("next", event.getNextMessageId());
        assertEquals("compressed", event.getCompressedMessageId());
        assertEquals(12, event.getTokenBefore());
        assertEquals(5, event.getTokenAfter());
        assertEquals(7, event.getTokenReduction());
        assertEquals(9, event.getCompressInputToken());
        assertEquals(3, event.getCompressOutputToken());

        CompressionEvent mutable = new CompressionEvent();
        mutable.setEventType("updated");
        mutable.setTimestamp(20L);
        mutable.setCompressedMessageCount(4);
        mutable.setPreviousMessageId("p");
        mutable.setNextMessageId("n");
        mutable.setCompressedMessageId("c");
        mutable.setMetadata(null);

        assertEquals("updated", mutable.getEventType());
        assertEquals(20L, mutable.getTimestamp());
        assertEquals(4, mutable.getCompressedMessageCount());
        assertEquals("p", mutable.getPreviousMessageId());
        assertEquals("n", mutable.getNextMessageId());
        assertEquals("c", mutable.getCompressedMessageId());
        assertTrue(mutable.getMetadata().isEmpty());
    }

    @Test
    void autoContextStateCopiesCollectionsAndNormalizesNulls() {
        List<Msg> working = new ArrayList<>(List.of(AutoContextTestSupport.userMessage("work")));
        List<Msg> original =
                new ArrayList<>(List.of(AutoContextTestSupport.assistantMessage("original")));
        List<CompressionEvent> events =
                new ArrayList<>(
                        List.of(new CompressionEvent("type", 1L, 1, null, null, null, null)));
        Map<String, List<Msg>> offload = new HashMap<>();
        offload.put(
                "uuid", new ArrayList<>(List.of(AutoContextTestSupport.userMessage("offload"))));

        AutoContextState state = new AutoContextState();
        state.setWorkingMessages(working);
        state.setOriginalMessages(original);
        state.setCompressionEvents(events);
        state.setOffloadContext(offload);

        working.clear();
        original.clear();
        events.clear();
        offload.clear();

        assertEquals(1, state.getWorkingMessages().size());
        assertEquals(1, state.getOriginalMessages().size());
        assertEquals(1, state.getCompressionEvents().size());
        assertEquals(1, state.getOffloadContext().size());

        state.setWorkingMessages(null);
        state.setOriginalMessages(null);
        state.setCompressionEvents(null);
        state.setOffloadContext(null);

        assertTrue(state.getWorkingMessages().isEmpty());
        assertTrue(state.getOriginalMessages().isEmpty());
        assertTrue(state.getCompressionEvents().isEmpty());
        assertTrue(state.getOffloadContext().isEmpty());
    }

    @Test
    void tokenCounterUsesMinimumOfOneAndCharHeuristic() {
        assertEquals(1, TokenCounterUtil.calculateToken(List.of()));
        assertEquals(
                2,
                TokenCounterUtil.calculateToken(
                        List.of(AutoContextTestSupport.userMessage("12345678"))));
    }

    @Test
    void onEventReturnsUnhandledEventUnchanged() {
        AutoContextHook hook = new AutoContextHook();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("post-call")
                        .sysPrompt("system")
                        .model(AutoContextTestSupport.noopModel())
                        .build();
        Msg finalMessage =
                Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .name("assistant")
                        .textContent("done")
                        .usage(ChatUsage.builder().inputTokens(1).outputTokens(2).time(0.1).build())
                        .build();
        PostCallEvent event = new PostCallEvent(agent, finalMessage);

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        assertSame(finalMessage, event.getFinalMessage());
    }
}
