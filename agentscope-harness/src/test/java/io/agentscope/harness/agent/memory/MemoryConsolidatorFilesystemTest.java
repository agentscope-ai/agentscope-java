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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryConsolidator} reads daily ledgers and writes watermark / MEMORY.md
 * through the filesystem layer.
 */
class MemoryConsolidatorFilesystemTest {

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

    @Test
    void readWatermark_returnsEpochWhenStateAbsent(@TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        WorkspaceManager wsm = new WorkspaceManager(tmp, fs);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        assertEquals(Instant.EPOCH, consolidator.readWatermark());
    }

    @Test
    void watermark_roundTripThroughFilesystem(@TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        WorkspaceManager wsm = new WorkspaceManager(tmp, fs);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        Instant ts = Instant.parse("2025-06-15T12:00:00Z");
        wsm.writeUtf8WorkspaceRelative(MemoryConsolidator.STATE_REL_PATH, ts.toString());

        assertEquals(ts, consolidator.readWatermark());
    }

    @Test
    void watermark_doesNotCreateLocalFile(@TempDir Path tmp) {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        WorkspaceManager wsm = new WorkspaceManager(tmp, fs);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        Instant ts = Instant.now();
        wsm.writeUtf8WorkspaceRelative(MemoryConsolidator.STATE_REL_PATH, ts.toString());

        Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
        assertFalse(
                Files.exists(localState),
                "state file should not be written to local disk when using RemoteFilesystem");

        assertEquals(ts, consolidator.readWatermark());
    }

    @Test
    void stateFileRelPath_matchesConstant() {
        assertEquals("memory/" + MemoryConsolidator.STATE_FILE, MemoryConsolidator.STATE_REL_PATH);
    }

    @Test
    void consolidate_readsRootDailyLedgerAndWritesMemoryMd(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        WorkspaceManager wsm = new WorkspaceManager(tmp, fs);

        Path memoryDir = Files.createDirectories(tmp.resolve("memory"));
        Files.writeString(memoryDir.resolve("2026-05-20.md"), "root daily entry");

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, stubModel("updated memory"));

        consolidator.consolidate().block();

        assertEquals("updated memory", wsm.readMemoryMd());
        assertTrue(consolidator.readWatermark().isAfter(Instant.EPOCH));
    }

    @Test
    void watermark_localFallback_whenNoFilesystem(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        assertEquals(Instant.EPOCH, consolidator.readWatermark());

        Instant ts = Instant.parse("2025-03-10T09:00:00Z");
        wsm.writeUtf8WorkspaceRelative(MemoryConsolidator.STATE_REL_PATH, ts.toString());

        assertEquals(ts, consolidator.readWatermark());

        Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
        assertTrue(
                Files.exists(localState),
                "state file should be written to local disk when no filesystem is configured");
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
