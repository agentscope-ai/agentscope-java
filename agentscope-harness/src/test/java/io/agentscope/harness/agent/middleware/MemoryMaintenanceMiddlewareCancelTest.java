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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end verification of the memory maintenance connection-leak fix. This targets the exact
 * production symptom (a {@code consolidate} model call started after {@code streamEvents}
 * completed, whose HTTP connection stayed open until the JVM exited): the consolidation step now
 * lives inside the reactive chain controlled by the registered {@link
 * reactor.core.Disposable}, so {@link MemoryBackgroundTasks#cancelAll()} cancels the underlying
 * model subscription instead of leaving a blocked {@code .block()} behind.
 */
class MemoryMaintenanceMiddlewareCancelTest {

    @Test
    void hungConsolidationIsReleasedByCancelAll() throws Exception {
        WorkspaceManager wsMgr = mock(WorkspaceManager.class);
        // filesystem null => expire/prune are no-ops, so only the (overridden) consolidation runs.
        when(wsMgr.getFilesystem()).thenReturn(null);

        Model unusedModel = mock(Model.class);
        MemoryConsolidator consolidator =
                new MemoryConsolidator(wsMgr, unusedModel) {
                    @Override
                    public Mono<Void> consolidate(RuntimeContext rc) {
                        // Simulates a model streaming call that never completes (hung connection).
                        return Mono.never();
                    }
                };

        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(wsMgr, consolidator);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("remember: deploys happen on Fridays")
                        .build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            middleware
                    .onAgent(null, rc, new AgentInput(List.of(userMsg)), in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));

            // begin() is incremented synchronously in onAgent.doOnComplete, so the task is
            // in-flight.
            assertFalse(
                    MemoryBackgroundTasks.awaitQuiescence(1, TimeUnit.SECONDS),
                    "without cancel, the hung consolidation must keep in-flight > 0 (proves the"
                            + " leak existed)");

            MemoryBackgroundTasks.cancelAll();

            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "after cancelAll the hung consolidation must quiesce (no leaked connection)");
        } finally {
            MemoryBackgroundTasks.cancelAll();
            MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS);
        }
    }
}
