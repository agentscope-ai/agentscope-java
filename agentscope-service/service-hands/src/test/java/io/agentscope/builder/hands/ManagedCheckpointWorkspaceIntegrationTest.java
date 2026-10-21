/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.InMemoryManagedLeaseStore;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedCheckpointLoop;
import io.agentscope.builder.runtime.managed.ManagedCheckpointObjects;
import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual local processes and persistent DB intents; Redis and the cloud provider are explicit fakes. */
@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(60)
class ManagedCheckpointWorkspaceIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path root;

    @Test
    void lostImportStartResponseSurvivesProviderReplacementWithoutOverwritingTheFork()
            throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:workspace-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var origin = new ManagedScope("t", "n", "origin");
        var target = new ManagedScope("t", "n", "target");
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(origin, "{}");
        admission.createSession(target, "{\"owner_id\":\"owner\"}");
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        var operations = new ManagedCheckpointOperations(source, mapper, journal);
        var catalog = mock(ManagedCatalog.class);
        var resolved =
                new SessionResolveResult(
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        Map.of("AGENTS.md", "pinned definition"),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null);
        when(catalog.resolve(origin)).thenReturn(withSession(resolved, origin));
        when(catalog.resolve(target)).thenReturn(withSession(resolved, target));
        var redis = mock(RedisManagedInfrastructure.class);
        var crypto = mock(VaultCrypto.class);
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        var starts = new AtomicInteger();
        var lost = new AtomicBoolean();
        byte[] bytes = new byte[128 * 1024];
        new Random(42).nextBytes(bytes);
        var provider =
                new ManagedSandboxProviders(
                        catalog,
                        mapper,
                        crypto,
                        redis,
                        source,
                        new BuilderE2bProperties(),
                        16,
                        true,
                        root.toString());
        var initial =
                new ToolTask(
                        "seed",
                        null,
                        "seed",
                        "seed",
                        "fs.upload",
                        mapper.writeValueAsString(
                                Map.of(
                                        "path",
                                        "nested/data.bin",
                                        "content",
                                        Base64.getEncoder().encodeToString(bytes))),
                        0,
                        "pending",
                        null,
                        null,
                        null);
        try {
            var executor = provider.executor(origin);
            executor.start(origin, initial);
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!executor.query(origin, initial).terminal()) {
                assertTrue(System.nanoTime() < until);
                Thread.sleep(20);
            }
            Files.delete(root.resolve(origin.messageGroup()).resolve("AGENTS.md"));
            String turn = admission.acceptTurn(origin, "actor", "seed", "{}").id();
            admission.reservePublication(origin, turn);
            admission.confirmPublication(origin, turn, "broker");
            admission.activate(origin, turn, 1, "token");
            admission.closeInputs(origin, turn, 1);
            var event =
                    new SessionEvent(
                            1,
                            "checkpoint",
                            1,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            "run",
                            turn,
                            true,
                            mapper.writeValueAsString(
                                    Map.of(
                                            "stateJson",
                                            AgentState.builder().build().toJson(),
                                            "projectorVersion",
                                            1)));
            journal.commit(
                    journal.objects()
                            .prepare(
                                    new ManagedRuntimeBatch(
                                            origin,
                                            turn,
                                            1,
                                            "token",
                                            "writer",
                                            "checkpoint",
                                            0,
                                            List.of(event),
                                            List.of(),
                                            List.of(),
                                            "succeeded")));
            new JdbcTemplate(source)
                    .update(
                            "INSERT INTO"
                                + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status,result,result_hash)"
                                + " VALUES(?,'seed',?,'seed','seed','fs.upload','{}',0,'succeeded','{}','seed')",
                            origin.messageGroup(),
                            turn);
            String checkpoint =
                    (String)
                            ((Map<?, ?>)
                                            ((List<?>)
                                                            journal.checkpoints()
                                                                    .list(origin, null, null, 10)
                                                                    .get("data"))
                                                    .get(0))
                                    .get("checkpoint_id");
            var receipt = operations.prepare(origin, target, "actor", "fork", checkpoint);
            var command =
                    new ManagedCheckpointOperations.Operation(
                            origin, target, receipt.id(), "actor");
            var current = new AtomicReference<>(provider);
            try (var pipeline =
                            new ManagedHandsTestPipeline(
                                    source,
                                    mapper,
                                    journal,
                                    catalog,
                                    leases,
                                    scope -> {
                                        var attached = current.get().executor(scope);
                                        return new ManagedToolExecutor() {
                                            public String start(ManagedScope owner, ToolTask task) {
                                                String handle = attached.start(owner, task);
                                                if (task.name().equals("workspace.restore")) {
                                                    starts.incrementAndGet();
                                                    lost.set(true);
                                                    throw new ManagedTransportException(
                                                            "Injected lost start response");
                                                }
                                                return handle;
                                            }

                                            public Outcome query(
                                                    ManagedScope owner, ToolTask task) {
                                                return attached.query(owner, task);
                                            }

                                            public Outcome cancel(
                                                    ManagedScope owner, ToolTask task) {
                                                return attached.cancel(owner, task);
                                            }
                                        };
                                    },
                                    root.resolve("private-blobs"));
                    var worker =
                            new ManagedCheckpointLoop(
                                    operations,
                                    leases,
                                    pipeline.administrative::executor,
                                    ignored -> "owner",
                                    1,
                                    Duration.ofSeconds(60),
                                    Duration.ofSeconds(15),
                                    (ignored, failure) -> fail(failure))) {
                until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!lost.get()) {
                    pipeline.check();
                    worker.execute(command);
                    assertTrue(System.nanoTime() < until);
                    Thread.sleep(20);
                }
                assertEquals("restoring", operations.get(origin, receipt.id()).status());
                assertEquals("started", operations.workspaceTask(origin, receipt.id()).status());
                provider.close();
                var replacement =
                        new ManagedSandboxProviders(
                                catalog,
                                mapper,
                                crypto,
                                redis,
                                source,
                                new BuilderE2bProperties(),
                                16,
                                true,
                                root.toString());
                current.set(replacement);
                try {
                    until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (!operations.get(origin, receipt.id()).status().equals("completed")) {
                        pipeline.check();
                        worker.execute(command);
                        assertTrue(System.nanoTime() < until);
                        Thread.sleep(20);
                    }
                    assertEquals(1, starts.get());
                    Path fork = root.resolve(target.messageGroup());
                    assertArrayEquals(bytes, Files.readAllBytes(fork.resolve("nested/data.bin")));
                    assertFalse(Files.exists(fork.resolve("AGENTS.md")));
                    Files.writeString(
                            root.resolve(origin.messageGroup()).resolve("nested/data.bin"),
                            "source changed");
                    assertArrayEquals(bytes, Files.readAllBytes(fork.resolve("nested/data.bin")));
                    assertEquals(1, journal.head(target).sequence());
                    assertEquals(
                            "accepted",
                            admission.acceptTurn(target, "actor", "next", "{}").status());
                } finally {
                    replacement.close();
                }
            }
        } finally {
            provider.close();
        }
    }

    private SessionResolveResult withSession(SessionResolveResult resolved, ManagedScope scope) {
        return new SessionResolveResult(
                new ManagedSessionDto(
                        scope.sessionId(),
                        "owner",
                        "agent",
                        "owner",
                        1,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "created",
                        null,
                        1,
                        1,
                        null),
                resolved.agentSnapshot(),
                resolved.workspacePath(),
                resolved.workspaceId(),
                resolved.workspaceVersion(),
                resolved.definitionFiles(),
                resolved.environment(),
                resolved.vaultCredentials(),
                resolved.memoryMounts(),
                resolved.teamContext(),
                resolved.executionContext());
    }
}
