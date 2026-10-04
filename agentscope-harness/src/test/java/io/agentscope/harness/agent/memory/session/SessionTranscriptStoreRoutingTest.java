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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for #2918: the default {@link ObjectStoreTranscriptStore} segment keys must
 * match the routes {@link RemoteFilesystemSpec} registers ({@code agents/{agentId}/sessions/}),
 * otherwise session history silently lands on the default backend (local disk) instead of the
 * distributed store.
 */
class SessionTranscriptStoreRoutingTest {

    @TempDir Path workspace;

    private static Msg message(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    void objectStoreTranscriptKeysHitTheSessionsRoute() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem sessionsRoute =
                new RemoteFilesystem(
                        store, List.of("agents", "agent-a", "users", "user-1", "sessions"));
        LocalFilesystem defaultBackend =
                new LocalFilesystem(workspace, false, 10, rc -> List.of("u1"));
        AbstractFilesystem composite =
                new CompositeFilesystem(
                        defaultBackend, Map.of("agents/agent-a/sessions/", sessionsRoute));

        try (WorkspaceManager wm = new WorkspaceManager(workspace, composite)) {
            ObjectStoreTranscriptStore transcriptStore = new ObjectStoreTranscriptStore(composite);
            SessionTranscriptWriter writer =
                    new SessionTranscriptWriter(wm, transcriptStore, "default");
            writer.appendMessages(
                    RuntimeContext.builder().userId("user-1").sessionId("session-1").build(),
                    List.of(message("m1", "hello"), message("m2", "world")),
                    "agent-a",
                    "session-1");
        }

        // The segment must be reachable through the routed store namespace — the SQL-level
        // check from the issue (LIKE '%sessions%') at the store API level.
        boolean foundInRoute =
                store
                        .search(List.of("agents", "agent-a", "users", "user-1", "sessions"), 50, 0)
                        .stream()
                        .anyMatch(item -> item.key().contains("/events/"));
        assertTrue(foundInRoute, "segment must land in the routed store namespace");

        // And it must NOT have leaked to the default backend's namespace directory.
        assertFalse(
                java.nio.file.Files.exists(workspace.resolve("u1/default/agent-a")),
                "segment must not fall through to the namespaced default backend");
    }
}
