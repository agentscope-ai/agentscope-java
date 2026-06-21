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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class WakeupDispatcherTest {

    private WorkspaceMessageBus messageBus;
    private StubTarget target;
    private WakeupDispatcher dispatcher;

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmpDir;

    @BeforeEach
    void setUp() {
        messageBus = new WorkspaceMessageBus(new LocalFilesystem(tmpDir, true, 10), "/bus");
        target = new StubTarget();
        dispatcher = new WakeupDispatcher(messageBus, target);
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    @Test
    @DisplayName("Wakeup signal triggers drain and dispatch for idle session")
    void wakeupSignal_dispatchesIdleSession() throws Exception {
        target.addKnown("sess-wakeup-1");
        dispatcher.start();

        messageBus.enqueueWakeup("user-1", "sess-wakeup-1", "agent-main").block();

        assertTrue(
                target.awaitWakeup(1, 6, TimeUnit.SECONDS),
                "Expected runWakeup to be called within 6s");
        assertEquals(1, target.wokenSessions.size());
        assertEquals("sess-wakeup-1", target.wokenSessions.get(0));
    }

    @Test
    @DisplayName("Running session is skipped by dispatcher")
    void runningSession_skipped() throws Exception {
        target.addKnown("sess-running");
        target.markRunning("sess-running");
        dispatcher.start();

        messageBus.enqueueWakeup("user-1", "sess-running", "agent-main").block();

        Thread.sleep(500);
        assertTrue(target.wokenSessions.isEmpty(), "Running session should not be woken");
    }

    @Test
    @DisplayName("Unknown session is skipped (runWakeup returns Mono.empty)")
    void unknownSession_skipped() throws Exception {
        dispatcher.start();

        messageBus.enqueueWakeup("user-1", "sess-unknown", "agent-main").block();

        Thread.sleep(500);
        assertTrue(target.wokenSessions.isEmpty(), "Unknown session should not be woken");
    }

    @Test
    @DisplayName("Initial drain on start picks up pre-existing wakeups")
    void initialDrain_picksUpPreExisting() throws Exception {
        target.addKnown("sess-pre");

        messageBus
                .queuePush(
                        "agentscope:wakeups",
                        Map.of(
                                "userId", "user-1",
                                "sessionId", "sess-pre",
                                "agentId", "agent-main"))
                .block();

        dispatcher.start();

        assertTrue(
                target.awaitWakeup(1, 3, TimeUnit.SECONDS),
                "Pre-existing wakeup should be drained on start");
        assertEquals("sess-pre", target.wokenSessions.get(0));
    }

    @Test
    @DisplayName("Startup drain processes all pre-existing wakeups across multiple batches")
    void initialDrain_processesMoreThanOneBatch() {
        int wakeupCount = 65;
        for (int i = 0; i < wakeupCount; i++) {
            String sessionId = "sess-pre-" + i;
            target.addKnown(sessionId);
            messageBus
                    .queuePush(
                            "agentscope:wakeups",
                            Map.of(
                                    "userId", "user-1",
                                    "sessionId", sessionId,
                                    "agentId", "agent-main"))
                    .block();
        }

        dispatcher.start();

        assertEquals(wakeupCount, target.wokenSessions.size());
    }

    @Test
    @DisplayName("Startup subscribes before draining the durable wakeup queue")
    void startup_subscribesBeforeInitialDrain() {
        String sessionId = "sess-startup-race";
        target.addKnown(sessionId);
        messageBus =
                new EnqueueOnSubscribeMessageBus(
                        new LocalFilesystem(tmpDir, true, 10), "/bus", sessionId);
        dispatcher = new WakeupDispatcher(messageBus, target);

        dispatcher.start();

        assertEquals(1, target.wokenSessions.size());
        assertEquals(sessionId, target.wokenSessions.get(0));
    }

    // ------------------------------------------------------------------
    //  Test double
    // ------------------------------------------------------------------

    private static class StubTarget implements WakeupDispatcher.WakeupTarget {

        final CopyOnWriteArrayList<String> wokenSessions = new CopyOnWriteArrayList<>();
        private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();
        private final Set<String> knownSessions = ConcurrentHashMap.newKeySet();
        private volatile CountDownLatch wakeupLatch = new CountDownLatch(1);

        void addKnown(String sessionId) {
            knownSessions.add(sessionId);
        }

        void markRunning(String sessionId) {
            runningSessions.add(sessionId);
        }

        @Override
        public boolean isSessionRunning(String sessionId) {
            return runningSessions.contains(sessionId);
        }

        @Override
        public Mono<Msg> runWakeup(String sessionId) {
            if (!knownSessions.contains(sessionId)) {
                return Mono.empty();
            }
            wokenSessions.add(sessionId);
            wakeupLatch.countDown();
            return Mono.just(Msg.builder().role(MsgRole.ASSISTANT).textContent("woken").build());
        }

        boolean awaitWakeup(int expected, long timeout, TimeUnit unit) throws InterruptedException {
            wakeupLatch = new CountDownLatch(expected);
            if (wokenSessions.size() >= expected) {
                return true;
            }
            return wakeupLatch.await(timeout, unit);
        }
    }

    private static final class EnqueueOnSubscribeMessageBus extends WorkspaceMessageBus {

        private final String sessionId;

        private EnqueueOnSubscribeMessageBus(
                LocalFilesystem filesystem, String busRoot, String sessionId) {
            super(filesystem, busRoot);
            this.sessionId = sessionId;
        }

        @Override
        public Flux<Map<String, Object>> subscribeWakeup() {
            return Flux.defer(
                    () -> {
                        queuePush(
                                        "agentscope:wakeups",
                                        Map.of(
                                                "userId", "user-1",
                                                "sessionId", sessionId,
                                                "agentId", "agent-main"))
                                .block();
                        return Flux.never();
                    });
        }
    }
}
