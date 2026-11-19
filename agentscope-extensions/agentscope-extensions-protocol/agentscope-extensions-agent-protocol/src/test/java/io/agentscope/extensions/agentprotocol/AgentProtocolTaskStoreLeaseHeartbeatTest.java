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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * The lease heartbeat, tested on its positive path.
 *
 * <p>{@link AgentProtocolTaskStoreFailureHandlingTest} covers the failure side — a lease that cannot
 * be renewed must stop the run. The opposite mistake is silent and worse: a heartbeat that never
 * renews, or renews at an interval the TTL cannot tolerate, lets a healthy long run lose ownership
 * mid-flight and be aborted as "taken over". A test that only checks the failure path stays green
 * either way, so this one runs a submission far longer than one lease TTL and asserts that ownership
 * is still held while it runs.
 */
class AgentProtocolTaskStoreLeaseHeartbeatTest {

    private static final long LEASE_TTL_MS = 1_000L;
    private static final long HEARTBEAT_MS = 250L;

    @TempDir Path tempDir;

    @Test
    @Timeout(30)
    void aRunOutlivingSeveralLeaseTtlsKeepsOwnershipAndCompletes() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        // One TTL is 1s; this run occupies roughly 2.2s, so it must survive two renewals.
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.<AgentEvent>just(
                                        new AgentResultEvent(reply("done")),
                                        new AgentEndEvent(null))
                                .delaySubscription(Duration.ofMillis(2_200)));

        CountingRegistry registry =
                new CountingRegistry(BaseStoreProtocolTaskRunRegistry.inMemory());
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setRunLeaseTtlMs(LEASE_TTL_MS);
        properties.setRunHeartbeatIntervalMs(HEARTBEAT_MS);

        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        new WorkspaceProtocolTaskRepository(tempDir),
                        new AgentProtocolTaskEventBus(),
                        properties,
                        List.of(),
                        registry,
                        BaseStoreProtocolTaskContextStore.inMemory());

        store.submit("t-long", "worker", "go");

        // Wait until the heartbeat has renewed at least twice, then confirm the lease is still
        // live.
        long deadline = System.currentTimeMillis() + 5_000;
        while (registry.renewals() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(
                registry.renewals() >= 2,
                "the heartbeat must renew the lease, renewals=" + registry.renewals());
        assertTrue(
                registry.delegate().find("t-long").isPresent(),
                "the lease must still be held after its original TTL, or the run would be"
                        + " abandoned as taken over");

        Map<String, Object> snapshot = store.waitFor("t-long", 20_000);
        assertEquals(
                "success",
                snapshot.get("status"),
                "a run that keeps renewing must finish instead of being settled as owner-lost: "
                        + snapshot);
        assertTrue(
                registry.delegate().find("t-long").isEmpty(),
                "the lease must be released once the run ends");
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    /** Counts renewals so the assertion does not depend on wall-clock sleeps alone. */
    private static final class CountingRegistry implements ProtocolTaskRunRegistry {

        private final ProtocolTaskRunRegistry delegate;
        private final AtomicInteger renewals = new AtomicInteger();

        CountingRegistry(ProtocolTaskRunRegistry delegate) {
            this.delegate = delegate;
        }

        ProtocolTaskRunRegistry delegate() {
            return delegate;
        }

        int renewals() {
            return renewals.get();
        }

        @Override
        public Optional<ProtocolTaskLease> tryAcquire(String taskId, Duration leaseTtl) {
            return delegate.tryAcquire(taskId, leaseTtl);
        }

        @Override
        public boolean renew(ProtocolTaskLease lease, Duration leaseTtl) {
            boolean renewed = delegate.renew(lease, leaseTtl);
            if (renewed) {
                renewals.incrementAndGet();
            }
            return renewed;
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
