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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.context.ContextBudgetExceededException;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Commit gate for {@link MemoryConsolidator}: only a complete, valid consolidation result may
 * replace {@code MEMORY.md} and advance the watermark. Everything else — a length-truncated
 * partial, empty text, tool-call output, an over-budget body, a model error, a pre-commit
 * cancellation, or a rejected auxiliary call — must leave the old memory and the watermark
 * untouched so the next maintenance run retries on the same ledgers.
 */
class MemoryConsolidatorCommitGateTest {

    private static final String OLD_MEMORY = "# Old Memory\n- stale fact";

    @TempDir Path workspace;

    private static void seedDailyLedger(InMemoryStore store, List<String> ns, String content) {
        seedFile(store, ns, "memory/" + LocalDate.now() + ".md", content);
    }

    private static void seedFile(
            InMemoryStore store, List<String> ns, String path, String content) {
        store.put(
                ns,
                path,
                Map.of(
                        "content",
                        content,
                        "encoding",
                        "utf-8",
                        "modified_at",
                        Instant.now().toString()));
    }

    private static ChatResponse textChunk(String text) {
        return ChatResponse.builder()
                .id("chunk")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse finishChunk(String finishReason) {
        return ChatResponse.builder()
                .id("chunk")
                .content(List.of())
                .finishReason(finishReason)
                .build();
    }

    /** Model replaying one scripted response stream per call and recording every input. */
    private static final class ScriptedModel implements Model {

        private final List<Flux<ChatResponse>> scripts;
        private final List<List<Msg>> inputs = new ArrayList<>();
        private final int contextWindowSize;
        private int call;

        ScriptedModel(List<Flux<ChatResponse>> scripts) {
            this(scripts, 0);
        }

        ScriptedModel(List<Flux<ChatResponse>> scripts, int contextWindowSize) {
            this.scripts = scripts;
            this.contextWindowSize = contextWindowSize;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return scripts.get(call++);
        }

        @Override
        public String getModelName() {
            return "scripted-consolidation-model";
        }

        @Override
        public int getContextWindowSize() {
            return contextWindowSize;
        }
    }

    // ======================================================================
    // Acceptance demo: length-truncated partial is refused, the later valid
    // complete result is committed and advances the watermark.
    // ======================================================================

    @Test
    void lengthPartialRejected_thenValidResultCommitted() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("partial text that got cut mid-senten"),
                                        finishChunk("length")),
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            // First run: the model hit its output limit mid-body. The old memory and the
            // watermark must survive so the next run re-merges the same ledger.
            consolidator.consolidate(rc).block();

            assertEquals(
                    OLD_MEMORY,
                    wsm.readMemoryMd(rc),
                    "truncated partial must not replace MEMORY.md");
            assertEquals(
                    Instant.EPOCH,
                    consolidator.readWatermark(rc),
                    "truncated partial must not advance the watermark");

            // Second run: a complete, valid, in-budget result commits and advances.
            consolidator.consolidate(rc).block();

