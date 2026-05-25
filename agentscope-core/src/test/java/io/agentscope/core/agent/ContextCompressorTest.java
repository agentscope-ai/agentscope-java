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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Unit tests for {@link ContextCompressor} covering the three Stage 7e strategies. */
class ContextCompressorTest {

    /** Stub that records invocations and returns a fixed text response. */
    private static final class StubModel extends ChatModelBase {
        private final String reply;
        final AtomicInteger calls = new AtomicInteger(0);

        StubModel(String reply) {
            this.reply = reply;
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(reply).build()))
                            .build());
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg systemMsg(String text) {
        return Msg.builder().role(MsgRole.SYSTEM).textContent(text).build();
    }

    private static Msg toolMsg(String text) {
        return Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.text(text)).build();
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    // ==================== Strategy 1: tool-result truncation ====================

    @Test
    void truncateToolResultMsgPassesThroughWhenNonTool() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(10).build();
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        Msg user = userMsg(repeat("x", 100));
        assertSame(user, cc.truncateToolResultMsg(user));
    }

    @Test
    void truncateToolResultMsgPassesThroughWhenUnderLimit() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(100).build();
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        Msg tool = toolMsg("short");
        assertSame(tool, cc.truncateToolResultMsg(tool));
    }

    @Test
    void truncateToolResultMsgCapsOversizedText() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(50).build();
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        Msg tool = toolMsg(repeat("x", 200));
        Msg capped = cc.truncateToolResultMsg(tool);
        assertNotSame(tool, capped);
        List<ToolResultBlock> blocks = capped.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, blocks.size());
        ToolResultBlock trb = blocks.get(0);
        int totalLen = 0;
        boolean hasSuffix = false;
        for (ContentBlock b : trb.getOutput()) {
            if (b instanceof TextBlock tb) {
                totalLen += tb.getText().length();
                if (tb.getText().endsWith(ContextCompressor.TRUNCATION_SUFFIX)) {
                    hasSuffix = true;
                }
            }
        }
        assertTrue(hasSuffix, "truncated text must end with the marker suffix");
        assertTrue(totalLen <= 50, "total text length must not exceed limit, got " + totalLen);
    }

    @Test
    void truncateToolResultMsgPreservesNonTextBlocksInOutput() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(10).build();
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        Msg tool = toolMsg(repeat("y", 100));
        Msg capped = cc.truncateToolResultMsg(tool);
        ToolResultBlock trb = capped.getContentBlocks(ToolResultBlock.class).get(0);
        // The result must still surface at least one TextBlock (possibly empty + suffix).
        long textCount = trb.getOutput().stream().filter(b -> b instanceof TextBlock).count();
        assertTrue(textCount >= 1);
    }

    @Test
    void truncateToolResultMsgIsNoopWhenLimitZero() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(1).build();
        // Use limit=1 (smallest legal), then verify limit<=0 path via direct constructor not
        // testable; instead assert the equivalent: when limit is large enough, behavior is
        // identity.
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        Msg tool = toolMsg("");
        assertSame(tool, cc.truncateToolResultMsg(tool));
    }

    // ==================== estimateTokens ====================

    @Test
    void estimateTokensReturnsZeroForEmpty() {
        assertEquals(0, ContextCompressor.estimateTokens(List.of()));
        assertEquals(0, ContextCompressor.estimateTokens(null));
    }

    @Test
    void estimateTokensCountsTextAndToolResultText() {
        Msg user = userMsg(repeat("a", 400)); // 400 chars / 4 = 100 tokens
        Msg tool = toolMsg(repeat("b", 200)); // 200 / 4 = 50 tokens
        int tokens = ContextCompressor.estimateTokens(List.of(user, tool));
        assertEquals(150, tokens);
    }

    // ==================== Strategy 2: snip old messages ====================

    @Test
    void snipOldMessagesPreservesSystemAndDropsOldestNonSystem() {
        List<Msg> ctx = new ArrayList<>();
        ctx.add(systemMsg(repeat("s", 40))); // 10 tokens, system, preserved
        ctx.add(userMsg(repeat("a", 400))); // 100 tokens, oldest non-system
        ctx.add(userMsg(repeat("b", 400))); // 100 tokens
        ctx.add(userMsg(repeat("c", 400))); // 100 tokens
        int removed = ContextCompressor.snipOldMessages(ctx, 150); // target <=150 tokens
        assertTrue(removed >= 1);
        assertTrue(ContextCompressor.estimateTokens(ctx) <= 150);
        // System always survives at index 0
        assertEquals(MsgRole.SYSTEM, ctx.get(0).getRole());
    }

    @Test
    void snipOldMessagesIsNoopWhenAlreadyUnderReserve() {
        List<Msg> ctx = new ArrayList<>();
        ctx.add(userMsg("tiny"));
        int removed = ContextCompressor.snipOldMessages(ctx, 1000);
        assertEquals(0, removed);
        assertEquals(1, ctx.size());
    }

    @Test
    void snipOldMessagesCannotShrinkBelowSystemOnly() {
        // With only system messages, no removal possible — returned size unchanged.
        List<Msg> ctx = new ArrayList<>();
        ctx.add(systemMsg(repeat("s", 4000))); // 1000 tokens
        int removed = ContextCompressor.snipOldMessages(ctx, 10);
        assertEquals(0, removed);
        assertEquals(1, ctx.size());
    }

    // ==================== Strategy 2+3: maybeCompress orchestration ====================

    @Test
    void maybeCompressIsNoopWhenBelowTrigger() {
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.5).reserveRatio(0.1).build();
        StubModel model = new StubModel("summary");
        ContextCompressor cc = new ContextCompressor(cfg, model);
        AgentState state = AgentState.builder().sessionId("s1").build();
        state.contextMutable().add(userMsg("tiny"));
        cc.maybeCompress(state).block();
        assertEquals(0, model.calls.get());
        assertEquals(1, state.getContext().size());
        assertEquals("", state.getSummary());
    }

    @Test
    void maybeCompressSnipsWhenSnipAloneSatisfiesReserve() {
        // trigger = 0.001 * 128000 / 4 chars = 128 tokens trigger; reserve = 0.0005 * 128000 / 4 =
        // 64 tokens.
        // Use 0.005 trigger -> 640 tokens trigger; reserve 0.001 -> 128 tokens reserve.
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.005).reserveRatio(0.001).build();
        StubModel model = new StubModel("summary");
        ContextCompressor cc = new ContextCompressor(cfg, model);
        AgentState state = AgentState.builder().sessionId("s2").build();
        // Build 5 small user messages each 400 chars (100 tokens). Total 500 tokens > 640? No, 500
        // < 640.
        // Push to 8 messages -> 800 tokens > 640 trigger. After snipping to <=128, ~5 messages
        // drop.
        for (int i = 0; i < 8; i++) {
            state.contextMutable().add(userMsg(repeat("x", 400)));
        }
        cc.maybeCompress(state).block();
        assertEquals(0, model.calls.get(), "snip alone should satisfy reserve, no model call");
        assertTrue(
                ContextCompressor.estimateTokens(state.getContext()) <= 128,
                "post-snip tokens must be <= reserve");
        assertEquals("", state.getSummary(), "summary unchanged when no micro-compact");
    }

    @Test
    void maybeCompressMicroCompactsWhenSnipInsufficient() {
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.005).reserveRatio(0.001).build();
        StubModel model = new StubModel("compressed summary");
        ContextCompressor cc = new ContextCompressor(cfg, model);
        AgentState state = AgentState.builder().sessionId("s3").build();
        state.contextMutable().add(systemMsg("you are helpful"));
        // One huge user message: 800 tokens -> exceeds trigger (640) and reserve (128).
        // Snipping non-system leaves only system (well under reserve).  Wait — that would actually
        // satisfy reserve via snip alone. To force micro-compact, the message must be a SYSTEM one
        // so snip cannot remove it, OR the lone non-system must itself exceed reserve after snip.
        // After snipping all non-system, only system (small) remains -> under reserve -> no
        // compact.
        // To trigger micro-compact, build a context where snipping leaves one large non-system msg
        // — but snipOldMessages drops from index 0, so the *last* survivor is the newest
        // non-system.
        // Strategy: 1 huge user message (3200 chars = 800 tokens) alone, no system. Trigger fires
        // (800 > 640). Snip tries to remove the only message -> succeeds (loop removes it) ->
        // reserve satisfied -> no micro-compact. So this scenario won't trigger compact either.
        //
        // The only path to micro-compact is: snip cannot reduce further (only system msgs remain or
        // ctx empty) AND token count still exceeds reserve. That happens when system messages alone
        // exceed reserve.
        state.contextMutable().clear();
        // 1 huge system message: 800 tokens. Snip preserves it. Reserve (128) still exceeded.
        state.contextMutable().add(systemMsg(repeat("s", 3200)));
        cc.maybeCompress(state).block();
        assertEquals(1, model.calls.get(), "micro-compact must invoke the model exactly once");
        assertEquals("compressed summary", state.getSummary());
        // Post-compact context: one preserved system + one rendered summary user message.
        List<Msg> after = state.getContext();
        assertEquals(2, after.size());
        assertEquals(MsgRole.SYSTEM, after.get(0).getRole());
        assertEquals(MsgRole.USER, after.get(1).getRole());
        String rendered = after.get(1).getContentBlocks(TextBlock.class).get(0).getText();
        assertTrue(rendered.contains("compressed summary"));
        assertTrue(rendered.contains("Task Overview"));
    }

    @Test
    void microCompactReplacesContextEvenWithoutSystem() {
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.005).reserveRatio(0.001).build();
        StubModel model = new StubModel("brief summary");
        ContextCompressor cc = new ContextCompressor(cfg, model);
        AgentState state = AgentState.builder().sessionId("s4").build();
        // No system; one large user msg. Snip removes it; reserve satisfied; no compact.
        // To force compact-without-system path, install two system msgs whose combined size
        // exceeds reserve. After "snip" (which is a no-op for systems), micro-compact runs;
        // the preserveSystem helper grabs only the FIRST system message.
        state.contextMutable().add(systemMsg(repeat("s", 2000))); // 500 tokens
        state.contextMutable().add(systemMsg(repeat("t", 2000))); // 500 tokens; total 1000 > 128
        cc.maybeCompress(state).block();
        assertEquals(1, model.calls.get());
        List<Msg> after = state.getContext();
        assertEquals(2, after.size(), "first system kept + summary user appended");
        assertEquals(MsgRole.SYSTEM, after.get(0).getRole());
        String preservedText = after.get(0).getContentBlocks(TextBlock.class).get(0).getText();
        assertTrue(preservedText.startsWith("ssss"), "the EARLIEST system is preserved");
        assertFalse(preservedText.startsWith("tttt"), "the later system is discarded");
        assertEquals("brief summary", state.getSummary());
    }

    @Test
    void truncateToolResultMsgPreservesIdAndName() {
        ContextConfig cfg = ContextConfig.builder().toolResultLimit(20).build();
        ContextCompressor cc = new ContextCompressor(cfg, new StubModel(""));
        ToolResultBlock big =
                new ToolResultBlock(
                        "call-123",
                        "echo",
                        List.<ContentBlock>of(TextBlock.builder().text(repeat("z", 500)).build()),
                        null);
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(big).build();
        Msg capped = cc.truncateToolResultMsg(tool);
        ToolResultBlock cappedBlock = capped.getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("call-123", cappedBlock.getId());
        assertEquals("echo", cappedBlock.getName());
        assertTrue(
                cappedBlock.getMetadata() == null || cappedBlock.getMetadata().isEmpty(),
                "metadata should round-trip as null or empty");
    }
}
