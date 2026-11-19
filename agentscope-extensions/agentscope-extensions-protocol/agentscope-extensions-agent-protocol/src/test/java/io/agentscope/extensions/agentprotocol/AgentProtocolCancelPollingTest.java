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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Cancellation reaches a run as a poll: the intent is written to the registry by whichever instance
 * served {@code /cancel}, and the owner asks for it between events.
 *
 * <p>That interval is a cost when the registry is a shared store — one read per interval per running
 * task, whether or not anything was cancelled. This test pins the configuration knob that bounds it,
 * because the mistake it guards against (reading the intent per event, or ignoring the property and
 * using a constant) is invisible in a fast in-process test and only shows up as store QPS in
 * production.
 */
class AgentProtocolCancelPollingTest {

    private static final List<String> CANCELS =
            List.of("agent-protocol", "task-run-registry-cancels");

    @TempDir Path tempDir;

    @Test
    void aLongerIntervalMeansFewerRegistryReads() throws Exception {
        int fast = cancelReads(500);
        int slow = cancelReads(10_000);

        assertTrue(
                slow < fast,
                "a 10s interval must poll less often than a 500ms one, otherwise the configured"
                        + " interval is ignored: fast="
                        + fast
                        + " slow="
                        + slow);
        assertEquals(
                500L,
                new AgentProtocolProperties().getCancelCheckIntervalMs(),
                "the default keeps the single-instance behaviour");
    }

    /** Runs one short task over a counting store and returns how often cancels were read. */
    private int cancelReads(long intervalMs) throws Exception {
        CountingStore store = new CountingStore();
        AgentProtocolProperties properties = new AgentProtocolProperties();
        properties.setCancelCheckIntervalMs(intervalMs);
        AgentProtocolTaskStore taskStore =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(slowAgent()),
                        new WorkspaceProtocolTaskRepository(tempDir),
                        new AgentProtocolTaskEventBus(),
                        properties,
                        List.of(),
                        new BaseStoreProtocolTaskRunRegistry(store),
                        BaseStoreProtocolTaskContextStore.inMemory());

        String taskId = "t-" + intervalMs;
        taskStore.submit(taskId, "worker", "go");
        assertEquals("success", taskStore.waitFor(taskId, 15_000).get("status"));
        return store.cancelReads();
    }

    /** Three events spread over ~900ms, so the poll interval is measurable between them. */
    private static HarnessAgent slowAgent() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.interval(Duration.ofMillis(300))
                                .take(3)
                                .map(i -> (AgentEvent) new AgentResultEvent(reply("done"))));
        return agent;
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    /** Counts reads of the cancel namespace, which is the traffic the interval bounds. */
    private static final class CountingStore implements BaseStore {

        private final InMemoryStore delegate = new InMemoryStore();
        private final AtomicInteger cancelReads = new AtomicInteger();

        int cancelReads() {
            return cancelReads.get();
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            if (CANCELS.equals(namespace)) {
                cancelReads.incrementAndGet();
            }
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace,
                String key,
                Map<String, Object> value,
                long expectedVersion) {
            return delegate.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }
}
