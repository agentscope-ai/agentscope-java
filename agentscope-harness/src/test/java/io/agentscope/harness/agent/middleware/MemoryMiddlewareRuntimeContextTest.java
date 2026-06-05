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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class MemoryMiddlewareRuntimeContextTest {

    @Test
    void memoryFlushUsesStreamEventsRuntimeContextForRemoteFilesystemNamespace(
            @TempDir Path workspace) throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store).toFilesystem(workspace, "agent-a", rc -> List.of());

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace, fs)) {
            Model model = stubModel("- remember alice");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent-a")
                            .model(model)
                            .toolkit(new Toolkit())
                            .middleware(new MemoryFlushMiddleware(workspaceManager, model))
                            .build();

            RuntimeContext runtimeContext =
                    RuntimeContext.builder().userId("alice").sessionId("session-1").build();

            List<AgentEvent> events =
                    agent.streamEvents(List.of(userText("remember alice")), runtimeContext)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertTrue(events != null && !events.isEmpty(), "agent event stream should complete");

            String todayPath = "/" + LocalDate.now() + ".md";
            List<String> aliceNamespace = List.of("agents", "agent-a", "users", "alice", "memory");
            List<String> defaultNamespace =
                    List.of("agents", "agent-a", "users", "_default", "memory");

            assertTrue(
                    waitUntil(() -> store.get(aliceNamespace, todayPath) != null),
                    "memory flush should write the daily ledger under the caller user namespace");
            assertNull(
                    store.get(defaultNamespace, todayPath),
                    "memory flush must not fall back to the anonymous _default namespace");
        }
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
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

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
