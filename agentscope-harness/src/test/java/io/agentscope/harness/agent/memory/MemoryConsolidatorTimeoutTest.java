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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Verifies that {@link MemoryConsolidator#consolidate} does not hang forever when the model
 * provider never responds. Without a timeout, this would tie up a shared {@code boundedElastic}
 * worker thread indefinitely (see {@link io.agentscope.harness.agent.middleware
 * .MemoryMaintenanceMiddleware}, which runs consolidation on that scheduler).
 */
class MemoryConsolidatorTimeoutTest {

    @Test
    void consolidate_timesOutInsteadOfHangingForever(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);

        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            // Seed a fresh daily entry so consolidate() doesn't short-circuit with Mono.empty()
            // before ever reaching the model call.
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "memory/2025-06-15.md", "Some daily notes");

            Model hungModel = mock(Model.class);
            when(hungModel.stream(anyList(), any(), any())).thenReturn(Flux.<ChatResponse>never());

            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, hungModel);

            StepVerifier.withVirtualTime(() -> consolidator.consolidate(RuntimeContext.empty()))
                    .thenAwait(MemoryConsolidator.CONSOLIDATION_TIMEOUT.plus(Duration.ofSeconds(1)))
                    .expectError(TimeoutException.class)
                    .verify(Duration.ofSeconds(10));
        }
    }
}