            assertEquals("# Curated\n- fresh fact one", wsm.readMemoryMd(rc));
            assertTrue(
                    consolidator.readWatermark(rc).isAfter(Instant.EPOCH),
                    "successful consolidation must advance the watermark");
        }
    }

    // ======================================================================
    // Tool-call / mixed output is not a valid MEMORY.md body: refused.
    // ======================================================================

    @Test
    void toolCallMixedOutputRejected() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ChatResponse toolCallChunk =
                ChatResponse.builder()
                        .id("chunk")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("# Curated\n- fresh fact one")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("save_memory")
                                                .input(Map.of("k", "v"))
                                                .build()))
                        .finishReason("tool_calls")
                        .build();
        ScriptedModel model = new ScriptedModel(List.of(Flux.just(toolCallChunk)));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            consolidator.consolidate(rc).block();

            assertEquals(
                    OLD_MEMORY,
                    wsm.readMemoryMd(rc),
                    "a response containing tool calls must not replace MEMORY.md");
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
        }
    }

    // ======================================================================
    // A complete but over-budget body (the same max-chars target the prompt
    // states) is refused — MEMORY.md must stay bounded.
    // ======================================================================

    @Test
    void overBudgetOutputRejected() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        // consolidationMaxTokens = 20 → the prompt-level body target is ~80 chars; the
        // model returned a complete ("stop") body far beyond it.
        String overBudget = "x".repeat(200);
        ScriptedModel model =
                new ScriptedModel(List.of(Flux.just(textChunk(overBudget), finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            consolidator.consolidate(rc).block();

            assertEquals(
                    OLD_MEMORY,
                    wsm.readMemoryMd(rc),
                    "an over-budget body must not replace MEMORY.md");
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
        }
    }

    // ======================================================================
    // The consolidation call goes through the budget-only auxiliary boundary:
    // a tiny context window rejects the prepared input before the model is
    // even called — memory and watermark stay untouched.
    // ======================================================================

    @Test
    void budgetRejectedInput_keepsMemoryAndWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))),
                        16);

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            RuntimeException error =
                    assertThrows(
                            RuntimeException.class, () -> consolidator.consolidate(rc).block());

            assertTrue(
                    error instanceof ContextBudgetExceededException
                            || error.getCause() instanceof ContextBudgetExceededException,
                    "expected the auxiliary boundary's budget rejection, got: " + error);
            assertEquals(OLD_MEMORY, wsm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
            assertTrue(model.inputs.isEmpty(), "the model must not be called on a rejected input");
        }
    }

    // ======================================================================
    // The auxiliary call carries exactly the two consolidation messages —
    // no parent conversation history, tools, or task projection.
    // ======================================================================

    @Test
    void auxiliaryCall_carriesOnlyConsolidationMessages() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);

            consolidator.consolidate(RuntimeContext.empty()).block();

            assertEquals(1, model.inputs.size());
            List<Msg> sent = model.inputs.get(0);
            assertEquals(2, sent.size(), "only the SYSTEM prompt and USER payload may be sent");
            assertEquals(MsgRole.SYSTEM, sent.get(0).getRole());
            assertEquals(MsgRole.USER, sent.get(1).getRole());
            String userPayload = sent.get(1).getTextContent();
            assertTrue(userPayload.contains(OLD_MEMORY), "old MEMORY.md must be in the payload");
            assertTrue(
                    userPayload.contains("- fresh fact one"),
                    "ledger entries must be in the payload");
        }
    }

    // ======================================================================
    // A model error mid-stream must not overwrite anything: the commit step
    // never runs.
    // ======================================================================

    @Test
    void modelErrorMidStream_keepsMemoryAndWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(textChunk("# Curated\n- fresh fact one"))
                                        .concatWith(
                                                Flux.error(
                                                        new RuntimeException("model exploded")))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            RuntimeException error =
                    assertThrows(
                            RuntimeException.class, () -> consolidator.consolidate(rc).block());
            assertEquals("model exploded", error.getMessage());

            assertEquals(OLD_MEMORY, wsm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
        }
    }

    // ======================================================================
    // Cancelling the subscription before the stream completes (pre-commit)
    // must not overwrite anything.
    // ======================================================================

    @Test
    void cancelledBeforeCommit_keepsMemoryAndWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        CountDownLatch firstChunkConsumed = new CountDownLatch(1);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(textChunk("# Curated\n- fresh fact one"))
                                        .doOnNext(c -> firstChunkConsumed.countDown())
                                        .concatWith(Flux.never())));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            Disposable subscription = consolidator.consolidate(rc).subscribe();
            assertTrue(firstChunkConsumed.await(5, TimeUnit.SECONDS), "stream must have started");
            subscription.dispose();

            assertEquals(OLD_MEMORY, wsm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
        }
    }

    // ======================================================================
    // The watermark advances only after the MEMORY.md write is confirmed.
    // Here the host-side write fails silently (the target path is occupied
    // by a directory, and the local writer swallows the IO error): without
    // a read-back confirmation the watermark would advance past ledgers
    // that never landed.
    // ======================================================================

    @Test
    void unconfirmedWrite_doesNotAdvanceWatermark() throws Exception {
        Path memDir = workspace.resolve("memory");
        Files.createDirectories(memDir);
        String today = LocalDate.now().toString();
        Files.writeString(memDir.resolve(today + ".md"), "- fresh fact one\n");
        // MEMORY.md cannot be written: a directory occupies its host-side path.
        Files.createDirectory(workspace.resolve("MEMORY.md"));

        SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, sandboxFs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            consolidator.consolidate(rc).block();

            assertEquals(
                    Instant.EPOCH,
                    consolidator.readWatermark(rc),
                    "the watermark must not advance when the MEMORY.md write is unconfirmed");
            assertTrue(
                    Files.notExists(memDir.resolve(MemoryConsolidator.STATE_FILE)),
                    "no consolidation state may be recorded for an unconfirmed write");
        }
    }

    // ======================================================================
    // Empty / whitespace-only output is not a memory body: refused.
    // ======================================================================

    @Test
    void emptyOutput_keepsMemoryAndWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(List.of(Flux.just(textChunk("   \n"), finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.empty();

            consolidator.consolidate(rc).block();

            assertEquals(OLD_MEMORY, wsm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
        }
    }

    // ======================================================================
    // The gate keeps the current identity routing: with a namespace derived
    // from the runtime context's user, a refused result leaves that user's
    // memory and watermark untouched, a valid result lands in the same
    // namespace, and another user's memory is never crossed.
    // ======================================================================

    @Test
    void gateRunsThroughIdentityRoutedNamespace() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = new RemoteFilesystem(store, rc -> List.of("runtime", rc.getUserId()));
        seedFile(store, List.of("runtime", "u1"), "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, List.of("runtime", "u1"), "- fresh fact one");
        seedFile(store, List.of("runtime", "u2"), "MEMORY.md", "# Other user's memory");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("partial text that got cut mid-senten"),
                                        finishChunk("length")),
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, 20);
            RuntimeContext rc = RuntimeContext.builder().userId("u1").sessionId("s1").build();

            consolidator.consolidate(rc).block();

            assertEquals(OLD_MEMORY, wsm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));

            consolidator.consolidate(rc).block();

            assertEquals("# Curated\n- fresh fact one", wsm.readMemoryMd(rc));
            assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));

            StoreItem other = store.get(List.of("runtime", "u2"), "MEMORY.md");
            assertEquals(
                    "# Other user's memory",
                    other.value().get("content"),
                    "another identity's memory must stay untouched");
        }
    }

    // ======================================================================
    // With a BaseStore mounted, the watermark lives in the store (CAS write,
    // store-preferred read): a gate refusal leaves it absent, and only the
    // confirmed commit creates it.
    // ======================================================================

    @Test
    void storeWatermark_advancesOnlyOnCommittedResult() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");

        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("partial text that got cut mid-senten"),
                                        finishChunk("length")),
                                Flux.just(
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));

        try (WorkspaceManager wsm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();

            consolidator.consolidate(rc).block();

            assertNull(
                    store.get(List.of("memory", "consolidation"), "watermark"),
                    "a refused result must not create the store watermark");
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));

            consolidator.consolidate(rc).block();

            StoreItem watermark = store.get(List.of("memory", "consolidation"), "watermark");
            assertNotNull(watermark, "a committed result must write the store watermark");
            assertTrue(
                    ((Number) watermark.value().get("ts")).longValue() > 0,
                    "the store watermark must carry the run timestamp");
            assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));
        }
    }
}
