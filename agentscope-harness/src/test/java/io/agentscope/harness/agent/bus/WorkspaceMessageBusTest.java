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
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;

class WorkspaceMessageBusTest {

    @TempDir Path tempDir;
    private WorkspaceMessageBus bus;

    @BeforeEach
    void setUp() {
        LocalFilesystem fs = new LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
    }

    // ---- Mode A: drain queue ----

    @Test
    void pushAndDrainInOrder() {
        bus.queuePush("q1", Map.of("seq", 1)).block();
        bus.queuePush("q1", Map.of("seq", 2)).block();
        bus.queuePush("q1", Map.of("seq", 3)).block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertNotNull(drained);
        assertEquals(3, drained.size());
        assertEquals(1, drained.get(0).payload().get("seq"));
        assertEquals(2, drained.get(1).payload().get("seq"));
        assertEquals(3, drained.get(2).payload().get("seq"));
    }

    @Test
    void drainRemovesEntries() {
        bus.queuePush("q1", Map.of("v", "a")).block();
        bus.queuePush("q1", Map.of("v", "b")).block();

        bus.queueDrain("q1", 10).block();
        List<BusEntry> second = bus.queueDrain("q1", 10).block();
        assertTrue(second.isEmpty());
    }

    @Test
    void drainRespectsMaxCount() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queuePush("q1", Map.of("v", 2)).block();
        bus.queuePush("q1", Map.of("v", 3)).block();

        List<BusEntry> first = bus.queueDrain("q1", 2).block();
        assertEquals(2, first.size());

