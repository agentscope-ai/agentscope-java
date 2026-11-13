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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.ExecutionRecovery;
import io.agentscope.durable.Executor;
import io.agentscope.durable.ToolBinding;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxBindingsTest {
    @TempDir Path directory;

    final SandboxBindings.Binding binding =
            new SandboxBindings.Binding(
                    "tenant", "workspace", "v1", DurableJson.value(Map.of("provider", "fixture")));
    final ToolBinding tool =
            new ToolBinding(
                    "shell",
                    "v1",
                    "hands",
                    DurableJson.value(
                            Map.of("name", "shell", "parameters", Map.of("type", "object"))),
                    DurableJson.value(Map.of("operation", "sandbox.exec")));

    ExecutionRequest request(String id, String command) {
        return new ExecutionRequest(
                id,
                new ThreadKey(new SessionKey("tenant", "session"), "main"),
                "turn",
                tool.call(
                                "call-" + id,
                                DurableJson.value(
                                        Map.of("command", command, "timeout_seconds", 10)))
                        .withOrigin(new RunStepRef("brain-run", "brain-step")));
    }

    SandboxBindings manager(JdbcRecordStore records, Fixture fixture, AtomicBoolean settled) {
        return SandboxBindings.builder()
                .stateStore(records)
                .resolver(ignored -> fixture.target())
                .canDelete(ignored -> settled.get())
                .cacheCapacity(1)
                .keepaliveInterval(Duration.ofMillis(10))
                .retryInterval(Duration.ofMillis(10))
                .protect(
                        value ->
                                "sealed:"
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        value.getBytes(StandardCharsets.UTF_8)),
                        value ->
                                new String(
                                        Base64.getDecoder().decode(value.substring(7)),
                                        StandardCharsets.UTF_8))
                .onError(failure -> {})
                .build();
    }

    ExecutionResult terminal(Executor executor, ExecutionRequest request) throws Exception {
        for (int i = 0; i < 200; i++) {
            var result = executor.query(request, null);
            if (result.state().terminal()) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Sandbox operation did not terminate");
    }

    @Test
    void targetResolutionFailureCanRetryWithoutRecordingAnExternalCreation() throws Exception {
        var fixture = new Fixture();
        var unavailable = new AtomicBoolean(true);
        try (var records = JdbcRecordStore.sqlite(directory.resolve("resolution.db"));
                var manager =
                        SandboxBindings.builder()
                                .stateStore(records)
                                .resolver(
                                        ignored -> {
                                            if (unavailable.getAndSet(false))
                                                throw new IllegalStateException(
                                                        "Credentials unavailable");
                                            return fixture.target();
                                        })
                                .build()) {
            var executor = manager.executor(binding, tool);
            var request = request("resolved", "printf once >> resolved");
            assertThrows(IllegalStateException.class, () -> executor.startExecution(request));
            assertNull(manager.status(binding));
            assertEquals(0, fixture.creations.get());
            executor.startExecution(request);
            assertEquals(ExecutionState.SUCCEEDED, terminal(executor, request).state());
            assertEquals(1, fixture.creations.get());
            assertEquals("once", Files.readString(fixture.last.root().resolve("resolved")));
            var other =
                    new ExecutionRequest(
                            "other",
                            new ThreadKey(new SessionKey("other-tenant", "session"), "main"),
                            request.turnId(),
                            request.call());
            assertThrows(SecurityException.class, () -> executor.validateStart(other));
            assertThrows(SecurityException.class, () -> executor.query(other, null));
        }
    }

    @Test
    void replacementAndCacheEvictionReattachOriginalResourcesWithoutStartingOrClosingThem()
            throws Exception {
        var fixture = new Fixture();
        var settled = new AtomicBoolean();
        var first = request("first", "printf once >> effects; printf output");
        try (var records = JdbcRecordStore.sqlite(directory.resolve("ledger.db"))) {
            try (var manager = manager(records, fixture, settled)) {
                var executor = manager.executor(binding, tool);
                assertThrows(DurableException.class, () -> executor.query(first, null));
                assertEquals(0, fixture.creations.get());
                executor.startExecution(first);
                assertEquals(ExecutionState.SUCCEEDED, terminal(executor, first).state());
                var other =
                        new SandboxBindings.Binding(
                                "tenant", "other", "v1", binding.configuration());
                var second = request("second", "printf second");
                var otherExecutor = manager.executor(other, tool);
                otherExecutor.startExecution(second);
                assertEquals(ExecutionState.SUCCEEDED, terminal(otherExecutor, second).state());
                assertEquals(1, manager.cachedBindings());
                assertEquals(ExecutionState.SUCCEEDED, executor.query(first, null).state());
                assertEquals(2, fixture.creations.get());
                assertEquals(2, fixture.starts.get());
                assertTrue(fixture.attachments.get() >= 1);
                var state = records.transaction(binding.key(), tx -> tx.get(""));
                assertTrue(state.path("sealed").asText().startsWith("sealed:"));
                assertFalse(state.has("directory"));
            }
            assertEquals(0, fixture.closes.get());
            assertEquals(0, fixture.destroys.get());
            try (var replacement = manager(records, fixture, settled)) {
                var executor = replacement.executor(binding, tool);
                assertEquals(ExecutionState.SUCCEEDED, executor.query(first, null).state());
                var page = executor.output(first, 0);
                assertEquals(
                        "output",
                        new String(
                                Base64.getDecoder().decode(page.data()), StandardCharsets.UTF_8));
                assertEquals(
                        "once", Files.readString(fixture.root("resource-1").resolve("effects")));
                assertEquals(2, fixture.starts.get());
            }
        }
    }

    @Test
    void concurrentCreationAdmitsOnlyOnePhysicalInstance() throws Exception {
        var fixture = new Fixture();
        fixture.starting = new CountDownLatch(1);
        fixture.proceed = new CountDownLatch(1);
        var thread = Executors.newSingleThreadExecutor();
        try (var records = JdbcRecordStore.sqlite(directory.resolve("creation.db"));
                var one = manager(records, fixture, new AtomicBoolean());
                var two = manager(records, fixture, new AtomicBoolean())) {
            var request = request("concurrent", "printf ok");
            var pending = thread.submit(() -> one.executor(binding, tool).startExecution(request));
            assertTrue(fixture.starting.await(2, TimeUnit.SECONDS));
            assertThrows(
                    DurableException.class,
                    () -> two.executor(binding, tool).startExecution(request));
            assertEquals(1, fixture.creations.get());
            fixture.proceed.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(
                    ExecutionState.SUCCEEDED,
                    terminal(two.executor(binding, tool), request).state());
            assertEquals(1, fixture.starts.get());
        } finally {
            fixture.proceed.countDown();
            thread.shutdownNow();
        }
    }

    @Test
    void lostCreationReplyRequiresOriginalEvidenceAndNeverCreatesAReplacement() throws Exception {
        var fixture = new Fixture();
        fixture.loseCreation = true;
        var request = request("uncertain", "printf never");
        try (var records = JdbcRecordStore.sqlite(directory.resolve("uncertain.db"));
                var manager = manager(records, fixture, new AtomicBoolean())) {
            var executor = manager.executor(binding, tool);
            assertThrows(DurableException.class, () -> executor.startExecution(request));
            var state = manager.status(binding);
            assertNotNull(state.creationId());
            assertFalse(state.ready());
            assertThrows(DurableException.class, () -> executor.startExecution(request));
            assertThrows(DurableException.class, () -> executor.query(request, null));
            assertEquals(1, fixture.creations.get());
            var original = fixture.last;
            Files.writeString(
                    original.root().resolve(".durable-sandbox-identity"), state.creationId());
            String serialized = fixture.serializeState(original.getState());
            manager.reconcileCreation(
                    binding,
                    state.creationId(),
                    original.state.id,
                    serialized,
                    "operator-evidence");
            manager.reconcileCreation(
                    binding,
                    state.creationId(),
                    original.state.id,
                    serialized,
                    "operator-evidence");
            assertEquals("operator-evidence", manager.status(binding).reconciliationEvidence());
            assertUnconfirmedWithoutReplay(executor, request);
            assertThrows(
                    DurableException.class,
                    () ->
                            manager.reconcileCreation(
                                    binding,
                                    state.creationId(),
                                    original.state.id,
                                    serialized,
                                    "different-evidence"));
            assertEquals(1, fixture.starts.get());
        }
    }

    @Test
    void lostMarkerReplyRetainsAttachmentStateAndMissingEvidenceNeverReconstructsWorkspace()
            throws Exception {
        var fixture = new Fixture();
        fixture.loseMarker = true;
        var request = request("marker", "printf never");
        try (var records = JdbcRecordStore.sqlite(directory.resolve("marker.db"))) {
            try (var first = manager(records, fixture, new AtomicBoolean())) {
                assertThrows(
                        DurableException.class,
                        () -> first.executor(binding, tool).startExecution(request));
                assertNotNull(first.status(binding).resourceId());
                assertFalse(first.status(binding).ready());
            }
            try (var replacement = manager(records, fixture, new AtomicBoolean())) {
                var executor = replacement.executor(binding, tool);
                assertUnconfirmedWithoutReplay(executor, request);
                assertTrue(replacement.status(binding).ready());
                Files.delete(fixture.last.root().resolve(".durable-sandbox-identity"));
                assertThrows(DurableException.class, () -> executor.query(request, null));
                assertThrows(DurableException.class, () -> executor.startExecution(request));
                assertEquals(1, fixture.creations.get());
                assertEquals(1, fixture.starts.get());
            }
        }
    }

    private void assertUnconfirmedWithoutReplay(Executor executor, ExecutionRequest request) {
        // The provider reports missing evidence. Durable must not interpret this as permission to
        // start a replacement operation after an attempted dispatch.
        assertEquals(ExecutionState.NOT_FOUND, executor.query(request, null).state());
        assertEquals(
                ExecutionState.UNKNOWN,
                ExecutionRecovery.resolve(
                                executor,
                                request,
                                ExecutionResult.unknown("Lost start response"),
                                true,
                                false)
                        .state());
        // Query again: an accidental retry would have admitted a job and changed this observation.
        assertEquals(ExecutionState.NOT_FOUND, executor.query(request, null).state());
    }

    @Test
    void deletionIsPersistentTerminalGatedAndIdempotentAfterLostDestroyReply() throws Exception {
        var fixture = new Fixture();
        var settled = new AtomicBoolean();
        var request = request("cleanup", "printf done");
        try (var records = JdbcRecordStore.sqlite(directory.resolve("cleanup.db"))) {
            try (var first = manager(records, fixture, settled)) {
                var executor = first.executor(binding, tool);
                executor.startExecution(request);
                assertEquals(ExecutionState.SUCCEEDED, terminal(executor, request).state());
                first.requestDeletion(binding);
                assertThrows(
                        DurableException.class,
                        () -> executor.startExecution(request("new", "printf forbidden")));
                first.tick();
                assertFalse(first.status(binding).deleted());
                assertEquals(0, fixture.destroys.get());
                assertEquals(ExecutionState.SUCCEEDED, executor.cancel(request, null).state());
            }
            fixture.loseDestroy = true;
            settled.set(true);
            try (var replacement = manager(records, fixture, settled)) {
                Thread.sleep(15);
                replacement.tick();
                assertFalse(replacement.status(binding).deleted());
                assertEquals(1, fixture.destroys.get());
                Thread.sleep(15);
                replacement.tick();
                assertTrue(replacement.status(binding).deleted());
                replacement.tick();
                assertEquals(2, fixture.destroys.get());
                assertEquals(1, fixture.physicalDeletes.get());
                assertEquals(1, fixture.creations.get());
                assertThrows(
                        DurableException.class,
                        () -> replacement.executor(binding, tool).query(request, null));
            }
        }
    }

    @Test
    void cleanupClaimPreventsConcurrentDestroyAndUnusedDeletionNeverCreatesResources()
            throws Exception {
        var fixture = new Fixture();
        var settled = new AtomicBoolean(true);
        var request = request("cleanup-race", "printf done");
        var thread = Executors.newSingleThreadExecutor();
        try (var records = JdbcRecordStore.sqlite(directory.resolve("cleanup-race.db"));
                var one = manager(records, fixture, settled);
                var two = manager(records, fixture, settled)) {
            one.executor(binding, tool).startExecution(request);
            terminal(one.executor(binding, tool), request);
            fixture.destroying = new CountDownLatch(1);
            fixture.destroyProceed = new CountDownLatch(1);
            one.requestDeletion(binding);
            var pending = thread.submit(one::tick);
            assertTrue(fixture.destroying.await(2, TimeUnit.SECONDS));
            two.tick();
            assertEquals(1, fixture.destroys.get());
            fixture.destroyProceed.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertTrue(two.status(binding).deleted());
            var unused =
                    new SandboxBindings.Binding("tenant", "unused", "v1", binding.configuration());
            two.requestDeletion(unused);
            two.tick();
            assertTrue(two.status(unused).deleted());
            assertThrows(
                    DurableException.class,
                    () -> two.executor(unused, tool).startExecution(request));
            assertEquals(1, fixture.creations.get());
        } finally {
            if (fixture.destroyProceed != null) fixture.destroyProceed.countDown();
            thread.shutdownNow();
        }
    }

    class Fixture implements SandboxClient<SandboxClientOptions> {
        final AtomicInteger creations = new AtomicInteger(),
                starts = new AtomicInteger(),
                attachments = new AtomicInteger(),
                closes = new AtomicInteger(),
                destroys = new AtomicInteger(),
                physicalDeletes = new AtomicInteger(),
                keepalives = new AtomicInteger();
        volatile boolean loseCreation, loseMarker, loseDestroy;
        volatile CountDownLatch starting, proceed, destroying, destroyProceed;
        volatile Local last;

        Path root(String id) {
            return directory.resolve(id);
        }

        SandboxBindings.Target target() {
            return SandboxBindings.Target.using(
                            this,
                            new WorkspaceSpec(),
                            null,
                            sandbox -> ((State) sandbox.getState()).id)
                    .withKeepAlive(sandbox -> keepalives.incrementAndGet());
        }

        public Sandbox create(
                WorkspaceSpec workspace,
                SandboxSnapshotSpec snapshot,
                SandboxClientOptions options) {
            last = new Local(new State("resource-" + creations.incrementAndGet()));
            if (loseCreation) {
                loseCreation = false;
                try {
                    last.start();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                throw new IllegalStateException("creation committed, response lost");
            }
            return last;
        }

        public Sandbox resume(SandboxState state) {
            attachments.incrementAndGet();
            return new Local((State) state);
        }

        public void delete(Sandbox sandbox) {
            throw new AssertionError("Use confirmed shutdown");
        }

        public String serializeState(SandboxState state) {
            return DurableJson.value(Map.of("id", ((State) state).id)).toString();
        }

        public SandboxState deserializeState(String json) {
            return new State(DurableJson.parse(json).required("id").asText());
        }

        class State extends SandboxState {
            final String id;

            State(String id) {
                this.id = id;
            }
        }

        class Local implements Sandbox {
            final State state;

            Local(State state) {
                this.state = state;
            }

            Path root() {
                return Fixture.this.root(state.id);
            }

            public void start() throws Exception {
                starts.incrementAndGet();
                if (starting != null) starting.countDown();
                if (proceed != null && !proceed.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("fixture blocked");
                Files.createDirectories(root());
            }

            public void stop() {
                throw new AssertionError("Do not stop a borrowed environment");
            }

            public void close() {
                closes.incrementAndGet();
            }

            public boolean isRunning() {
                return Files.isDirectory(root());
            }

            public SandboxState getState() {
                return state;
            }

            public ExecResult exec(RuntimeContext context, String command, Integer timeout)
                    throws Exception {
                if (!Files.isDirectory(root()))
                    return new ExecResult(1, "", "missing workspace", false);
                Path output = Files.createTempFile(directory, "control", ".out");
                try {
                    var process =
                            new ProcessBuilder("sh", "-c", command)
                                    .directory(root().toFile())
                                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                                    .redirectOutput(output.toFile())
                                    .start();
                    if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new IllegalStateException("fixture control timeout");
                    }
                    var result =
                            new ExecResult(
                                    process.exitValue(), Files.readString(output), "", false);
                    if (command.startsWith("printf") && loseMarker) {
                        loseMarker = false;
                        throw new IllegalStateException("marker saved, response lost");
                    }
                    return result;
                } finally {
                    Files.deleteIfExists(output);
                }
            }

            public void shutdown() throws Exception {
                destroys.incrementAndGet();
                if (destroying != null) destroying.countDown();
                if (destroyProceed != null && !destroyProceed.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("fixture blocked");
                if (Files.exists(root())) {
                    try (var files = Files.walk(root())) {
                        for (var file : files.sorted(Comparator.reverseOrder()).toList())
                            Files.delete(file);
                    }
                    physicalDeletes.incrementAndGet();
                }
                if (loseDestroy) {
                    loseDestroy = false;
                    throw new IllegalStateException("destroy committed, response lost");
                }
            }

            public InputStream persistWorkspace() {
                throw new UnsupportedOperationException();
            }

            public void hydrateWorkspace(InputStream source) {
                throw new UnsupportedOperationException();
            }
        }
    }
}
