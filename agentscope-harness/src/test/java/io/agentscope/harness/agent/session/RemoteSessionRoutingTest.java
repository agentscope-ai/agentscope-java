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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RemoteSessionRoutingTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @EnumSource(IsolationScope.class)
    void sessionLogSurvivesWorkspaceReplacement(IsolationScope scope) {
        var backend = new InMemoryStore();
        var rc = RuntimeContext.builder().userId("u").sessionId("session").build();
        var key = new SessionKey("u", "agent", "session");
        var spec = new RemoteFilesystemSpec(backend).isolationScope(scope);
        var store =
                new WorkspaceSessionLogStore(
                        spec.toFilesystem(
                                workspace.resolve("first"), "agent", context -> List.of()));
        var event = new SessionEvent(1, "e", 1, 1, "run/start", "r", "t", true, "{}");
        var log = store.open(key, rc);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        log.commit(writer, "batch", 0, List.of(event));
        log.release(writer);

        var namespace =
                switch (scope) {
                    case USER -> List.of("agents", "agent", "users", "u", "sessions");
                    case SESSION -> List.of("agents", "agent", "sessions", "session", "sessions");
                    case AGENT -> List.of("agents", "agent", "shared", "sessions");
                    case GLOBAL -> List.of("global", "sessions");
                };
        var remote = new WorkspaceSessionLogStore(new RemoteFilesystem(backend, namespace));
        assertEquals(List.of(event), remote.open(key, rc).readAfter(0, 10));
        var restored =
                new WorkspaceSessionLogStore(
                        spec.toFilesystem(
                                workspace.resolve("second"), "agent", context -> List.of()));
        assertEquals(List.of(event), restored.open(key, rc).readAfter(0, 10));
        assertEquals(List.of(key), restored.list(rc));
        assertFalse(Files.exists(workspace.resolve("first/.agentscope-runtime")));
        assertFalse(Files.exists(workspace.resolve("second/.agentscope-runtime")));
    }

    @Test
    void userScopedLogsRemainIsolated() {
        var spec =
                new RemoteFilesystemSpec(new InMemoryStore()).isolationScope(IsolationScope.USER);
        var store =
                new WorkspaceSessionLogStore(
                        spec.toFilesystem(workspace, "agent", context -> List.of()));
        var alice = RuntimeContext.builder().userId("alice").build();
        var bob = RuntimeContext.builder().userId("bob").build();
        var key = new SessionKey("alice", "agent", "session");
        var log = store.open(key, alice);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        log.commit(
                writer,
                "batch",
                0,
                List.of(new SessionEvent(1, "e", 1, 1, "run/start", "r", "t", true, "{}")));
        log.release(writer);
        assertEquals(0, store.open(key, bob).head().seq());
        assertEquals(List.of(), store.list(bob));
        assertEquals(List.of(key), store.list(alice));
    }
}
