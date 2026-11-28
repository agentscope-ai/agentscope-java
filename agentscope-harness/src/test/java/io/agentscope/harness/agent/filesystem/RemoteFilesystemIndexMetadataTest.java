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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Regression tests for issue #2692: {@link RemoteFilesystem#ls} and {@link RemoteFilesystem#glob}
 * must propagate {@code size_bytes} and {@code modified_at} from {@link WorkspaceIndex} on the
 * index fast path so downstream consumers such as {@link MemoryConsolidator}'s watermark filter do
 * not treat indexed files as having an empty modification timestamp.
 */
class RemoteFilesystemIndexMetadataTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @TempDir Path workspaceRoot;

    private void insertSqliteIndexRow(String path, long sizeBytes, String modifiedAt)
            throws Exception {
        Path dbFile = workspaceRoot.resolve(".index").resolve("workspace.db");
        try (Connection conn =
                        DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
                PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT OR REPLACE INTO files(path, size_bytes, modified_at,"
                                        + " present_local) VALUES(?,?,?,1)")) {
            ps.setString(1, path);
            ps.setLong(2, sizeBytes);
            ps.setString(3, modifiedAt);
            ps.executeUpdate();
        }
    }

    @Test
    void ls_indexFastPathPropagatesSizeAndModifiedAt() {
        InMemoryStore emptyStore = new InMemoryStore();
        try (WorkspaceIndex index = WorkspaceIndex.open(workspaceRoot)) {
            assertNotNull(index);
            String mtime = "2026-02-10T08:30:00Z";
            index.upsert("memory/2026-02-10.md", 142L, mtime);
            index.upsert("memory/archive/2026-01-01.md", 88L, "2026-01-01T00:00:00Z");

            RemoteFilesystem fs = new RemoteFilesystem(emptyStore, List.of("ns")).withIndex(index);

            LsResult result = fs.ls(CTX, "memory");
            assertTrue(result.isSuccess());
            assertEquals(2, result.entries().size());

            FileInfo file =
                    result.entries().stream()
                            .filter(e -> !e.isDirectory())
                            .findFirst()
                            .orElseThrow();
            assertEquals("memory/2026-02-10.md", file.path());
            assertEquals(142L, file.size());
            assertEquals(mtime, file.modifiedAt());

            FileInfo subdir =
                    result.entries().stream()
                            .filter(FileInfo::isDirectory)
                            .findFirst()
                            .orElseThrow();
            assertEquals("memory/archive/", subdir.path());
        }
    }

    @Test
    void glob_indexFastPathPropagatesSizeAndModifiedAt() throws Exception {
        InMemoryStore emptyStore = new InMemoryStore();
        try (WorkspaceIndex index = WorkspaceIndex.open(workspaceRoot)) {
            assertNotNull(index);
            String mtime = "2026-02-11T09:15:30Z";
            insertSqliteIndexRow("/memory/2026-02-11.md", 256L, mtime);

            RemoteFilesystem fs = new RemoteFilesystem(emptyStore, List.of("ns")).withIndex(index);

            GlobResult result = fs.glob(CTX, "*.md", "memory");
            assertTrue(result.isSuccess());
            assertEquals(1, result.matches().size());

            FileInfo match = result.matches().get(0);
            assertEquals("/memory/2026-02-11.md", match.path());
            assertFalse(match.isDirectory());
            assertEquals(256L, match.size());
            assertEquals(mtime, match.modifiedAt());
        }
    }

    @Test
    void listFileEntriesByPrefix_returnsStoredSizeAndModifiedAt() {
        try (WorkspaceIndex index = WorkspaceIndex.open(workspaceRoot)) {
            assertNotNull(index);
            String mtime = "2026-02-01T12:00:00Z";
            index.upsert("memory/2026-02-01.md", 64L, mtime);
            index.upsert("memory/unknown-size.md", -1L, mtime);

            List<WorkspaceIndex.FileEntry> entries = index.listFileEntriesByPrefix("memory/");
            assertEquals(2, entries.size());

            RemoteFilesystem fs =
                    new RemoteFilesystem(new InMemoryStore(), List.of("ns")).withIndex(index);
            LsResult ls = fs.ls(CTX, "memory");
            assertTrue(ls.isSuccess());
            Map<String, FileInfo> byPath =
                    ls.entries().stream()
                            .collect(java.util.stream.Collectors.toMap(FileInfo::path, e -> e));
            assertEquals(64L, byPath.get("memory/2026-02-01.md").size());
            assertEquals(mtime, byPath.get("memory/2026-02-01.md").modifiedAt());
            assertEquals(
                    0L,
                    byPath.get("memory/unknown-size.md").size(),
                    "unknown (-1) indexed size must clamp to 0 in FileInfo");
        }
    }

    @Test
    void memoryConsolidator_skipsUnmodifiedIndexedLedgerAfterWatermarkAdvances() throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("consolidate-ns");
        String ledgerContent = "- user prefers dark mode";
        Instant ledgerMtime = Instant.parse("2026-01-15T10:00:00Z");

        store.put(
                ns,
                "MEMORY.md",
                Map.of(
                        "content",
                        "# Initial Memory",
                        "encoding",
                        "utf-8",
                        "modified_at",
                        ledgerMtime.toString()));
        store.put(
                ns,
                "memory/2026-01-15.md",
                Map.of(
                        "content",
                        ledgerContent,
                        "encoding",
                        "utf-8",
                        "modified_at",
                        ledgerMtime.toString()));

        AtomicInteger modelCalls = new AtomicInteger();
        Model countingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int n = modelCalls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("chunk-" + n)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "# Consolidated v"
                                                                                + n
                                                                                + "\n"
                                                                                + ledgerContent)
                                                                .build()))
                                        .finishReason("stop")
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "counting-consolidation-model";
                    }
                };

        try (WorkspaceIndex index = WorkspaceIndex.open(workspaceRoot)) {
            assertNotNull(index);
            // Seed the SQLite index row matched by RemoteFilesystem.glob(rc, "*.md", "memory").
            insertSqliteIndexRow(
                    "/memory/2026-01-15.md", ledgerContent.length(), ledgerMtime.toString());

            RemoteFilesystem fs = new RemoteFilesystem(store, ns).withIndex(index);
            try (WorkspaceManager wsm = new WorkspaceManager(workspaceRoot, fs, index)) {
                MemoryConsolidator consolidator = new MemoryConsolidator(wsm, countingModel, 50);

                // First run: watermark is EPOCH, so the indexed ledger is consolidated and
                // watermark advances to runStart (> ledgerMtime).
                consolidator.consolidate(CTX).block();
                assertEquals(1, modelCalls.get(), "first consolidation must invoke the model");
                String rawWatermark =
                        wsm.readMemoryFileUtf8(CTX, "memory/" + MemoryConsolidator.STATE_FILE);
                assertNotNull(rawWatermark);
                assertTrue(Instant.parse(rawWatermark.strip()).isAfter(ledgerMtime));

                // Second run: the indexed ledger's modifiedAt is before the saved watermark, so
                // isModifiedAfter must return false and skip the LLM call.
                consolidator.consolidate(CTX).block();
                assertEquals(
                        1,
                        modelCalls.get(),
                        "unmodified indexed ledger must not be re-consolidated after watermark"
                                + " advances");
            }
        }
    }
}
