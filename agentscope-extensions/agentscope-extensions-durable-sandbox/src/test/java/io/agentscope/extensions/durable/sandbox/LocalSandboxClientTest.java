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
package io.agentscope.extensions.durable.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.Executor;
import io.agentscope.durable.ToolBinding;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import io.agentscope.harness.agent.sandbox.layout.LocalFileEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSandboxClientTest {
    @TempDir Path directory;

    @Test
    void durableReplacementReusesOriginalDirectoryAndCleanupWaitsForBarrier() throws Exception {
        Path root = directory.resolve("sandboxes");
        var workspace = new WorkspaceSpec();
        workspace.getEntries().put("seed.txt", new FileEntry("original"));
        workspace.getEnvironment().put("DURABLE_LOCAL_TEST", "env");
        var binding =
                new SandboxBindings.Binding(
                        "tenant", "workspace", "v1", DurableJson.value(Map.of()));
        var tool =
                new ToolBinding(
                        "shell",
                        "v1",
                        "hands",
                        DurableJson.value(
                                Map.of("name", "shell", "parameters", Map.of("type", "object"))),
                        DurableJson.value(Map.of("operation", "sandbox.exec")));
        var request =
                new ExecutionRequest(
                        "effect",
                        new ThreadKey(new SessionKey("tenant", "session"), "main"),
                        "turn",
                        tool.call(
                                "call",
                                DurableJson.value(
                                        Map.of(
                                                "command",
                                                "sleep 0.2; cat seed.txt; printf '%s'"
                                                        + " \"$DURABLE_LOCAL_TEST\"; printf once >>"
                                                        + " effect"))));
        String original;
        try (var records = JdbcRecordStore.sqlite(directory.resolve("local.db"))) {
            var client = new LocalSandboxClient(root);
            try (var first =
                    SandboxBindings.builder()
                            .stateStore(records)
                            .resolver(ignored -> client.target(workspace))
                            .build()) {
                assertEquals(
                        ExecutionState.RUNNING,
                        first.executor(binding, tool).startExecution(request).state());
                original = first.status(binding).resourceId();
            }
            // Rebuilding the host does not reapply even changed initial files/environment.
            workspace.getEntries().put("seed.txt", new FileEntry("replacement"));
            workspace.getEnvironment().put("DURABLE_LOCAL_TEST", "changed");
            var replacementClient = new LocalSandboxClient(root);
            try (var replacement =
                    SandboxBindings.builder()
                            .stateStore(records)
                            .resolver(ignored -> replacementClient.target(workspace))
                            .canDelete(ignored -> true)
                            .build()) {
                Executor executor = replacement.executor(binding, tool);
                var result = terminal(executor, request);
                assertEquals(ExecutionState.SUCCEEDED, result.state());
                assertEquals("originalenv", result.result().path("output").asText());
                assertEquals(original, replacement.status(binding).resourceId());
                executor.startExecution(request);
                assertEquals("once", Files.readString(root.resolve(original).resolve("effect")));
                assertTrue(executor.output(request, 0).terminal());
                assertEquals(
                        "originalenv",
                        new String(Base64.getDecoder().decode(executor.output(request, 0).data())));
                replacement.requestDeletion(binding);
                replacement.tick();
                assertTrue(replacement.status(binding).deleted());
                assertFalse(Files.exists(root.resolve(original)));
            }
        }
    }

    @Test
    void resumeAndStartNeverRecreateMissingWorkspaceAndRootCannotChange() throws Exception {
        var client = new LocalSandboxClient(directory.resolve("one"));
        var sandbox = client.create(new WorkspaceSpec(), null, null);
        sandbox.start();
        String state = client.serializeState(sandbox.getState());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSandboxClient(directory.resolve("two")).deserializeState(state));
        var resumed = client.resume(client.deserializeState(state));
        assertEquals(client.identity(sandbox), client.identity(resumed));
        assertTrue(resumed.exec(null, "printf ready", 2).ok());
        client.delete(sandbox);
        client.delete(sandbox);
        assertThrows(IOException.class, resumed::start);
        assertThrows(IOException.class, () -> resumed.exec(null, "printf forbidden", 2));
        assertFalse(Files.exists(directory.resolve("one").resolve(client.identity(sandbox))));
    }

    @Test
    void initialLayoutIsFrozenAndInvalidOrPrivatePathsAreRejected() throws Exception {
        var client = new LocalSandboxClient(directory.resolve("layout"));
        var workspace = new WorkspaceSpec();
        var file = new FileEntry("before");
        workspace.getEntries().put("nested", new DirEntry(Map.of("value", file)));
        var sandbox = client.create(workspace, null, null);
        file.setContent("after");
        try (sandbox) {
            sandbox.start();
            assertEquals("before", sandbox.exec(null, "cat nested/value", 2).stdout());
            for (String name :
                    new String[] {
                        "../outside", ".durable-executions/forged", ".durable-sandbox-identity"
                    }) {
                var invalid = new WorkspaceSpec();
                invalid.getEntries().put(name, new FileEntry("forbidden"));
                assertThrows(
                        IllegalArgumentException.class, () -> client.create(invalid, null, null));
            }
            var nested = new WorkspaceSpec();
            nested.getEntries()
                    .put("dir", new DirEntry(Map.of("../../outside", new FileEntry("forbidden"))));
            assertThrows(IllegalArgumentException.class, () -> client.create(nested, null, null));
            var unsupported = new WorkspaceSpec();
            unsupported.getEntries().put("host", new LocalFileEntry());
            assertThrows(
                    IllegalArgumentException.class, () -> client.create(unsupported, null, null));
            file.setEphemeral(true);
            assertThrows(
                    IllegalArgumentException.class, () -> client.create(workspace, null, null));
        }
        assertFalse(Files.exists(directory.resolve("outside")));
    }

    @Test
    void oversizedControlResponseIsBoundedAndMarkedTruncated() throws Exception {
        var client = new LocalSandboxClient(directory.resolve("output"));
        try (var sandbox = client.create(new WorkspaceSpec(), null, null)) {
            sandbox.start();
            var result =
                    sandbox.exec(
                            null, "python3 -c 'import sys; sys.stderr.write(\"x\" * 100000)'", 5);
            assertTrue(result.truncated());
            assertEquals(64 * 1024, result.stderr().length());
        }
    }

    private static ExecutionResult terminal(Executor executor, ExecutionRequest request)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var result = executor.query(request, null);
            if (result.state().terminal()) return result;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        throw new AssertionError("Local execution did not finish");
    }
}