        List<BusEntry> remaining = bus.queueDrain("q1", 10).block();
        assertEquals(1, remaining.size());
        assertEquals(3, remaining.get(0).payload().get("v"));
    }

    @Test
    void drainEmptyQueueReturnsEmpty() {
        List<BusEntry> drained = bus.queueDrain("nonexistent", 10).block();
        assertNotNull(drained);
        assertTrue(drained.isEmpty());
    }

    @Test
    void queueDeleteRemovesAll() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queueDelete("q1").block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertTrue(drained.isEmpty());
    }

    @Test
    void drainsLegacyJsonQueueEntries() {
        LocalFilesystem filesystem = new LocalFilesystem(tempDir, true, 10);
        WorkspaceMessageBus legacyBus = new WorkspaceMessageBus(filesystem, "/bus");
        String path =
                "/bus/queues/"
                        + WorkspaceMessageBus.hashKey("legacy")
                        + "/00000000000000000001.json";
        WriteResult write =
                filesystem.write(
                        io.agentscope.core.agent.RuntimeContext.empty(),
                        path,
                        "{\"value\":\"legacy\"}");
        assertTrue(write.isSuccess());

        List<BusEntry> drained = legacyBus.queueDrain("legacy", 1).block();

        assertEquals(1, drained.size());
        assertEquals("legacy", drained.get(0).payload().get("value"));
        assertFalse(Files.exists(tempDir.resolve(path.substring(1))));
        assertFalse(Files.exists(tempDir.resolve(path.substring(1) + ".claim")));
    }

    @Test
    void missingPayloadDoesNotConsumeDrainLimit() {
        LocalFilesystem filesystem = new LocalFilesystem(tempDir, true, 10);
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");
        String queueDir = "/bus/queues/" + WorkspaceMessageBus.hashKey("with-ghost");
        WriteResult ghostMarker =
                filesystem.write(
                        io.agentscope.core.agent.RuntimeContext.empty(),
                        queueDir + "/00000000000000000001.ready",
                        "");
        assertTrue(ghostMarker.isSuccess());
        testBus.queuePush("with-ghost", Map.of("value", "live")).block();

        List<BusEntry> drained = testBus.queueDrain("with-ghost", 1).block();

        assertEquals(1, drained.size());
        assertEquals("live", drained.get(0).payload().get("value"));
    }

    @Test
    void failedPublishWithSurvivingReadyMarkerKeepsPayloadReadable() {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult write(
                            io.agentscope.core.agent.RuntimeContext context,
                            String path,
                            String content) {
                        WriteResult result = super.write(context, path, content);
                        if (path.endsWith(".ready") && result.isSuccess()) {
                            // Sandbox upload can fail after creating the marker, and cleanup can
                            // fail too. Model the persisted marker with a real local file.
                            return WriteResult.fail(
                                    "upload denied; failed to remove placeholder: cleanup denied");
                        }
                        return result;
                    }

                    @Override
                    public WriteResult delete(
                            io.agentscope.core.agent.RuntimeContext context, String path) {
                        if (path.endsWith(".ready")) {
                            return WriteResult.fail("cleanup denied");
                        }
                        return super.delete(context, path);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");

        assertThrows(
                IllegalStateException.class,
                () -> testBus.queuePush("failed-publish", Map.of("value", "kept")).block());

        List<BusEntry> drained = testBus.queueDrain("failed-publish", 1).block();
        assertEquals(1, drained.size());
        assertEquals("kept", drained.get(0).payload().get("value"));
    }

    @Test
    void failedPublishWithoutReadyMarkerRetainsPayloadForOperatorCleanup() throws IOException {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult write(
                            io.agentscope.core.agent.RuntimeContext context,
                            String path,
                            String content) {
                        if (path.endsWith(".ready")) {
                            return WriteResult.fail("publish denied");
                        }
                        return super.write(context, path, content);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");

        assertThrows(
                IllegalStateException.class,
                () -> testBus.queuePush("failed-publish", Map.of("value", "orphan")).block());

        Path queueDir =
                tempDir.resolve("bus/queues/" + WorkspaceMessageBus.hashKey("failed-publish"));
        try (var entries = Files.list(queueDir)) {
            assertTrue(entries.anyMatch(path -> path.toString().endsWith(".payload")));
        }
        assertFalse(testBus.queuePeek("failed-publish").block());
        testBus.queueDelete("failed-publish").block();
        assertFalse(Files.exists(queueDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingReadyMarkerIsNotDeletedByFailedPublisher(boolean structuredConflict) {
        AtomicReference<String> readyPath = new AtomicReference<>();
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult write(
                            io.agentscope.core.agent.RuntimeContext context,
                            String path,
                            String content) {
                        if (path.endsWith(".ready")) {
                            readyPath.set(path);
                            assertTrue(super.write(context, path, content).isSuccess());
                            return structuredConflict
                                    ? WriteResult.alreadyExists(path)
                                    : WriteResult.fail("unknown write outcome");
                        }
                        return super.write(context, path, content);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");

        assertThrows(
                IllegalStateException.class,
                () -> testBus.queuePush("marker-conflict", Map.of("value", "kept")).block());

        Path marker = tempDir.resolve(readyPath.get().substring(1));
        assertTrue(Files.exists(marker));
        Path payload =
                marker.resolveSibling(
                        marker.getFileName().toString().replace(".ready", ".payload"));
        assertTrue(Files.exists(payload));
    }

    @Test
    void failedReadyDeletionRetainsPayloadForRecovery() {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult delete(
                            io.agentscope.core.agent.RuntimeContext context, String path) {
                        if (path.endsWith(".ready")) {
                            return WriteResult.fail("permission denied");
                        }
                        return super.delete(context, path);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");
        String entryId = testBus.queuePush("delete-failure", Map.of("value", "delivered")).block();

        List<BusEntry> drained = testBus.queueDrain("delete-failure", 1).block();
        assertEquals(1, drained.size());
        assertEquals("delivered", drained.get(0).payload().get("value"));

        Path entryPath =
                tempDir.resolve(
                        "bus/queues/"
                                + WorkspaceMessageBus.hashKey("delete-failure")
                                + "/"
                                + entryId);
        assertTrue(Files.exists(Path.of(entryPath + ".ready")));
        assertTrue(Files.exists(Path.of(entryPath + ".payload")));
        assertTrue(testBus.queueDrain("delete-failure", 1).block().isEmpty());
    }

    @Test
    void claimStorageFailureIsNotReportedAsEmptyQueue() {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult write(
                            io.agentscope.core.agent.RuntimeContext context,
                            String path,
                            String content) {
                        if (path.endsWith(".claim")) {
                            return WriteResult.fail("permission denied");
                        }
                        return super.write(context, path, content);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");
        testBus.queuePush("claim-failure", Map.of("value", "waiting")).block();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> testBus.queueDrain("claim-failure", 1).block());

        assertTrue(error.getMessage().contains("permission denied"));
        assertTrue(testBus.queuePeek("claim-failure").block());
    }

    @Test
    void routedClaimConflictIsNotReportedAsStorageFailure() {
        CompositeFilesystem filesystem =
                new CompositeFilesystem(
                        new LocalFilesystem(tempDir.resolve("default"), true, 10),
                        Map.of(
                                "/route/",
                                new LocalFilesystem(tempDir.resolve("routed"), true, 10)));
        WorkspaceMessageBus routedBus = new WorkspaceMessageBus(filesystem, "/route/bus");
        String entryId = routedBus.queuePush("shared", Map.of("value", "waiting")).block();
        String claimPath =
                "/route/bus/queues/"
                        + WorkspaceMessageBus.hashKey("shared")
                        + "/"
                        + entryId
                        + ".ready.claim";
        assertTrue(
                filesystem
                        .write(io.agentscope.core.agent.RuntimeContext.empty(), claimPath, "")
                        .isSuccess());

        List<BusEntry> drained =
                assertDoesNotThrow(() -> routedBus.queueDrain("shared", 1).block());
        assertTrue(drained.isEmpty());
        assertTrue(routedBus.queuePeek("shared").block());
    }

    @Test
    void claimStorageFailureDoesNotDiscardAlreadyDrainedEntries() {
        AtomicInteger claimAttempts = new AtomicInteger();
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, true, 10) {
                    @Override
                    public WriteResult write(
                            io.agentscope.core.agent.RuntimeContext context,
                            String path,
                            String content) {
                        if (path.endsWith(".claim") && claimAttempts.incrementAndGet() >= 2) {
                            return WriteResult.fail("permission denied");
                        }
                        return super.write(context, path, content);
                    }
                };
        WorkspaceMessageBus testBus = new WorkspaceMessageBus(filesystem, "/bus");
        testBus.queuePush("claim-failure", Map.of("value", "first")).block();
        testBus.queuePush("claim-failure", Map.of("value", "second")).block();

        List<BusEntry> drained = testBus.queueDrain("claim-failure", 2).block();

        assertEquals(1, drained.size());
        assertEquals("first", drained.get(0).payload().get("value"));
        assertTrue(testBus.queuePeek("claim-failure").block());
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> testBus.queueDrain("claim-failure", 2).block());
        assertTrue(error.getMessage().contains("permission denied"));
    }

    @Test
    void pushReturnsUniqueIds() {
        String id1 = bus.queuePush("q1", Map.of("v", 1)).block();
        String id2 = bus.queuePush("q1", Map.of("v", 2)).block();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    void queuesIsolatedByKey() {
        bus.queuePush("q1", Map.of("from", "q1")).block();
        bus.queuePush("q2", Map.of("from", "q2")).block();

        List<BusEntry> fromQ1 = bus.queueDrain("q1", 10).block();
        assertEquals(1, fromQ1.size());
        assertEquals("q1", fromQ1.get(0).payload().get("from"));
    }

    // ---- Mode C: replay log ----

    @Test
    void logAppendAndReadInOrder() {
        bus.logAppend("log1", Map.of("seq", 1), 0).block();
        bus.logAppend("log1", Map.of("seq", 2), 0).block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).payload().get("seq"));
        assertEquals(2, all.get(1).payload().get("seq"));
    }

    @Test
    void logReadWithCursor() {
        bus.logAppend("log1", Map.of("v", "a"), 0).block();
        String id2 = bus.logAppend("log1", Map.of("v", "b"), 0).block();
        bus.logAppend("log1", Map.of("v", "c"), 0).block();

        List<BusEntry> afterId2 = bus.logRead("log1", id2, 100).block();
        assertEquals(1, afterId2.size());
        assertEquals("c", afterId2.get(0).payload().get("v"));
    }

    @Test
    void logReadIsNonDestructive() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        List<BusEntry> first = bus.logRead("log1", null, 100).block();
        List<BusEntry> second = bus.logRead("log1", null, 100).block();
        assertEquals(first.size(), second.size());
    }

    @Test
    void logAppendRespectsMaxLen() {
        for (int i = 0; i < 10; i++) {
            bus.logAppend("log1", Map.of("i", i), 5).block();
        }
        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(5, all.size());
        assertEquals(5, all.get(0).payload().get("i"));
        assertEquals(9, all.get(4).payload().get("i"));
    }

    @Test
    void logTrimDeletesAll() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        bus.logTrim("log1").block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertTrue(all.isEmpty());
    }

    // ---- Inbox domain helpers ----

    @Test
    void inboxPushAndDrain() {
        bus.inboxPush("s1", Map.of("hint", "hello")).block();
        bus.inboxPush("s1", Map.of("hint", "world")).block();

        List<BusEntry> entries = bus.inboxDrain("s1", 100).block();
        assertEquals(2, entries.size());
        assertEquals("hello", entries.get(0).payload().get("hint"));

        assertTrue(bus.inboxDrain("s1", 100).block().isEmpty());
    }

    // ---- Cross-instance sharing ----

    @Test
    void twoInstancesShareSameDirectory() {
        LocalFilesystem fs2 = new LocalFilesystem(tempDir, true, 10);
        WorkspaceMessageBus bus2 = new WorkspaceMessageBus(fs2, "/bus");

        bus.queuePush("shared", Map.of("from", "bus1")).block();
        bus2.queuePush("shared", Map.of("from", "bus2")).block();

        List<BusEntry> all = bus.queueDrain("shared", 10).block();
        assertEquals(2, all.size());

        assertTrue(bus2.queueDrain("shared", 10).block().isEmpty());
    }

    @ParameterizedTest(name = "legacyEntry={0}")
    @ValueSource(booleans = {false, true})
    void concurrentDrainsDeliverEachEntryOnce(boolean legacyEntry) throws Exception {
        DelayedClaimLocalFilesystem fs = new DelayedClaimLocalFilesystem(tempDir);
        WorkspaceMessageBus bus1 = new WorkspaceMessageBus(fs, "/concurrent-bus");
        WorkspaceMessageBus bus2 = new WorkspaceMessageBus(fs, "/concurrent-bus");
        if (legacyEntry) {
            String path =
                    "/concurrent-bus/queues/"
                            + WorkspaceMessageBus.hashKey("shared")
                            + "/00000000000000000001.json";
            WriteResult write =
                    fs.write(
                            io.agentscope.core.agent.RuntimeContext.empty(),
                            path,
                            "{\"value\":\"once\"}");
            assertTrue(write.isSuccess());
        } else {
            bus1.queuePush("shared", Map.of("value", "once")).block();
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<BusEntry>> first =
                    executor.submit(() -> bus1.queueDrain("shared", 1).block());
            Future<List<BusEntry>> second =
                    executor.submit(() -> bus2.queueDrain("shared", 1).block());

            int delivered = first.get(5, TimeUnit.SECONDS).size();
            delivered += second.get(5, TimeUnit.SECONDS).size();
            assertEquals(1, delivered);
            assertEquals(2, fs.claimWrites.get(), "both drains must attempt the same claim");
            assertEquals(1, fs.successfulClaims.get(), "only one concurrent claim may succeed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void drainDoesNotConsumeEntryWhileQueuePushIsWriting() throws Exception {
        PartiallyWrittenLocalFilesystem fs = new PartiallyWrittenLocalFilesystem(tempDir);
        WorkspaceMessageBus bus = new WorkspaceMessageBus(fs, "/publishing-bus");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> push =
                    executor.submit(
                            () -> bus.queuePush("shared", Map.of("value", "complete")).block());

            assertTrue(
                    fs.partialWriteStarted.await(5, TimeUnit.SECONDS),
                    "queuePush did not reach the partial-write point");
            assertTrue(bus.queueDrain("shared", 1).block().isEmpty());

            fs.finishWrite.countDown();
            push.get(5, TimeUnit.SECONDS);

            List<BusEntry> drained = bus.queueDrain("shared", 1).block();
            assertEquals(1, drained.size());
            assertEquals("complete", drained.get(0).payload().get("value"));
        } finally {
            fs.finishWrite.countDown();
            executor.shutdownNow();
        }
    }

    // ---- Session events ----

    @Test
    void sessionPublishAndReadEvents() {
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_DELTA")).block();
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_END")).block();

        List<BusEntry> events = bus.sessionReadEvents("s1", null, 100).block();
        assertEquals(2, events.size());
        assertEquals("TEXT_DELTA", events.get(0).payload().get("type"));
        assertEquals("TEXT_END", events.get(1).payload().get("type"));
    }

    @Test
    void sessionTrimClearsEvents() {
        bus.sessionPublishEvent("s1", Map.of("v", 1)).block();
        bus.sessionTrimEvents("s1").block();

        assertTrue(bus.sessionReadEvents("s1", null, 100).block().isEmpty());
    }

    // ---- File structure verification ----

    @Test
    void filesCreatedUnderBusRoot() throws IOException {
        bus.queuePush("myqueue", Map.of("v", 1)).block();

        Path busDir = tempDir.resolve("bus");
        assertTrue(Files.exists(busDir), "Bus root directory should exist");
        assertTrue(Files.exists(busDir.resolve("queues")), "queues/ should exist");

        long readyMarkers =
                Files.walk(busDir.resolve("queues"))
                        .filter(p -> p.toString().endsWith(".ready"))
                        .count();
        assertTrue(readyMarkers >= 1, "Should have at least one published queue entry");
    }

    // ---- Scheduler affinity regression guard ----

    /**
     * Locks in that {@link WorkspaceMessageBus#subscribe(String)} emits its heartbeat ticks on
     * the {@code boundedElastic} scheduler.
     *
     * <p>Why this matters: the per-tick downstream consumer ({@code WakeupDispatcher} calls
     * {@code queueDrain(...).block()}) is blocking file I/O, and it runs on the same thread that
     * emits the tick. It must not land on {@code parallel()} — that pool is shared, CPU-core-sized,
     * and blocking it starves the global timer. Note that
     * {@code Flux.interval(d).subscribeOn(boundedElastic())} does <b>not</b> move ticks off
     * {@code parallel} ({@code subscribeOn} only relocates the subscribe call, not the timer);
     * only {@code Flux.interval(d, boundedElastic())} does. This test guards against a silent
     * revert to the ineffective {@code subscribeOn} form.
     */
    @Test
    void subscribeEmitsTicksOnBoundedElastic() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        Disposable d =
                bus.subscribe("regression")
                        .doOnNext(m -> thread.set(Thread.currentThread().getName()))
                        .take(1)
                        .subscribe(m -> latch.countDown());
        try {
            // First tick arrives after POLL_INTERVAL (3s); allow headroom.
            assertTrue(latch.await(5, TimeUnit.SECONDS), "no tick within 5s");
            assertNotNull(thread.get());
            assertTrue(
                    thread.get().startsWith("boundedElastic"),
                    "subscribe() tick emitted on " + thread.get() + ", expected boundedElastic");
        } finally {
            d.dispose();
        }
    }

    /**
     * Reproduces the "open Studio → crash" scenario at the agentscope layer.
     *
     * <p>Registering a non-Noop {@link Tracer} triggers {@link TracerRegistry#enableTracingHook()},
     * which installs a <b>global</b> {@code Hooks.onEachOperator} lift wrapping every Reactor
     * operator in the JVM (the deprecated hook smartwe's Studio integration turns on via
     * {@code TelemetryTracer}). The per-tick consumer calls {@code .block()} — exactly what
     * {@code WakeupDispatcher.drainAndDispatch()} does. On a {@code parallel} (NonBlocking) tick
     * thread, {@code Mono.block()} throws {@code IllegalStateException}; subscribe() must therefore
     * emit ticks on {@code boundedElastic} (non-NonBlocking) so blocking per-tick work is legal.
     *
     * <p>This test fails on the old {@code Flux.interval(d).subscribeOn(boundedElastic)} form and
     * passes once the timer runs on {@code boundedElastic}.
     */
    @Test
    void subscribeTickSafeUnderGlobalTracingHook() throws InterruptedException {
        // non-Noop Tracer → register() enables the global onEachOperator lift.
        TracerRegistry.register(new Tracer() {});
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Disposable d =
                    bus.subscribe("hook-block-check")
                            .doOnNext(
                                    m -> {
                                        try {
                                            // Mimic drainAndDispatch()'s blocking call.
                                            bus.queuePeek("anything").block();
                                        } catch (Throwable t) {
                                            error.set(t);
                                        }
                                    })
                            .take(1)
                            .subscribe(m -> latch.countDown());
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS), "no tick within 5s");
                assertNull(error.get(), () -> "blocking on subscribe() tick threw: " + error.get());
            } finally {
                d.dispose();
            }
        } finally {
            // Global hook cleanup — must not leak into other tests in the JVM.
            TracerRegistry.resetToNoop();
        }
    }

    private static final class DelayedClaimLocalFilesystem extends LocalFilesystem {

        private final CountDownLatch listings = new CountDownLatch(2);
        private final CountDownLatch firstClaimCreated = new CountDownLatch(1);
        private final CountDownLatch secondClaimAttempted = new CountDownLatch(1);
        private final AtomicInteger queueReads = new AtomicInteger();
        private final AtomicInteger claimWrites = new AtomicInteger();
        private final AtomicInteger successfulClaims = new AtomicInteger();

        private DelayedClaimLocalFilesystem(Path rootDir) {
            super(rootDir, true, 10);
        }

        @Override
        public LsResult ls(io.agentscope.core.agent.RuntimeContext context, String path) {
            LsResult result = super.ls(context, path);
            if (path.contains("/queues/")) {
                listings.countDown();
                await(listings);
            }
            return result;
        }

        @Override
        public ReadResult read(
                io.agentscope.core.agent.RuntimeContext context,
                String filePath,
                int offset,
                int limit) {
            ReadResult result = super.read(context, filePath, offset, limit);
            if (filePath.endsWith(".json") || filePath.endsWith(".payload")) {
                if (queueReads.incrementAndGet() == 1) {
                    await(secondClaimAttempted);
                }
            }
            return result;
        }

        @Override
        public WriteResult write(
                io.agentscope.core.agent.RuntimeContext context, String filePath, String content) {
            if (filePath.endsWith(".claim")) {
                int attempt = claimWrites.incrementAndGet();
                if (attempt == 2) {
                    await(firstClaimCreated);
                }
                WriteResult result = super.write(context, filePath, content);
                if (result.isSuccess()) {
                    successfulClaims.incrementAndGet();
                    if (attempt == 1) {
                        firstClaimCreated.countDown();
                    }
                }
                if (attempt == 2) {
                    secondClaimAttempted.countDown();
                }
                return result;
            }
            return super.write(context, filePath, content);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for a concurrency-test barrier");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for a test barrier", e);
            }
        }
    }

    private static final class PartiallyWrittenLocalFilesystem extends LocalFilesystem {

        private final Path rootDir;
        private final CountDownLatch partialWriteStarted = new CountDownLatch(1);
        private final CountDownLatch finishWrite = new CountDownLatch(1);

        private PartiallyWrittenLocalFilesystem(Path rootDir) {
            super(rootDir, true, 10);
            this.rootDir = rootDir;
        }

        @Override
        public WriteResult write(
                io.agentscope.core.agent.RuntimeContext context, String filePath, String content) {
            if (!filePath.contains("/queues/")
                    || !(filePath.endsWith(".json") || filePath.endsWith(".payload"))) {
                return super.write(context, filePath, content);
            }

            Path resolved = rootDir.resolve(filePath.substring(1));
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.createDirectories(resolved.getParent());
                try (FileChannel channel =
                        FileChannel.open(
                                resolved,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE)) {
                    ByteBuffer firstByte = ByteBuffer.wrap(bytes, 0, 1);
                    while (firstByte.hasRemaining()) {
                        channel.write(firstByte);
                    }
                    partialWriteStarted.countDown();
                    DelayedClaimLocalFilesystem.await(finishWrite);
                    ByteBuffer remainder = ByteBuffer.wrap(bytes, 1, bytes.length - 1);
                    while (remainder.hasRemaining()) {
                        channel.write(remainder);
                    }
                }
                return WriteResult.ok(filePath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
