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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class MemoryConsolidatorGlobRegressionTest {

    @Test
    void consolidate_readsRootDailyLedgerAndWritesMemoryMd(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            Path memoryDir = Files.createDirectories(tmp.resolve("memory"));
            Files.writeString(memoryDir.resolve("2026-05-20.md"), "root daily entry");

            MemoryConsolidator consolidator =
                    new MemoryConsolidator(wsm, stubModel("updated memory"));

            consolidator.consolidate(RuntimeContext.empty()).block();

            assertEquals("updated memory", wsm.readMemoryMd(RuntimeContext.empty()));
            assertTrue(consolidator.readWatermark(RuntimeContext.empty()).isAfter(Instant.EPOCH));
        }
    }

    @Test
    void consolidate_readsSandboxDailyLedgerFromVirtualGlobPath(@TempDir Path tmp)
            throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp, true, 10);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            Path memoryDir = Files.createDirectories(tmp.resolve("memory"));
            Files.writeString(memoryDir.resolve("2026-05-20.md"), "sandbox daily entry");

            MemoryConsolidator consolidator =
                    new MemoryConsolidator(wsm, stubModel("updated sandbox memory"));

            consolidator.consolidate(RuntimeContext.empty()).block();

            assertEquals("updated sandbox memory", wsm.readMemoryMd(RuntimeContext.empty()));
            assertTrue(consolidator.readWatermark(RuntimeContext.empty()).isAfter(Instant.EPOCH));
        }
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
