/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * A run that dies in a way the store must still account for: a JVM-level {@link Error} escaping the
 * reactive chain, and a control-plane write that fails before the run is dispatched.
 */
class AgentProtocolTaskStoreFailureHandlingTest {

    @TempDir Path tempDir;

    @Test
    @Timeout(15)
    void errorFromTheAgentFailsTheTaskAndReleasesItsLease() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>error(new StackOverflowError("boom")));
        ProtocolTaskRunRegistry runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        AgentProtocolTaskStore store =
                store(agent, new WorkspaceProtocolTaskRepository(tempDir), runRegistry);

        store.submit("t-error", "worker", "go");

        Map<String, Object> snapshot = store.waitFor("t-error", 5_000);
        assertEquals("error", snapshot.get("status"));
        assertTrue(
                String.valueOf(snapshot.get("error")).contains("boom"),
                "the failure reason must reach the client, got " + snapshot.get("error"));
        assertTrue(runRegistry.find("t-error").isEmpty(), "the lease must be released");
        assertThrows(
                IllegalStateException.class,
                () -> store.submit("t-error", "worker", "retry after failure"),
                "a terminal task must not be re-submittable, or the error hides a second run");
    }

    @Test
    @Timeout(15)
    void submitThatCannotPersistLeavesNoSubmitContextOrLease() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ProtocolTaskRunRegistry runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        ProtocolTaskRepository failingRepository =
                new ProtocolTaskRepository() {
                    @Override
                    public void save(TaskRecord record) {
                        throw new IllegalStateException("control plane unavailable");
                    }

                    @Override
                    public Optional<TaskRecord> find(String taskId) {
                        return Optional.empty();
                    }
                };
        AgentProtocolTaskStore store = store(agent, failingRepository, runRegistry);

        assertThrows(
                IllegalStateException.class, () -> store.submit("t-unpersisted", "worker", "go"));

        assertFalse(
                store.hasSubmitContext("t-unpersisted"),
                "a run that never started must not leave submission metadata behind");
        assertTrue(
                runRegistry.find("t-unpersisted").isEmpty(),
                "a run that never started must not keep its lease");
    }

    @Test
    @Timeout(15)
    void orphanedRunIsSettledAsOwnerLostOnRead() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        // A plain map repository: the workspace one refreshes lastUpdatedAt on every write, which
        // would erase the staleness this test is about.
        ProtocolTaskRepository repository = new MapRepository();
        repository.save(runningRecord("t-orphan", Instant.now().minusSeconds(120)));
        repository.save(runningRecord("t-fresh", Instant.now()));
        AgentProtocolTaskStore store =
                store(agent, repository, BaseStoreProtocolTaskRunRegistry.inMemory());

        Map<String, Object> orphan = store.snapshot("t-orphan");
        assertEquals("error", orphan.get("status"), "a lease-less run must not read as running");
        assertEquals(AgentProtocolTaskStore.OWNER_LOST_ERROR, orphan.get("error"));
        assertEquals(
                TaskStatus.FAILED,
                repository.find("t-orphan").orElseThrow().getStatus(),
                "the settlement must be persisted, not only reported");

        assertEquals(
                "running",
                store.snapshot("t-fresh").get("status"),
                "a record inside the lease grace window must be left alone");
    }

    @Test
    @Timeout(15)
    void settlingAnOrphanedRunDropsItsSubmitContext() {
        HarnessAgent agent = mock(HarnessAgent.class);
        ProtocolTaskRepository repository = new MapRepository();
        repository.save(runningRecord("t-orphan", Instant.now().minusSeconds(120)));
        ProtocolTaskContextStore contextStore = BaseStoreProtocolTaskContextStore.inMemory();
        contextStore.save(
                "t-orphan", ProtocolTaskContext.from(Map.of("user_id", "u1", "detail", "full")));
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        repository,
                        new AgentProtocolTaskEventBus(),
                        new AgentProtocolProperties(),
                        List.of(),
                        BaseStoreProtocolTaskRunRegistry.inMemory(),
                        contextStore);

        assertEquals("error", store.snapshot("t-orphan").get("status"));

        assertFalse(
                contextStore.find("t-orphan").isPresent(),
                "a record settled by the read-side repair must not keep its submission metadata");
    }

    @Test
    @Timeout(20)
    void runStopsWhenItsLeaseIsTakenOver() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.never());
        ProtocolTaskRunRegistry delegate = BaseStoreProtocolTaskRunRegistry.inMemory();
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setRunLeaseTtlMs(1_000L);
        properties.setRunHeartbeatIntervalMs(250L);
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        new WorkspaceProtocolTaskRepository(tempDir),
                        new AgentProtocolTaskEventBus(),
                        properties,
                        List.of(),
                        new LosingLeaseRegistry(delegate),
                        BaseStoreProtocolTaskContextStore.inMemory());

        store.submit("t-lost", "worker", "go");

        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> snapshot = store.snapshot("t-lost");
        while (!"error".equals(snapshot.get("status")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            snapshot = store.snapshot("t-lost");
        }

        assertEquals(
                "error",
                snapshot.get("status"),
                "a run that lost its lease must stop and be settled, not stay running");
        assertEquals(AgentProtocolTaskStore.OWNER_LOST_ERROR, snapshot.get("error"));
    }

    private static TaskRecord runningRecord(String taskId, Instant lastUpdatedAt) {
        TaskRecord record = new TaskRecord(taskId, "worker", "parent", "parent-session", null);
        record.setStatus(TaskStatus.RUNNING);
        record.setLastUpdatedAt(lastUpdatedAt);
        return record;
    }

    private static AgentProtocolTaskStore store(
            HarnessAgent agent,
            ProtocolTaskRepository repository,
            ProtocolTaskRunRegistry runRegistry) {
        return new AgentProtocolTaskStore(
                AgentFactory.fixed(agent),
                repository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties(),
                List.of(),
                runRegistry,
                BaseStoreProtocolTaskContextStore.inMemory());
    }

    /** Keeps records exactly as written, including their timestamps. */
    private static final class MapRepository implements ProtocolTaskRepository {

        private final Map<String, TaskRecord> records = new ConcurrentHashMap<>();

        @Override
        public void save(TaskRecord record) {
            records.put(record.getTaskId(), record);
        }

        @Override
        public Optional<TaskRecord> find(String taskId) {
            return Optional.ofNullable(records.get(taskId));
        }
    }

    /** Registry whose lease is always reported as lost, i.e. the task was taken over elsewhere. */
    private static final class LosingLeaseRegistry implements ProtocolTaskRunRegistry {

        private final ProtocolTaskRunRegistry delegate;

        LosingLeaseRegistry(ProtocolTaskRunRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ProtocolTaskLease> tryAcquire(String taskId, Duration leaseTtl) {
            return delegate.tryAcquire(taskId, leaseTtl);
        }

        @Override
        public boolean renew(ProtocolTaskLease lease, Duration leaseTtl) {
            return false;
        }

        @Override
        public void release(ProtocolTaskLease lease) {
            delegate.release(lease);
        }

        @Override
        public Optional<ProtocolTaskLease> find(String taskId) {
            return delegate.find(taskId);
        }

        @Override
        public void bindCancelHandler(String taskId, Runnable handler) {
            delegate.bindCancelHandler(taskId, handler);
        }

        @Override
        public void unbindCancelHandler(String taskId) {
            delegate.unbindCancelHandler(taskId);
        }

        @Override
        public boolean requestCancel(String taskId) {
            return delegate.requestCancel(taskId);
        }

        @Override
        public boolean isCancelled(String taskId) {
            return delegate.isCancelled(taskId);
        }
    }
}
