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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryConsolidator} reads daily ledgers and writes watermark / MEMORY.md
 * entirely through {@link io.agentscope.harness.agent.filesystem.AbstractFilesystem}, making it
 * backend-agnostic.
 */
class MemoryConsolidatorFilesystemTest {

    @ParameterizedTest
    @CsvSource({"false, USER", "true, USER", "false, SESSION", "true, SESSION"})
    void consolidationOfOneScopeDoesNotSkipAnotherScopesLedger(
            boolean sharedWatermarkStore, IsolationScope scope, @TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory namespaces = scope.toNamespaceFactory();
        RemoteFilesystem fs = new RemoteFilesystem(store, namespaces);
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("a").build();
        RuntimeContext bob =
                RuntimeContext.builder()
                        .userId(scope == IsolationScope.SESSION ? "alice" : "bob")
                        .sessionId("b")
                        .build();
        Instant ledgerTime = Instant.now().minusSeconds(3600);
        seedStoreFile(
                store,
                namespaces.getNamespace(alice),
                "memory/2026-10-11.md",
                "Alice fact",
                ledgerTime);
        seedStoreFile(
                store,
                namespaces.getNamespace(bob),
                "memory/2026-10-11.md",
                "Bob fact",
                ledgerTime);
        seedStoreFile(
                store, namespaces.getNamespace(bob), "MEMORY.md", "previous Bob fact", ledgerTime);
        Model model = mock(Model.class);
        when(model.stream(anyList(), isNull(), isNull()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("curated")
                                                                .build()))
                                        .build()));

        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs, null, namespaces)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm, model, null, 4000, sharedWatermarkStore ? store : null);
            consolidator.consolidate(alice).block();
            assertEquals("curated", wsm.readMemoryMd(alice));
            assertEquals("previous Bob fact", wsm.readMemoryMd(bob));

            consolidator.consolidate(bob).block();
            assertEquals("curated", wsm.readMemoryMd(bob));
            consolidator.consolidate(alice).block();
            consolidator.consolidate(bob).block();
            verify(model, times(2)).stream(anyList(), isNull(), isNull());
            if (sharedWatermarkStore) {
                for (RuntimeContext rc : List.of(alice, bob)) {
                    var watermarkNamespace =
                            new ArrayList<String>(List.of("memory", "consolidation", "scopes"));
                    watermarkNamespace.addAll(namespaces.getNamespace(rc));
                    assertTrue(store.get(watermarkNamespace, "watermark") != null);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(IsolationScope.class)
    void watermarkUsesConfiguredScopeAndPreservesFileFallback(
            IsolationScope scope, @TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory namespaces = scope.toNamespaceFactory();
        RemoteFilesystem fs =
                new RemoteFilesystem(
                        store,
                        rc -> {
                            var ns = new ArrayList<String>(List.of("workspace"));
                            ns.addAll(namespaces.getNamespace(rc));
                            return ns;
                        });
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("a").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("b").build();
        Instant legacy = Instant.parse("2026-10-10T12:00:00Z");
        Instant scopedFile = legacy.minusSeconds(3600);
        store.put(
                List.of("memory", "consolidation"),
                "watermark",
                Map.of("ts", legacy.toEpochMilli()));
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs, null, namespaces)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null, null, 4000, store);
            boolean isolated = scope == IsolationScope.USER || scope == IsolationScope.SESSION;
            assertEquals(isolated ? Instant.EPOCH : legacy, consolidator.readWatermark(alice));
            wsm.writeUtf8WorkspaceRelative(
                    alice, MemoryConsolidator.STATE_REL_PATH, scopedFile.toString());
            assertEquals(isolated ? scopedFile : legacy, consolidator.readWatermark(alice));
            assertEquals(isolated ? Instant.EPOCH : legacy, consolidator.readWatermark(bob));
        }
    }

    @Test
    void watermarkWithoutNamespaceFactoryReadsLegacyStoreSlot(@TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        Instant legacy = Instant.parse("2026-10-10T12:00:00Z");
        store.put(
                List.of("memory", "consolidation"),
                "watermark",
                Map.of("ts", legacy.toEpochMilli()));
        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("workspace"));
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs, null, null)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null, null, 4000, store);
            assertEquals(legacy, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    @Test
    void watermarkNormalizesNullRuntimeContextBeforeResolvingNamespace(@TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        Instant scoped = Instant.parse("2026-10-10T12:00:00Z");
        NamespaceFactory namespaces =
                rc -> {
                    assertNotNull(rc);
                    assertNull(rc.getUserId());
                    assertNull(rc.getSessionId());
                    return List.of("anonymous");
                };
        store.put(
                List.of("memory", "consolidation", "scopes", "anonymous"),
                "watermark",
                Map.of("ts", scoped.toEpochMilli()));
        RemoteFilesystem fs = new RemoteFilesystem(store, namespaces);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs, null, namespaces)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null, null, 4000, store);
            assertEquals(scoped, consolidator.readWatermark(null));
        }
    }

    @Test
    void watermarkRetriesCasConflictWithinTheSameScope(@TempDir Path tmp) {
        List<String> watermarkNamespace = List.of("memory", "consolidation", "scopes", "alice");
        Instant old = Instant.now().minusSeconds(7200);
        List<Long> expectedVersions = new ArrayList<>();
        InMemoryStore store =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> namespace,
                            String key,
                            Map<String, Object> value,
                            long expectedVersion) {
                        assertEquals(watermarkNamespace, namespace);
                        assertEquals("watermark", key);
                        expectedVersions.add(expectedVersion);
                        if (expectedVersions.size() == 1) {
                            // Another replica advances the version between the read and CAS.
                            super.put(
                                    namespace,
                                    key,
                                    Map.of("ts", old.plusSeconds(1).toEpochMilli()));
                        }
                        return super.putIfVersion(namespace, key, value, expectedVersion);
                    }
                };
        store.put(watermarkNamespace, "watermark", Map.of("ts", old.toEpochMilli()));
        NamespaceFactory namespaces = IsolationScope.USER.toNamespaceFactory();
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RemoteFilesystem fs = new RemoteFilesystem(store, namespaces);
        seedStoreFile(
                store,
                namespaces.getNamespace(alice),
                "memory/2026-10-11.md",
                "Alice fact",
                old.plusSeconds(3600));
        Model model = mock(Model.class);
        when(model.stream(anyList(), isNull(), isNull()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("curated")
                                                                .build()))
                                        .build()));
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs, null, namespaces)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, model, null, 4000, store);
            Instant before = Instant.now();
            consolidator.consolidate(alice).block();

            assertEquals("curated", wsm.readMemoryMd(alice));
            assertEquals(List.of(1L, 2L), expectedVersions);
            assertEquals(3L, store.get(watermarkNamespace, "watermark").version());
            Instant fileWatermark =
                    Instant.parse(
                            wsm.readManagedWorkspaceFileUtf8(
                                    alice, MemoryConsolidator.STATE_REL_PATH));
            assertFalse(fileWatermark.isBefore(before));
            assertEquals(
                    fileWatermark.toEpochMilli(), consolidator.readWatermark(alice).toEpochMilli());
            assertNull(store.get(List.of("memory", "consolidation"), "watermark"));
            assertEquals(
                    Instant.EPOCH,
                    consolidator.readWatermark(RuntimeContext.builder().userId("bob").build()));
            verify(model, times(1)).stream(anyList(), isNull(), isNull());
        }
    }

    private static void seedStoreFile(
            InMemoryStore store, List<String> ns, String path, String content, Instant modifiedAt) {
        Map<String, Object> value =
                Map.of(
                        "content",
                        content,
                        "encoding",
                        "utf-8",
                        "modified_at",
                        modifiedAt.toString());
        store.put(ns, path, value);
    }

    // ======================================================================
    // readWatermark: returns EPOCH when state file absent
    // ======================================================================

    @Test
    void readWatermark_returnsEpochWhenStateAbsent(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark / writeWatermark round-trip through filesystem
    // ======================================================================

    @Test
    void watermark_roundTripThroughFilesystem(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.parse("2025-06-15T12:00:00Z");
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark: no local file is touched — only the filesystem
    // ======================================================================

    @Test
    void watermark_doesNotCreateLocalFile(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.now();
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            // local disk must NOT have the state file — it lives only in the store
            Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
            assertFalse(
                    Files.exists(localState),
                    "state file should not be written to local disk when using RemoteFilesystem");

            // but consolidator reads it correctly from the store
            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // STATE_FILE constant is preserved
    // ======================================================================

    @Test
    void stateFileRelPath_matchesConstant() {
        assertEquals("memory/" + MemoryConsolidator.STATE_FILE, MemoryConsolidator.STATE_REL_PATH);
    }

    // ======================================================================
    // Local filesystem (no store) — watermark uses local disk via WorkspaceManager
    // ======================================================================

    @Test
    void watermark_localFallback_whenNoFilesystem(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        // No file → EPOCH
        assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));

        // Write via WorkspaceManager (falls to local disk)
        Instant ts = Instant.parse("2025-03-10T09:00:00Z");
        wsm.writeUtf8WorkspaceRelative(
                RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

        assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));

        // Verify the local file actually exists
        Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
        assertTrue(
                Files.exists(localState),
                "state file should be written to local disk when no filesystem is configured");
    }
}
