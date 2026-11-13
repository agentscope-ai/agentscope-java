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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Commit gate for {@link MemoryConsolidator}: only a complete, valid consolidation result may
 * replace {@code MEMORY.md} and advance the watermark. Everything else — a length-truncated
 * partial, empty text, tool-call output, an over-budget body, a model error, a pre-commit
 * cancellation — must leave the old memory and the watermark
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
        private int call;

        ScriptedModel(List<Flux<ChatResponse>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            assertNull(tools, "2.0.x consolidation must not pass tools");
            assertNull(options, "body target must not become a model API output limit");
            inputs.add(List.copyOf(messages));
            return scripts.get(call++);
        }

        @Override
        public String getModelName() {
            return "scripted-consolidation-model";
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "MAX_TOKENS", "max_tokens", "incomplete"})
    void outputLimitAcrossSeparateTextMetadataAndFinishChunksIsRejected(String reason) {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        ChatResponse metadata =
                ChatResponse.builder()
                        .id("metadata")
                        .metadata(Map.of("openai.response.incomplete_reason", "max_output_tokens"))
                        .build();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        textChunk("partial text"), metadata, finishChunk(reason))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new RemoteFilesystem(store, ns))) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, model, 20);
            consolidator.consolidate(RuntimeContext.empty()).block();
            assertEquals(OLD_MEMORY, wm.readMemoryMd(RuntimeContext.empty()));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    @Test
    void toolResultBlockIsRejectedEvenWhenFinalChunkHasOnlyText() {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("gate-test");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        ChatResponse toolResult =
                ChatResponse.builder()
                        .id("tool-result")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("save_memory")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("saved")
                                                                        .build()))
                                                .build()))
                        .build();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        toolResult,
                                        textChunk("# Curated\n- fresh fact one"),
                                        finishChunk("stop"))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new RemoteFilesystem(store, ns))) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, model, 20);
            consolidator.consolidate(RuntimeContext.empty()).block();
            assertEquals(OLD_MEMORY, wm.readMemoryMd(RuntimeContext.empty()));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unsuccessful",
                "absent",
                "null-list",
                "null-entry",
                "wrong-upload-path",
                "upload-no-path",
                "partial",
                "mismatch",
                "miss",
                "empty",
                "null-content",
                "download-absent",
                "download-null-list",
                "download-null-entry",
                "wrong-download-path",
                "download-no-path",
                "throw-write",
                "throw-read"
            })
    void unconfirmedCommitPreservesExistingFileAndStoreWatermarks(String failure) throws Exception {
        unconfirmedCommitPreservesWatermarks(failure, false);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unsuccessful",
                "absent",
                "null-list",
                "null-entry",
                "wrong-upload-path",
                "upload-no-path",
                "partial",
                "mismatch",
                "miss",
                "empty",
                "null-content",
                "download-absent",
                "download-null-list",
                "download-null-entry",
                "wrong-download-path",
                "download-no-path",
                "throw-write",
                "throw-read"
            })
    void boundUnconfirmedCommitPreservesExistingFileAndStoreWatermarks(String failure)
            throws Exception {
        unconfirmedCommitPreservesWatermarks(failure, true);
    }

    private void unconfirmedCommitPreservesWatermarks(String failure, boolean bound)
            throws Exception {
        String candidate = "# Curated\n- fresh fact one";
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory namespace = rc -> List.of("runtime", rc.getUserId());
        List<String> ns = List.of("runtime", "bob");
        Instant oldWatermark = Instant.parse("2025-01-01T00:00:00Z");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        seedFile(store, ns, MemoryConsolidator.STATE_REL_PATH, oldWatermark.toString());
        store.put(
                List.of("memory", "consolidation"),
                "watermark",
                Map.of("ts", oldWatermark.toEpochMilli()));
        // The identical stale copy is in the SAME namespace, not merely a root/sibling copy.
        Path stale = workspace.resolve("runtime/bob/MEMORY.md");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, candidate);
        RuntimeContext caller = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        AtomicBoolean wrote = new AtomicBoolean();
        AtomicBoolean downloaded = new AtomicBoolean();
        ScriptedModel model =
                new ScriptedModel(List.of(Flux.just(textChunk(candidate), finishChunk("stop"))));
        RemoteFilesystem fs =
                new RemoteFilesystem(store, namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        assertSame(caller, rc);
                        String path = files.get(0).getKey();
                        assertEquals("MEMORY.md", path, "no progress writes before confirmation");
                        wrote.set(true);
                        return switch (failure) {
                            case "unsuccessful" ->
                                    List.of(FileUploadResponse.fail(path, "upload failed"));
                            case "absent" -> List.of();
                            case "null-list" -> null;
                            case "null-entry" -> Collections.singletonList(null);
                            case "wrong-upload-path" ->
                                    List.of(FileUploadResponse.success("other.md"));
                            case "upload-no-path" -> List.of(FileUploadResponse.success(null));
                            case "throw-write" -> throw new IllegalStateException("write failed");
                            default -> super.uploadFiles(rc, files);
                        };
                    }

                    @Override
                    public List<FileDownloadResponse> downloadFiles(
                            RuntimeContext rc, List<String> paths) {
                        assertSame(caller, rc);
                        String path = paths.get(0);
                        assertEquals("MEMORY.md", path);
                        downloaded.set(true);
                        return switch (failure) {
                            case "miss" ->
                                    List.of(FileDownloadResponse.fail(path, "file_not_found"));
                            case "empty" ->
                                    List.of(FileDownloadResponse.success(path, new byte[0]));
                            case "null-content" ->
                                    List.of(FileDownloadResponse.success(path, null));
                            case "download-absent" -> List.of();
                            case "download-null-list" -> null;
                            case "download-null-entry" -> Collections.singletonList(null);
                            case "wrong-download-path" ->
                                    List.of(
                                            FileDownloadResponse.success(
                                                    "other.md",
                                                    candidate.getBytes(StandardCharsets.UTF_8)));
                            case "download-no-path" ->
                                    List.of(
                                            FileDownloadResponse.success(
                                                    null,
                                                    candidate.getBytes(StandardCharsets.UTF_8)));
                            case "throw-read" -> throw new IllegalStateException("read failed");
                            default ->
                                    List.of(
                                            FileDownloadResponse.success(
                                                    path,
                                                    (failure.equals("partial")
                                                                    ? candidate.substring(0, 5)
                                                                    : OLD_MEMORY)
                                                            .getBytes(StandardCharsets.UTF_8)));
                        };
                    }

                    @Override
                    public ReadResult read(RuntimeContext rc, String path, int offset, int limit) {
                        assertSame(caller, rc);
                        return wrote.get() && path.equals("MEMORY.md")
                                ? ReadResult.fail("authoritative copy unavailable")
                                : super.read(rc, path, offset, limit);
                    }
                };
        RuntimeContext callRc =
                bound
                        ? RuntimeContext.builder().userId("OTHER").sessionId("other").build()
                        : caller;
        try (WorkspaceManager wm =
                new WorkspaceManager(
                        workspace,
                        bound ? new BakedContextFilesystem(fs, caller) : fs,
                        null,
                        bound ? ignored -> namespace.getNamespace(caller) : namespace)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            if (failure.startsWith("throw-")) {
                IllegalStateException error =
                        assertThrows(
                                IllegalStateException.class,
                                () -> consolidator.consolidate(callRc).block());
                assertEquals(
                        failure.equals("throw-write") ? "write failed" : "read failed",
                        error.getMessage());
            } else {
                consolidator.consolidate(callRc).block();
            }
            assertTrue(wrote.get(), "candidate reached the write gate");
            assertEquals(
                    !List.of(
                                    "unsuccessful",
                                    "absent",
                                    "null-list",
                                    "null-entry",
                                    "wrong-upload-path",
                                    "upload-no-path",
                                    "throw-write")
                            .contains(failure),
                    downloaded.get());
            assertEquals(
                    candidate,
                    wm.readMemoryMd(caller),
                    "ordinary same-namespace fallback stays available but cannot confirm");
            assertEquals(candidate, Files.readString(stale));
            assertEquals(oldWatermark, consolidator.readWatermark(callRc));
            assertNull(store.get(List.of("runtime", "OTHER"), MemoryConsolidator.STATE_REL_PATH));
            assertEquals(
                    oldWatermark.toEpochMilli(),
                    store.get(List.of("memory", "consolidation"), "watermark").value().get("ts"));
            assertEquals(
                    oldWatermark.toString(),
                    wm.readMemoryFileUtf8(caller, MemoryConsolidator.STATE_REL_PATH));
        }
    }

    @Test
    void readbackCannotConfirmAWriteUsingRootOrAnotherUsersIdenticalBody() throws Exception {
        String candidate = "# Curated\n- fresh fact one";
        Instant oldWatermark = Instant.parse("2025-01-01T00:00:00Z");
        Files.createDirectories(workspace.resolve("alice/memory"));
        Files.createDirectories(workspace.resolve("bob/memory"));
        Files.writeString(workspace.resolve("MEMORY.md"), candidate);
        Files.writeString(workspace.resolve("alice/MEMORY.md"), candidate);
        Files.writeString(
                workspace.resolve("alice/" + MemoryConsolidator.STATE_REL_PATH),
                oldWatermark.toString());
        Files.writeString(
                workspace.resolve("bob/memory/" + LocalDate.now() + ".md"), "- fresh fact one");
        Files.writeString(
                workspace.resolve("bob/" + MemoryConsolidator.STATE_REL_PATH),
                oldWatermark.toString());
        RuntimeContext caller = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        ScriptedModel model =
                new ScriptedModel(List.of(Flux.just(textChunk(candidate), finishChunk("stop"))));
        Files.createDirectory(workspace.resolve("bob/MEMORY.md")); // authoritative write fails
        try (WorkspaceManager wm =
                new WorkspaceManager(
                        workspace,
                        new SandboxBackedFilesystem(),
                        null,
                        rc -> List.of(rc.getUserId()))) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, model, 20);
            consolidator.consolidate(caller).block();
            assertEquals("", wm.readMemoryMd(caller));
            assertEquals(oldWatermark, consolidator.readWatermark(caller));
            assertFalse(Files.isRegularFile(workspace.resolve("bob/MEMORY.md")));
            assertEquals(candidate, Files.readString(workspace.resolve("MEMORY.md")));
            assertEquals(candidate, Files.readString(workspace.resolve("alice/MEMORY.md")));
            assertEquals(
                    oldWatermark.toString(),
                    Files.readString(
                            workspace.resolve("alice/" + MemoryConsolidator.STATE_REL_PATH)));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "local",
                "remote",
                "composite",
                "routed-remote",
                "sandbox",
                "routed-host",
                "overlay",
                "shell-overlay",
                "routed-overlay"
            })
    void realDestinationConfirmationKeepsNamespaceProgressIsolated(String backend)
            throws Exception {
        NamespaceFactory namespace = rc -> List.of("runtime", rc.getUserId(), rc.getSessionId());
        Path host = workspace.resolve("host");
        Path disk = workspace.resolve("backend");
        Files.createDirectories(host);
        Files.createDirectories(disk);
        LocalFilesystem local = new LocalFilesystem(disk, false, 10, namespace);
        RemoteFilesystem remote = new RemoteFilesystem(new InMemoryStore(), namespace);
        AbstractFilesystem fs =
                switch (backend) {
                    case "local" -> local;
                    case "remote" -> remote;
                    case "composite" ->
                            new CompositeFilesystem(
                                    local, Map.of("MEMORY.md", remote, "memory/", remote));
                    case "routed-remote" ->
                            new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(),
                                    Map.of("MEMORY.md", remote, "memory/", remote));
                    case "sandbox" -> new SandboxBackedFilesystem();
                    case "routed-host" ->
                            new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(), Map.of("unrelated/", remote));
                    case "overlay" -> new OverlayFilesystem(local, remote);
                    case "shell-overlay" ->
                            OverlayFilesystem.of(
                                    new LocalFilesystemWithShell(disk, namespace), remote);
                    case "routed-overlay" ->
                            new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(),
                                    Map.of(
                                            "MEMORY.md",
                                            new OverlayFilesystem(
                                                    new RemoteFilesystem(
                                                            new InMemoryStore(), namespace),
                                                    remote),
                                            "memory/",
                                            remote));
                    default -> throw new AssertionError(backend);
                };
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("shared").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        String candidate = "# Curated\n- fresh fact one";
        Instant old = Instant.parse("2025-01-01T00:00:00Z");
        ScriptedModel model =
                new ScriptedModel(List.of(Flux.just(textChunk(candidate), finishChunk("stop"))));
        try (WorkspaceManager wm = new WorkspaceManager(host, fs, null, namespace)) {
            for (RuntimeContext rc : List.of(alice, bob)) {
                wm.writeMemoryFileUtf8(rc, "MEMORY.md", OLD_MEMORY);
                wm.writeMemoryFileUtf8(rc, "memory/" + LocalDate.now() + ".md", "- fresh fact one");
                wm.writeMemoryFileUtf8(rc, MemoryConsolidator.STATE_REL_PATH, old.toString());
            }
            // Identical local fallback must not be the confirmation target for a remote write.
            Path fallback = wm.resolveRuntimeDataPath(alice, "MEMORY.md");
            if (!backend.equals("sandbox") && !backend.equals("routed-host")) {
                Files.createDirectories(fallback.getParent());
                Files.writeString(fallback, candidate);
            }
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, model, 20);
            assertFalse(wm.listMemoryFileInfos(alice).isEmpty(), backend);
            assertEquals(
                    "- fresh fact one",
                    wm.readMemoryFileUtf8(alice, "memory/" + LocalDate.now() + ".md"),
                    backend);
            consolidator.consolidate(alice).block();
            assertEquals(1, model.inputs.size(), backend);
            assertEquals(candidate, wm.readMemoryMd(alice));
            assertTrue(consolidator.readWatermark(alice).isAfter(old), backend);
            assertEquals(OLD_MEMORY, wm.readMemoryMd(bob));
            assertEquals(old, consolidator.readWatermark(bob));
            if (backend.equals("local") || backend.equals("overlay")) {
                assertArrayEquals(
                        candidate.getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(disk.resolve("runtime/alice/shared/MEMORY.md")));
            }
            // Identity aliases remain legal, and routed directory prefixes are stripped exactly
            // once.
            String alias = "memory/alice/shared.md";
            String exact = "unicode 中文\r\nsecond line\n";
            assertTrue(wm.writeMemoryFileUtf8AndConfirm(alice, alias, exact));
            assertEquals("", wm.readMemoryFileUtf8(bob, alias));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"overlay", "composite-overlay", "routed-overlay"})
    void missingWritableOverlayCopyCannotConfirmViaIdenticalLowerCopy(String route)
            throws Exception {
        String candidate = "# Curated\n- fresh fact one";
        NamespaceFactory namespace = rc -> List.of(rc.getUserId());
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        RemoteFilesystem upper =
                new RemoteFilesystem(new InMemoryStore(), namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        return List.of(
                                FileUploadResponse.success(
                                        files.get(0).getKey())); // acknowledged, but missing
                    }
                };
        RemoteFilesystem lower = new RemoteFilesystem(new InMemoryStore(), namespace);
        lower.uploadFiles(
                bob,
                List.of(
                        Map.entry("/MEMORY.md", candidate.getBytes(StandardCharsets.UTF_8)),
                        Map.entry("MEMORY.md", candidate.getBytes(StandardCharsets.UTF_8))));
        OverlayFilesystem overlay = new OverlayFilesystem(upper, lower);
        AbstractFilesystem fs =
                switch (route) {
                    case "overlay" -> overlay;
                    case "composite-overlay" ->
                            new CompositeFilesystem(lower, Map.of("MEMORY.md", overlay));
                    case "routed-overlay" ->
                            new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(), Map.of("MEMORY.md", overlay));
                    default -> throw new AssertionError(route);
                };
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null, namespace)) {
            assertEquals(
                    candidate, wm.readMemoryMd(bob), "ordinary lower fallback remains supported");
            assertFalse(wm.writeMemoryFileUtf8AndConfirm(bob, "MEMORY.md", candidate));
            assertEquals(candidate, wm.readMemoryMd(bob));
        }
    }

    @Test
    void cancelledWhileCheckedWriterWaitsForOrdinaryAppendLockCannotCommit() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("lock-cancellation");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "fresh fact");
        CountDownLatch appendReading = new CountDownLatch(1);
        CountDownLatch releaseAppend = new CountDownLatch(1);
        CountDownLatch modelFinished = new CountDownLatch(1);
        AtomicBoolean holdAppend = new AtomicBoolean();
        RemoteFilesystem fs =
                new RemoteFilesystem(store, ns) {
                    @Override
                    public ReadResult read(RuntimeContext rc, String path, int offset, int limit) {
                        if (path.equals("MEMORY.md") && holdAppend.compareAndSet(true, false)) {
                            appendReading.countDown();
                            awaitUninterruptibly(releaseAppend);
                        }
                        return super.read(rc, path, offset, limit);
                    }
                };
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(textChunk("curated fact"), finishChunk("stop"))
                                        .doOnComplete(modelFinished::countDown),
                                Flux.just(textChunk("curated fact"), finishChunk("stop"))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();
            // Prepare while reads are available, then let the REAL ordinary append own the lock.
            Mono<Void> run = consolidator.consolidate(rc);
            holdAppend.set(true);
            CompletableFuture<Void> append =
                    CompletableFuture.runAsync(
                            () -> wm.appendMemoryFileUtf8(rc, "MEMORY.md", "\nordinary append"));
            Disposable subscription = null;
            try {
                assertTrue(appendReading.await(5, TimeUnit.SECONDS));
                subscription = run.subscribe();
                assertTrue(modelFinished.await(5, TimeUnit.SECONDS));
                var field = WorkspaceManager.class.getDeclaredField("pathLocks");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ReentrantLock> locks = (Map<String, ReentrantLock>) field.get(wm);
                ReentrantLock lock = locks.get("MEMORY.md");
                Thread writer = awaitQueuedWriter(lock);
                subscription.dispose();
                releaseAppend.countDown();
                append.get(5, TimeUnit.SECONDS);
                awaitCommitExit(writer);
                assertAll(
                        () -> assertEquals(OLD_MEMORY + "\nordinary append", wm.readMemoryMd(rc)),
                        () -> assertEquals(Instant.EPOCH, consolidator.readWatermark(rc)),
                        () ->
                                assertNull(
                                        store.get(List.of("memory", "consolidation"), "watermark")),
                        () ->
                                assertEquals(
                                        "",
                                        wm.readMemoryFileUtf8(
                                                rc, MemoryConsolidator.STATE_REL_PATH)));
                // No progress was lost: the same ledger remains eligible for a later run.
                consolidator.consolidate(rc).block();
                assertEquals(2, model.inputs.size());
                assertTrue(model.inputs.get(1).get(1).getTextContent().contains("fresh fact"));
                assertEquals("curated fact", wm.readMemoryMd(rc));
                assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));
            } finally {
                if (subscription != null) {
                    subscription.dispose();
                }
                releaseAppend.countDown();
                append.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    assertTrue(latch.await(5, TimeUnit.SECONDS), "test gate must be released");
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Thread awaitQueuedWriter(ReentrantLock lock) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (lock.hasQueuedThread(thread)) {
                    return thread;
                }
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("checked writer must wait on the ordinary append's shared lock");
    }

    private static void awaitCommitExit(Thread writer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            boolean committing = false;
            for (StackTraceElement frame : writer.getStackTrace()) {
                if (frame.getClassName().startsWith(MemoryConsolidator.class.getName())
                        || frame.getClassName().equals(WorkspaceManager.class.getName())) {
                    committing = true;
                    break;
                }
            }
            if (!committing) {
                return;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("cancelled commit must stop after lock release");
    }

    @Test
    void checkedUploadCannotBypassRejectingProjectWriterOnCompositeExactRoute() throws Exception {
        Path upperRoot = workspace.resolve("upper");
        Path lowerRoot = workspace.resolve("lower");
        Path projectRoot = workspace.resolve("project");
        Files.createDirectories(upperRoot);
        Files.createDirectories(lowerRoot);
        Files.createDirectories(projectRoot);
        AtomicReference<String> attempted = new AtomicReference<>();
        LocalFilesystem project =
                new LocalFilesystem(projectRoot, true, 10) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        attempted.set(files.get(0).getKey());
                        return List.of(
                                FileUploadResponse.fail(files.get(0).getKey(), "project rejected"));
                    }
                };
        ProjectAwareOverlay overlay =
                new ProjectAwareOverlay(
                        new LocalFilesystemWithShell(upperRoot, true, 30, 100_000, null, false),
                        new LocalFilesystem(lowerRoot, true, 10),
                        project,
                        upperRoot,
                        null);
        CompositeFilesystem fs = new CompositeFilesystem(overlay, Map.of("MEMORY.md", overlay));
        assertFalse(
                fs.uploadFiles(
                                RuntimeContext.empty(),
                                List.of(
                                        Map.entry(
                                                "MEMORY.md",
                                                "ordinary".getBytes(StandardCharsets.UTF_8))))
                        .get(0)
                        .isSuccess());
        assertEquals("/MEMORY.md", attempted.get());
        attempted.set(null);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            assertFalse(
                    wm.writeMemoryFileUtf8AndConfirm(
                            RuntimeContext.empty(), "MEMORY.md", "curated fact"));
            assertEquals("/MEMORY.md", attempted.get(), "checked upload must use actual policy");
            assertFalse(Files.exists(upperRoot.resolve("MEMORY.md")));
            assertFalse(Files.exists(projectRoot.resolve("MEMORY.md")));
        }
    }

    @Test
    void cancellationAfterBodyStartsStopsWatermarkAndDoesNotPoisonAnotherSubscription()
            throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("body-cancellation");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "fresh fact");
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicBoolean holdFirstBody = new AtomicBoolean(true);
        AtomicReference<Thread> writer = new AtomicReference<>();
        RemoteFilesystem fs =
                new RemoteFilesystem(store, ns) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        if (files.get(0).getKey().equals("MEMORY.md")
                                && holdFirstBody.compareAndSet(true, false)) {
                            writer.set(Thread.currentThread());
                            bodyStarted.countDown();
                            awaitUninterruptibly(releaseBody);
                            // Simulate a blocking backend that swallows/clears scheduler
                            // interruption.
                            Thread.interrupted();
                        }
                        return super.uploadFiles(rc, files);
                    }
                };
        String candidate = "curated fact that fits once but not twice in the body target";
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(textChunk(candidate), finishChunk("stop")),
                                Flux.just(textChunk(candidate), finishChunk("stop"))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();
            Mono<Void> run = consolidator.consolidate(rc);
            Disposable subscription = run.subscribe();
            try {
                assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
                subscription.dispose();
                releaseBody.countDown();
                awaitCommitExit(writer.get());
                assertEquals(
                        candidate, wm.readMemoryMd(rc), "no rollback after physical IO starts");
                assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
                assertNull(store.get(List.of("memory", "consolidation"), "watermark"));
                assertEquals("", wm.readMemoryFileUtf8(rc, MemoryConsolidator.STATE_REL_PATH));
                // Subscribe again to the SAME Mono: guards and output accumulators must be private.
                run.block();
                assertEquals(2, model.inputs.size());
                assertEquals(candidate, wm.readMemoryMd(rc));
                assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));
            } finally {
                subscription.dispose();
                releaseBody.countDown();
                if (writer.get() != null) {
                    awaitCommitExit(writer.get());
                }
            }
        }
    }

    @Test
    void checkedWriterRestoresInterruptWhileOrdinaryBestEffortIoKeepsItsBehavior() {
        RemoteFilesystem fs = new RemoteFilesystem(new InMemoryStore(), List.of("interrupt-test"));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            RuntimeContext rc = RuntimeContext.empty();
            wm.writeMemoryFileUtf8(rc, "MEMORY.md", OLD_MEMORY);
            try {
                Thread.currentThread().interrupt();
                assertFalse(wm.writeMemoryFileUtf8AndConfirm(rc, "MEMORY.md", "not committed"));
                assertTrue(
                        Thread.currentThread().isInterrupted(), "caught interruption is restored");
                assertEquals(OLD_MEMORY, wm.readMemoryMd(rc));
                wm.writeMemoryFileUtf8(rc, "MEMORY.md", "ordinary write");
                wm.appendMemoryFileUtf8(rc, "MEMORY.md", " + append");
                assertEquals("ordinary write + append", wm.readMemoryMd(rc));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"composite-exact", "composite-prefix", "routed-exact", "routed-prefix"})
    void projectConfirmationUsesActualWriterNotIdenticalUpperOrLower(String route)
            throws Exception {
        RuntimeContext caller = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        String candidate = "curated fact\r\n中文\n";
        String path = route.endsWith("prefix") ? "memory/MEMORY.md" : "MEMORY.md";
        String mount = route.endsWith("prefix") ? "memory/" : "MEMORY.md";
        for (String outcome : List.of("reject", "ack-only", "write")) {
            Path upperRoot = workspace.resolve(outcome + "/upper");
            Path lowerRoot = workspace.resolve(outcome + "/lower");
            Path projectRoot = workspace.resolve(outcome + "/project");
            Files.createDirectories(upperRoot);
            Files.createDirectories(lowerRoot);
            Files.createDirectories(projectRoot);
            // Both ordinary read layers match, but neither is the actual project write target.
            Files.writeString(upperRoot.resolve("MEMORY.md"), candidate);
            Files.writeString(lowerRoot.resolve("MEMORY.md"), candidate);
            AtomicInteger uploads = new AtomicInteger();
            AtomicReference<String> confirmedPath = new AtomicReference<>();
            LocalFilesystem project =
                    new LocalFilesystem(projectRoot, true, 10) {
                        @Override
                        public List<FileUploadResponse> uploadFiles(
                                RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                            assertSame(caller, rc);
                            assertEquals("/MEMORY.md", files.get(0).getKey(), "remap exactly once");
                            uploads.incrementAndGet();
                            return switch (outcome) {
                                case "reject" ->
                                        List.of(FileUploadResponse.fail("/MEMORY.md", "rejected"));
                                case "ack-only" ->
                                        List.of(FileUploadResponse.success("/MEMORY.md"));
                                default -> super.uploadFiles(rc, files);
                            };
                        }

                        @Override
                        public List<FileDownloadResponse> downloadFiles(
                                RuntimeContext rc, List<String> paths) {
                            assertSame(caller, rc);
                            confirmedPath.set(paths.get(0));
                            return super.downloadFiles(rc, paths);
                        }
                    };
            ProjectAwareOverlay overlay =
                    new ProjectAwareOverlay(
                            new LocalFilesystemWithShell(upperRoot, true, 30, 100_000, null, false),
                            new LocalFilesystem(lowerRoot, true, 10),
                            project,
                            upperRoot,
                            null);
            AbstractFilesystem fs =
                    route.startsWith("composite")
                            ? new CompositeFilesystem(overlay, Map.of(mount, overlay))
                            : new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(), Map.of(mount, overlay));
            try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
                assertEquals(candidate, wm.readMemoryFileUtf8(caller, path));
                assertEquals(
                        outcome.equals("write"),
                        wm.writeMemoryFileUtf8AndConfirm(caller, path, candidate),
                        outcome);
                assertEquals(1, uploads.get(), "must retain actual upload dispatch");
                assertEquals(outcome.equals("reject") ? null : "/MEMORY.md", confirmedPath.get());
                assertEquals(candidate, Files.readString(upperRoot.resolve("MEMORY.md")));
                assertEquals(candidate, Files.readString(lowerRoot.resolve("MEMORY.md")));
                assertEquals(candidate, wm.readMemoryFileUtf8(caller, path));
                if (outcome.equals("write")) {
                    assertArrayEquals(
                            candidate.getBytes(StandardCharsets.UTF_8),
                            Files.readAllBytes(projectRoot.resolve("MEMORY.md")));
                } else {
                    assertFalse(Files.exists(projectRoot.resolve("MEMORY.md")));
                }
                // The void writer remains best-effort, including a failed upload response.
                wm.writeMemoryFileUtf8(caller, path, candidate);
                assertEquals(2, uploads.get());
            }
        }
    }

    @Test
    void projectUploadInspectionUsesExistingNamespaceClassificationAndNormalization()
            throws Exception {
        NamespaceFactory namespace = rc -> List.of("runtime", rc.getUserId(), rc.getSessionId());
        RuntimeContext caller = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        Path upperRoot = workspace.resolve("scoped-upper");
        Path projectRoot = workspace.resolve("scoped-project");
        Files.createDirectories(upperRoot);
        Files.createDirectories(projectRoot);
        LocalFilesystemWithShell upper = new LocalFilesystemWithShell(upperRoot, namespace);
        LocalFilesystem project = new LocalFilesystem(projectRoot, true, 10, namespace);
        ProjectAwareOverlay overlay =
                new ProjectAwareOverlay(
                        upper,
                        new LocalFilesystem(workspace.resolve("scoped-lower"), true, 10),
                        project,
                        upperRoot,
                        namespace);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, overlay, null, namespace)) {
            assertTrue(wm.writeMemoryFileUtf8AndConfirm(caller, "MEMORY.md", "workspace fact"));
            assertEquals(
                    "workspace fact",
                    Files.readString(upperRoot.resolve("runtime/bob/shared/MEMORY.md")));
            assertFalse(Files.exists(projectRoot.resolve("runtime/bob/shared/MEMORY.md")));
            // Unlike write(), uploadFiles keeps the original spelling of workspace uploads.
            for (String path :
                    List.of(
                            "MEMORY.md",
                            upperRoot.resolve("runtime/bob/shared/MEMORY.md").toString())) {
                OverlayFilesystem.UploadDestination target =
                        overlay.uploadDestination(caller, path);
                assertSame(upper, target.filesystem());
                assertEquals(path, target.path());
                assertTrue(
                        overlay.uploadFiles(
                                        caller,
                                        List.of(
                                                Map.entry(
                                                        path,
                                                        "updated fact"
                                                                .getBytes(StandardCharsets.UTF_8))))
                                .get(0)
                                .isSuccess());
                assertArrayEquals(
                        "updated fact".getBytes(StandardCharsets.UTF_8),
                        target.filesystem()
                                .downloadFiles(caller, List.of(target.path()))
                                .get(0)
                                .content());
            }
            for (String path :
                    List.of(
                            "src/fact.md",
                            upperRoot.resolve("runtime/bob/shared/src/fact.md").toString())) {
                OverlayFilesystem.UploadDestination target =
                        overlay.uploadDestination(caller, path);
                assertSame(project, target.filesystem());
                assertEquals("src/fact.md", target.path());
                assertTrue(
                        overlay.uploadFiles(
                                        caller,
                                        List.of(
                                                Map.entry(
                                                        path,
                                                        "project fact"
                                                                .getBytes(StandardCharsets.UTF_8))))
                                .get(0)
                                .isSuccess());
                assertEquals(
                        "project fact",
                        Files.readString(projectRoot.resolve("runtime/bob/shared/src/fact.md")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"overlay", "project-aware"})
    void customOverlayUploadPolicyFailsClosedWithoutBypassingOrdinaryDispatch(String type)
            throws Exception {
        Path upperRoot = workspace.resolve("custom-upper");
        Files.createDirectories(upperRoot);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(upperRoot, true, 30, 100_000, null, false);
        LocalFilesystem lower = new LocalFilesystem(workspace.resolve("custom-lower"), true, 10);
        AtomicInteger uploads = new AtomicInteger();
        AbstractFilesystem custom =
                type.equals("overlay")
                        ? new OverlayFilesystem(upper, lower) {
                            @Override
                            public List<FileUploadResponse> uploadFiles(
                                    RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                                uploads.incrementAndGet();
                                return List.of(
                                        FileUploadResponse.fail(
                                                files.get(0).getKey(), "custom policy"));
                            }
                        }
                        : new ProjectAwareOverlay(upper, lower, lower, upperRoot, null) {
                            @Override
                            public List<FileUploadResponse> uploadFiles(
                                    RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                                uploads.incrementAndGet();
                                return List.of(
                                        FileUploadResponse.fail(
                                                files.get(0).getKey(), "custom policy"));
                            }
                        };
        try (WorkspaceManager wm = new WorkspaceManager(workspace, custom, null)) {
            assertFalse(
                    wm.writeMemoryFileUtf8AndConfirm(RuntimeContext.empty(), "MEMORY.md", "fact"));
            assertEquals(0, uploads.get(), "unknown upload destination must fail before body IO");
            wm.writeMemoryFileUtf8(RuntimeContext.empty(), "MEMORY.md", "ordinary");
            assertEquals(1, uploads.get(), "best-effort writer still invokes custom policy");
            assertFalse(Files.exists(upperRoot.resolve("MEMORY.md")));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "direct",
                "composite-exact",
                "composite-prefix",
                "routed-exact",
                "routed-prefix",
                "nested-outer",
                "nested-backend"
            })
    void boundCheckedRoutesUseEffectiveContextAndRetainOriginalUpload(String route) {
        RuntimeContext outer = RuntimeContext.builder().userId("userA").sessionId("outer").build();
        RuntimeContext inner = RuntimeContext.builder().userId("userB").sessionId("inner").build();
        RuntimeContext effective = route.startsWith("nested") ? inner : outer;
        NamespaceFactory namespace = IsolationScope.USER.toNamespaceFactory();
        InMemoryStore store = new InMemoryStore();
        String candidate = "curated\r\n中文\n";
        String path = route.endsWith("prefix") ? "memory/ledger.md" : "MEMORY.md";
        String backendPath =
                route.equals("direct") || route.equals("nested-outer")
                        ? path
                        : "/" + (route.endsWith("prefix") ? "ledger.md" : "MEMORY.md");
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        RemoteFilesystem remote =
                new RemoteFilesystem(store, namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        assertSame(effective, rc);
                        assertEquals(backendPath, files.get(0).getKey(), "remap exactly once");
                        uploads.incrementAndGet();
                        return super.uploadFiles(rc, files);
                    }

                    @Override
                    public List<FileDownloadResponse> downloadFiles(
                            RuntimeContext rc, List<String> paths) {
                        assertSame(
                                effective,
                                rc,
                                "authoritative IO must not reuse empty/OTHER caller");
                        assertEquals(backendPath, paths.get(0));
                        downloads.incrementAndGet();
                        return super.downloadFiles(rc, paths);
                    }
                };
        AbstractFilesystem routed;
        if (route.equals("direct") || route.equals("nested-outer")) {
            routed = remote;
        } else {
            String mount = route.endsWith("prefix") ? "memory/" : "MEMORY.md";
            AbstractFilesystem backend =
                    route.equals("nested-backend")
                            ? new BakedContextFilesystem(remote, inner)
                            : remote;
            routed =
                    route.startsWith("routed")
                            ? new RoutedSandboxFilesystem(
                                    new SandboxBackedFilesystem(), Map.of(mount, backend))
                            : new CompositeFilesystem(
                                    new SandboxBackedFilesystem(), Map.of(mount, backend));
        }
        AbstractFilesystem fs =
                new BakedContextFilesystem(
                        route.equals("nested-outer")
                                ? new BakedContextFilesystem(routed, inner)
                                : routed,
                        outer);
        try (WorkspaceManager wm =
                new WorkspaceManager(
                        workspace, fs, null, rc -> namespace.getNamespace(effective))) {
            for (RuntimeContext caller :
                    List.of(
                            RuntimeContext.empty(),
                            RuntimeContext.builder().userId("OTHER").sessionId("other").build())) {
                assertTrue(wm.writeMemoryFileUtf8AndConfirm(caller, path, candidate));
                assertEquals(
                        candidate, remote.read(effective, backendPath, 0, 0).fileData().content());
            }
            assertEquals(2, uploads.get());
            assertEquals(2, downloads.get());
            assertNull(
                    store.get(
                            List.of("OTHER"),
                            backendPath.startsWith("/") ? backendPath.substring(1) : backendPath));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "composite", "routed", "host"})
    void actualWorkspaceForConsolidatesOnlyItsBoundUserAndWatermark(String route) throws Exception {
        NamespaceFactory namespace = IsolationScope.USER.toNamespaceFactory();
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, namespace);
        AbstractFilesystem fs =
                route.equals("host")
                        ? new SandboxBackedFilesystem()
                        : route.equals("direct")
                                ? remote
                                : route.equals("composite")
                                        ? new CompositeFilesystem(
                                                remote,
                                                Map.of("MEMORY.md", remote, "memory/", remote))
                                        : new RoutedSandboxFilesystem(
                                                new SandboxBackedFilesystem(),
                                                Map.of("MEMORY.md", remote, "memory/", remote));
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(textChunk("curated A"), finishChunk("stop")),
                                Flux.just(textChunk("curated B"), finishChunk("stop"))));
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("bound-consolidation")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(fs)
                        .disableMemoryHooks()
                        .disableWebTools()
                        .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                        .build()) {
            WorkspaceManager a = agent.workspaceFor("userA", "sessionA");
            WorkspaceManager b = agent.workspaceFor("userB", "sessionB");
            a.writeMemoryFileUtf8(RuntimeContext.empty(), "MEMORY.md", OLD_MEMORY);
            b.writeMemoryFileUtf8(RuntimeContext.empty(), "MEMORY.md", "old B");
            String ledger = "memory/" + LocalDate.now() + ".md";
            a.writeMemoryFileUtf8(RuntimeContext.empty(), ledger, "fresh A");
            b.writeMemoryFileUtf8(RuntimeContext.empty(), ledger, "fresh B");
            MemoryConsolidator ca = new MemoryConsolidator(a, model, 20);
            MemoryConsolidator cb = new MemoryConsolidator(b, model, 20);
            RuntimeContext other =
                    RuntimeContext.builder().userId("OTHER").sessionId("other").build();
            ca.consolidate(RuntimeContext.empty()).block();
            assertEquals("curated A", a.readMemoryMd(other));
            assertEquals("old B", b.readMemoryMd(other));
            assertTrue(ca.readWatermark(other).isAfter(Instant.EPOCH));
            assertEquals(Instant.EPOCH, cb.readWatermark(other));
            cb.consolidate(other).block();
            assertEquals("curated B", b.readMemoryMd(RuntimeContext.empty()));
            assertEquals("curated A", a.readMemoryMd(other));
            assertTrue(cb.readWatermark(RuntimeContext.empty()).isAfter(Instant.EPOCH));
            assertNull(store.get(List.of("OTHER"), "MEMORY.md"));
            assertNull(store.get(List.of("OTHER"), MemoryConsolidator.STATE_REL_PATH));
            if (route.equals("host")) {
                assertEquals("curated A", Files.readString(workspace.resolve("userA/MEMORY.md")));
                assertEquals("curated B", Files.readString(workspace.resolve("userB/MEMORY.md")));
                assertTrue(
                        Files.isRegularFile(
                                workspace.resolve("userA/" + MemoryConsolidator.STATE_REL_PATH)));
                assertTrue(
                        Files.isRegularFile(
                                workspace.resolve("userB/" + MemoryConsolidator.STATE_REL_PATH)));
                assertFalse(Files.exists(workspace.resolve("OTHER")));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> a.resolveRuntimeDataPath(other, "../userB/MEMORY.md"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"outer", "backend"})
    void opaqueBakedOverlayFailsClosedButOrdinaryIoRetainsBakedContext(String placement) {
        RuntimeContext baked = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        NamespaceFactory namespace = rc -> List.of(rc.getUserId());
        AtomicInteger uploads = new AtomicInteger();
        InMemoryStore upperStore = new InMemoryStore();
        RemoteFilesystem upper =
                new RemoteFilesystem(upperStore, namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        assertSame(baked, rc);
                        uploads.incrementAndGet();
                        return List.of(FileUploadResponse.success(files.get(0).getKey()));
                    }
                };
        RemoteFilesystem lower = new RemoteFilesystem(new InMemoryStore(), namespace);
        String candidate = "identical stale fact";
        // Matching bytes in the caller's upper namespace cannot confirm the bound writer.
        seedFile(upperStore, List.of("OTHER"), "/MEMORY.md", candidate);
        seedFile(upperStore, List.of(""), "/MEMORY.md", candidate);
        lower.uploadFiles(
                baked,
                List.of(Map.entry("/MEMORY.md", candidate.getBytes(StandardCharsets.UTF_8))));
        OverlayFilesystem overlay = new OverlayFilesystem(upper, lower);
        AbstractFilesystem fs =
                placement.equals("outer")
                        ? new BakedContextFilesystem(
                                new CompositeFilesystem(lower, Map.of("MEMORY.md", overlay)), baked)
                        : new CompositeFilesystem(
                                lower,
                                Map.of("MEMORY.md", new BakedContextFilesystem(overlay, baked)));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            RuntimeContext empty = RuntimeContext.empty();
            assertEquals(candidate, wm.readMemoryMd(empty));
            assertFalse(wm.writeMemoryFileUtf8AndConfirm(empty, "MEMORY.md", candidate));
            assertFalse(
                    wm.writeMemoryFileUtf8AndConfirm(
                            RuntimeContext.builder().userId("OTHER").build(),
                            "MEMORY.md",
                            candidate));
            assertEquals(2, uploads.get(), "bound uploads run, but stale layers cannot confirm");
            assertNull(upperStore.get(List.of("bob"), "/MEMORY.md"));
            wm.writeMemoryFileUtf8(empty, "MEMORY.md", candidate);
            wm.appendMemoryFileUtf8(empty, "MEMORY.md", " ordinary append");
            assertEquals(4, uploads.get(), "actual IO stays on the bound wrapper");
            assertEquals(candidate, wm.readMemoryMd(empty));
        }
    }

    @Test
    void originalPermissionWrapperReceivesCallerWhileBackendUsesInnerBinding() {
        RuntimeContext caller = RuntimeContext.builder().userId("OTHER").build();
        RuntimeContext bound = RuntimeContext.builder().userId("userA").build();
        RemoteFilesystem remote =
                new RemoteFilesystem(new InMemoryStore(), IsolationScope.USER.toNamespaceFactory());
        AtomicBoolean allowed = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        CompositeFilesystem policy =
                new CompositeFilesystem(
                        remote, Map.of("MEMORY.md", new BakedContextFilesystem(remote, bound))) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        assertSame(
                                caller,
                                rc,
                                "must upload through the original wrapper and original context");
                        attempts.incrementAndGet();
                        return allowed.get()
                                ? super.uploadFiles(rc, files)
                                : List.of(
                                        FileUploadResponse.fail("MEMORY.md", "permission denied"));
                    }
                };
        try (WorkspaceManager wm = new WorkspaceManager(workspace, policy, null)) {
            assertFalse(wm.writeMemoryFileUtf8AndConfirm(caller, "MEMORY.md", "fact"));
            assertFalse(remote.exists(bound, "MEMORY.md"));
            allowed.set(true);
            assertTrue(wm.writeMemoryFileUtf8AndConfirm(caller, "MEMORY.md", "fact"));
            assertArrayEquals(
                    "fact".getBytes(StandardCharsets.UTF_8),
                    remote.downloadFiles(bound, List.of("/MEMORY.md")).get(0).content());
            assertFalse(remote.downloadFiles(caller, List.of("/MEMORY.md")).get(0).isSuccess());
            assertEquals(2, attempts.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "ack-only", "write"})
    void boundProjectWriterDoesNotConfirmMatchingReadLayersOrOtherUser(String outcome)
            throws Exception {
        RuntimeContext bound =
                RuntimeContext.builder().userId("userA").sessionId("sessionA").build();
        RuntimeContext caller = RuntimeContext.builder().userId("OTHER").sessionId("other").build();
        NamespaceFactory namespace = rc -> List.of("runtime", rc.getUserId(), rc.getSessionId());
        Path upperRoot = workspace.resolve("upper");
        Path lowerRoot = workspace.resolve("lower");
        Path projectRoot = workspace.resolve("project");
        String candidate = "bound project fact\r\n中文\n";
        for (Path root : List.of(upperRoot, lowerRoot)) {
            Path stale = root.resolve("runtime/userA/sessionA/src/fact.md");
            Files.createDirectories(stale.getParent());
            Files.writeString(stale, candidate);
        }
        Path otherCopy = projectRoot.resolve("runtime/OTHER/other/src/fact.md");
        Files.createDirectories(otherCopy.getParent());
        Files.writeString(otherCopy, candidate);
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        LocalFilesystem project =
                new LocalFilesystem(projectRoot, true, 10, namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        assertSame(bound, rc);
                        assertEquals("src/fact.md", files.get(0).getKey());
                        uploads.incrementAndGet();
                        return outcome.equals("reject")
                                ? List.of(FileUploadResponse.fail("src/fact.md", "denied"))
                                : outcome.equals("ack-only")
                                        ? List.of(FileUploadResponse.success("src/fact.md"))
                                        : super.uploadFiles(rc, files);
                    }

                    @Override
                    public List<FileDownloadResponse> downloadFiles(
                            RuntimeContext rc, List<String> paths) {
                        assertSame(bound, rc, "do not read the matching OTHER project copy");
                        assertEquals(List.of("src/fact.md"), paths);
                        downloads.incrementAndGet();
                        return super.downloadFiles(rc, paths);
                    }
                };
        LocalFilesystemWithShell upper = new LocalFilesystemWithShell(upperRoot, namespace);
        ProjectAwareOverlay overlay =
                new ProjectAwareOverlay(
                        upper,
                        new LocalFilesystem(lowerRoot, true, 10, namespace),
                        project,
                        upperRoot,
                        namespace);
        BakedContextFilesystem baked = new BakedContextFilesystem(overlay, bound);
        try (WorkspaceManager wm =
                new WorkspaceManager(
                        workspace, baked, null, ignored -> namespace.getNamespace(bound))) {
            assertEquals(candidate, wm.readMemoryFileUtf8(caller, "src/fact.md"));
            assertEquals(
                    outcome.equals("write"),
                    wm.writeMemoryFileUtf8AndConfirm(caller, "src/fact.md", candidate));
            assertEquals(1, uploads.get());
            assertEquals(outcome.equals("reject") ? 0 : 1, downloads.get());
            assertEquals(candidate, Files.readString(otherCopy));
            String absolute = upperRoot.resolve("runtime/userA/sessionA/src/fact.md").toString();
            OverlayFilesystem.UploadDestination target =
                    overlay.uploadDestination(baked.effectiveRuntimeContext(), absolute);
            assertSame(project, target.filesystem());
            assertEquals(
                    "src/fact.md", target.path(), "namespace stripping uses the bound identity");
            assertEquals(
                    outcome.equals("write"),
                    baked.uploadFiles(
                                            caller,
                                            List.of(
                                                    Map.entry(
                                                            absolute,
                                                            candidate.getBytes(
                                                                    StandardCharsets.UTF_8))))
                                    .get(0)
                                    .isSuccess()
                            && project.exists(bound, "src/fact.md"));
        }
    }

    @Test
    void filesystemNullConfirmsHistoricalRootDestinationNotNamespaceFallback() throws Exception {
        RuntimeContext rc = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        String candidate = "first\r\n中文\n";
        try (WorkspaceManager wm =
                new WorkspaceManager(
                        workspace, null, null, context -> List.of(context.getUserId()))) {
            Path namespaced = wm.resolveRuntimeDataPath(rc, "MEMORY.md");
            Files.createDirectories(namespaced.getParent());
            Files.writeString(namespaced, "stale");
            assertTrue(wm.writeMemoryFileUtf8AndConfirm(rc, "/MEMORY.md", candidate));
            assertArrayEquals(
                    candidate.getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(workspace.resolve("MEMORY.md")));
            assertEquals("stale", Files.readString(namespaced));
            assertFalse(wm.writeMemoryFileUtf8AndConfirm(rc, "", candidate));
            assertFalse(wm.writeMemoryFileUtf8AndConfirm(rc, "MEMORY.md", null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> wm.writeMemoryFileUtf8AndConfirm(rc, "../MEMORY.md", candidate));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmationContinuationOffloadsBlockingIoAndRetainsContextAndErrors(boolean fail) {
        List<String> ns = List.of("gate-test");
        InMemoryStore store = new InMemoryStore();
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "fresh fact");
        RuntimeContext caller = RuntimeContext.builder().userId("bob").sessionId("shared").build();
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        List<String> order = new CopyOnWriteArrayList<>();
        RemoteFilesystem fs =
                new RemoteFilesystem(store, ns) {
                    private void check(RuntimeContext rc) {
                        assertSame(caller, rc);
                        assertFalse(Schedulers.isInNonBlockingThread());
                        assertTrue(Thread.currentThread().getName().startsWith("boundedElastic-"));
                    }

                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        check(rc);
                        order.add(
                                files.get(0).getKey().equals("MEMORY.md")
                                        ? "body-write"
                                        : "watermark");
                        return super.uploadFiles(rc, files);
                    }

                    @Override
                    public List<FileDownloadResponse> downloadFiles(
                            RuntimeContext rc, List<String> paths) {
                        check(rc);
                        order.add("confirm");
                        if (fail) {
                            throw new IllegalStateException("confirmation failed");
                        }
                        return super.downloadFiles(rc, paths);
                    }
                };
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.deferContextual(
                                                context -> {
                                                    seen.set(context.get("caller"));
                                                    return Flux.just(
                                                            textChunk(
                                                                    "# Curated\n- fresh fact one"),
                                                            finishChunk("stop"));
                                                })
                                        .publishOn(Schedulers.parallel())
                                        .doOnComplete(
                                                () ->
                                                        assertTrue(
                                                                Schedulers
                                                                        .isInNonBlockingThread()))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs, null)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, model, 20);
            Mono<Void> run =
                    consolidator
                            .consolidate(caller)
                            .then(
                                    Mono.deferContextual(
                                            context -> {
                                                assertSame(caller, context.get("caller"));
                                                return Mono.<Void>empty();
                                            }))
                            .contextWrite(context -> context.put("caller", caller));
            if (fail) {
                StepVerifier.create(run).expectErrorMessage("confirmation failed").verify();
                assertEquals(List.of("body-write", "confirm"), order);
                assertEquals(Instant.EPOCH, consolidator.readWatermark(caller));
            } else {
                StepVerifier.create(run).verifyComplete();
                assertEquals(List.of("body-write", "confirm", "watermark"), order);
                assertTrue(consolidator.readWatermark(caller).isAfter(Instant.EPOCH));
            }
            assertSame(caller, seen.get());
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
    // The legacy model call carries exactly the two consolidation messages,
    // with null tools and options.
    // ======================================================================

    @Test
    void legacyModelCall_carriesOnlyConsolidationMessages() throws Exception {
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
                                        .publishOn(Schedulers.parallel())
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

    // ======================================================================
    // Non-text content is never a MEMORY.md body. A completed answer that also
    // carries media, data, hint, or tool blocks must leave the file and both
    // watermark copies untouched, and the same ledger stays eligible.
    // ======================================================================

    private static TextBlock textBlock(String text) {
        return TextBlock.builder().text(text).build();
    }

    private static ContentBlock nonTextBlock(String variant) {
        return switch (variant) {
            case "image", "split-before", "split-after", "metadata-after", "thinking-then-image" ->
                    ImageBlock.builder()
                            .source(new Base64Source("image/png", "iVBORw0KGgo="))
                            .build();
            case "audio" ->
                    AudioBlock.builder().source(new Base64Source("audio/mpeg", "AAAA")).build();
            case "video" ->
                    VideoBlock.builder().source(new Base64Source("video/mp4", "AAAA")).build();
            case "data" ->
                    DataBlock.builder()
                            .source(new Base64Source("application/json", "e30="))
                            .build();
            case "hint" -> new HintBlock("hint-1", "consider one fact");
            case "tool-use" ->
                    ToolUseBlock.builder()
                            .id("call-1")
                            .name("save_memory")
                            .input(Map.of("k", "v"))
                            .build();
            case "tool-result" ->
                    ToolResultBlock.builder()
                            .id("call-1")
                            .name("save_memory")
                            .output(List.of(textBlock("saved")))
                            .build();
            default -> null; // null-entry places a null element in the content list
        };
    }

    private static Flux<ChatResponse> nonTextStream(
            String variant, String body, ContentBlock nonText) {
        return switch (variant) {
            case "image", "audio", "video", "data", "hint", "tool-use", "tool-result" ->
                    Flux.just(
                            ChatResponse.builder()
                                    .id("mixed")
                                    .content(List.of(textBlock(body), nonText))
                                    .build(),
                            finishChunk("stop"));
            case "thinking-then-image" ->
                    Flux.just(
                            ChatResponse.builder()
                                    .id("thinking-image")
                                    .content(
                                            List.of(
                                                    ThinkingBlock.builder()
                                                            .thinking("private reasoning")
                                                            .build(),
                                                    nonText,
                                                    textBlock(body)))
                                    .build(),
                            finishChunk("stop"));
            case "null-entry" ->
                    Flux.just(
                            ChatResponse.builder()
                                    .id("null-entry")
                                    .content(Arrays.asList(textBlock(body), null))
                                    .build(),
                            finishChunk("stop"));
            case "split-before" ->
                    Flux.just(
                            ChatResponse.builder().id("nontext").content(List.of(nonText)).build(),
                            textChunk(body),
                            finishChunk("stop"));
            case "split-after" ->
                    Flux.just(
                            textChunk(body),
                            ChatResponse.builder().id("nontext").content(List.of(nonText)).build(),
                            finishChunk("stop"));
            case "metadata-after" ->
                    Flux.just(
                            ChatResponse.builder()
                                    .id("mixed")
                                    .content(List.of(textBlock(body), nonText))
                                    .build(),
                            ChatResponse.builder()
                                    .id("metadata")
                                    .metadata(
                                            Map.of(
                                                    "openai.response.incomplete_reason",
                                                    "max_output_tokens"))
                                    .build(),
                            finishChunk("stop"));
            default -> throw new AssertionError(variant);
        };
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "image",
                "audio",
                "video",
                "data",
                "hint",
                "tool-use",
                "tool-result",
                "null-entry",
                "split-before",
                "split-after",
                "metadata-after",
                "thinking-then-image"
            })
    void nonTextContentCannotReplaceBodyOrAdvanceEitherWatermark(String variant) {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("nontext-gate");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        String candidate = "# Curated\n- fresh fact one";
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                nonTextStream(variant, candidate, nonTextBlock(variant)),
                                Flux.just(textChunk(candidate), finishChunk("stop"))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new RemoteFilesystem(store, ns))) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();
            consolidator.consolidate(rc).block();
            assertEquals(OLD_MEMORY, wm.readMemoryMd(rc), variant);
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc), variant);
            assertEquals("", wm.readMemoryFileUtf8(rc, MemoryConsolidator.STATE_REL_PATH), variant);
            assertNull(store.get(List.of("memory", "consolidation"), "watermark"), variant);
            // The rejected run consumed nothing: the same ledger remains eligible.
            consolidator.consolidate(rc).block();
            assertEquals(2, model.inputs.size());
            assertTrue(
                    model.inputs.get(1).get(1).getTextContent().contains("- fresh fact one"),
                    variant);
            assertEquals(candidate, wm.readMemoryMd(rc), variant);
            assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH), variant);
            assertNotNull(store.get(List.of("memory", "consolidation"), "watermark"), variant);
        }
    }

    @Test
    void reasoningBlocksAreIgnoredWhileTextBodyStillCommits() {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("reasoning-gate");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        String candidate = "# Curated\n- fresh fact one";
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        ChatResponse.builder()
                                                .id("thinking")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking("first look")
                                                                        .build()))
                                                .build(),
                                        ChatResponse.builder()
                                                .id("mixed")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking("second look")
                                                                        .build(),
                                                                textBlock(candidate)))
                                                .build(),
                                        finishChunk("stop"))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new RemoteFilesystem(store, ns))) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();
            consolidator.consolidate(rc).block();
            assertEquals(candidate, wm.readMemoryMd(rc), "reasoning must not enter the body");
            assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));
            assertNotNull(store.get(List.of("memory", "consolidation"), "watermark"));
        }
    }

    @Test
    void reasoningOnlyOutputIsStillAnEmptyBody() {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("reasoning-empty");
        seedFile(store, ns, "MEMORY.md", OLD_MEMORY);
        seedDailyLedger(store, ns, "- fresh fact one");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                Flux.just(
                                        ChatResponse.builder()
                                                .id("thinking")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking("only reasoning")
                                                                        .build()))
                                                .build(),
                                        finishChunk("stop"))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new RemoteFilesystem(store, ns))) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wm, model, MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, 20, store);
            RuntimeContext rc = RuntimeContext.empty();
            consolidator.consolidate(rc).block();
            assertEquals(OLD_MEMORY, wm.readMemoryMd(rc));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(rc));
            assertEquals("", wm.readMemoryFileUtf8(rc, MemoryConsolidator.STATE_REL_PATH));
            assertNull(store.get(List.of("memory", "consolidation"), "watermark"));
        }
    }
}
