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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class ModelSchedulersTest {

    @TempDir Path tempDir;

    @Test
    void defaultSchedulersAreDistinctAndUseDedicatedThreadPrefixes() throws Exception {
        Scheduler requests = ModelSchedulers.requests();
        Scheduler streams = ModelSchedulers.streams();
        Scheduler responses = ModelSchedulers.responses();

        assertNotNull(requests);
        assertNotNull(streams);
        assertNotNull(responses);
        assertNotSame(requests, streams);
        assertNotSame(requests, responses);
        assertNotSame(streams, responses);

        assertRunsWithPrefix(requests, "agentscope-model-request-");
        assertRunsWithPrefix(streams, "agentscope-model-stream-");
        assertRunsWithPrefix(responses, "agentscope-model-response-");
    }

    @Test
    void defaultThreadCapIsSixtyFourOnTwoProcessors() throws Exception {
        runChildJvm(
                "default-cap",
                List.of(
                        "-XX:ActiveProcessorCount=2",
                        "-cp",
                        System.getProperty("java.class.path"),
                        DefaultCapProbe.class.getName()));
    }

    @Test
    void modelPoolsOperateIsolatedFromSaturatedBoundedElastic() throws Exception {
        runChildJvm(
                "isolation",
                List.of(
                        "-XX:ActiveProcessorCount=2",
                        "-Dreactor.schedulers.defaultBoundedElasticSize=1",
                        "-Dagentscope.model.requestThreadCap=4",
                        "-Dagentscope.model.streamThreadCap=2",
                        "-Dagentscope.model.responseThreadCap=2",
                        "-cp",
                        System.getProperty("java.class.path"),
                        IsolationProbe.class.getName()));
    }

    @ParameterizedTest
    @CsvSource({
        "agentscope.model.requestThreadCap, 0, request",
        "agentscope.model.requestThreadCap, -1, request",
        "agentscope.model.streamThreadCap, 0, stream",
        "agentscope.model.streamThreadCap, -1, stream",
        "agentscope.model.responseThreadCap, 0, response",
        "agentscope.model.responseThreadCap, -1, response",
        "agentscope.model.taskQueueCapacity, 0, request",
        "agentscope.model.taskQueueCapacity, -1, request"
    })
    void nonPositivePropertyFailsInitialization(String property, String value, String role)
            throws Exception {
        runChildJvm(
                "invalid-" + property + "-" + value,
                List.of(
                        "-D" + property + "=" + value,
                        "-cp",
                        System.getProperty("java.class.path"),
                        InvalidPropertyProbe.class.getName(),
                        role,
                        property));
    }

    @Test
    void taskQueueCapacityLimitsQueuedTasks() throws Exception {
        runChildJvm(
                "queue-cap",
                List.of(
                        "-Dagentscope.model.requestThreadCap=1",
                        "-Dagentscope.model.taskQueueCapacity=1",
                        "-cp",
                        System.getProperty("java.class.path"),
                        QueueCapProbe.class.getName()));
    }

    @Test
    void agentBaseStreamCancellationCancelsUpstreamAndRemovesTemporaryHook() throws Exception {
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        AgentBase agent =
                new MinimalAgent("cancel-hook-check") {
                    @Override
                    protected Mono<Msg> doCall(List<Msg> msgs) {
                        return Mono.<Msg>never().doOnCancel(upstreamCancelled::countDown);
                    }
                };
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build();

        Disposable subscription =
                agent.stream(List.of(input), StreamOptions.defaults()).subscribe();
        try {
            assertFalse(
                    agent.getHooks().isEmpty(),
                    "Temporary StreamingHook must be registered while stream is active");
        } finally {
            subscription.dispose();
        }

        assertTrue(
                upstreamCancelled.await(3, TimeUnit.SECONDS),
                "Disposing AgentBase.stream must propagate cancel upstream");

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!agent.getHooks().isEmpty() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertTrue(
                agent.getHooks().isEmpty(),
                "Temporary StreamingHook must be removed after stream cancellation");
    }

    private void runChildJvm(String name, List<String> jvmArgs) throws Exception {
        Path output = tempDir.resolve(name + ".log");
        Path argumentsFile = tempDir.resolve(name + ".args");
        StringBuilder arguments = new StringBuilder();
        for (String argument : jvmArgs) {
            arguments
                    .append('"')
                    .append(argument.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"')
                    .append('\n');
        }
        Files.writeString(argumentsFile, arguments);
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "@" + argumentsFile)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), name + " JVM did not finish");
            assertEquals(
                    0,
                    process.exitValue(),
                    () -> {
                        try {
                            return Files.readString(output);
                        } catch (Exception e) {
                            return e.toString();
                        }
                    });
        } finally {
            process.destroyForcibly();
        }
    }

    private static void assertRunsWithPrefix(Scheduler scheduler, String prefix) throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.schedule(
                () -> {
                    threadName.set(Thread.currentThread().getName());
                    latch.countDown();
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(threadName.get());
        assertTrue(
                threadName.get().startsWith(prefix),
                "Expected thread prefix " + prefix + ", got: " + threadName.get());
    }

    public static final class DefaultCapProbe {

        public static void main(String[] args) throws Exception {
            assertEquals(2, Runtime.getRuntime().availableProcessors());
            assertPoolSupportsConcurrentTasks(
                    ModelSchedulers.requests(), "agentscope-model-request-", 64);
            assertPoolSupportsConcurrentTasks(
                    ModelSchedulers.streams(), "agentscope-model-stream-", 64);
            assertPoolSupportsConcurrentTasks(
                    ModelSchedulers.responses(), "agentscope-model-response-", 64);
        }

        private static void assertPoolSupportsConcurrentTasks(
                Scheduler scheduler, String prefix, int count) throws Exception {
            CountDownLatch barrier = new CountDownLatch(count);
            List<CompletableFuture<Void>> futures = new ArrayList<>(count);
            List<String> threads = new CopyOnWriteArrayList<>();
            for (int i = 0; i < count; i++) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                futures.add(done);
                scheduler.schedule(
                        () -> {
                            try {
                                threads.add(Thread.currentThread().getName());
                                barrier.countDown();
                                assertTrue(
                                        barrier.await(10, TimeUnit.SECONDS),
                                        "All "
                                                + count
                                                + " tasks must run concurrently on "
                                                + prefix);
                                done.complete(null);
                            } catch (Throwable t) {
                                done.completeExceptionally(t);
                            }
                        });
            }
            for (CompletableFuture<Void> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            assertEquals(count, threads.size());
            for (String name : threads) {
                assertTrue(
                        name.startsWith(prefix),
                        "Expected thread prefix " + prefix + ", got: " + name);
            }
        }
    }

    public static final class IsolationProbe {

        public static void main(String[] args) throws Exception {
            CountDownLatch elasticEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> blockerError = new AtomicReference<>();
            Disposable elasticBlocker =
                    Schedulers.boundedElastic()
                            .schedule(
                                    () -> {
                                        try {
                                            elasticEntered.countDown();
                                            release.await();
                                        } catch (InterruptedException e) {
                                            if (release.getCount() > 0) {
                                                blockerError.compareAndSet(null, e);
                                            }
                                            Thread.currentThread().interrupt();
                                        } catch (Throwable t) {
                                            blockerError.compareAndSet(null, t);
                                        }
                                    });
            try {
                assertEquals(1, Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE);
                assertTrue(elasticEntered.await(5, TimeUnit.SECONDS));

                // Verify 4 concurrent tasks on ModelSchedulers.requests() make progress together.
                CountDownLatch requestBarrier = new CountDownLatch(4);
                List<CompletableFuture<Void>> requestFutures = new ArrayList<>();
                List<String> requestThreads = new CopyOnWriteArrayList<>();
                for (int i = 0; i < 4; i++) {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    requestFutures.add(done);
                    ModelSchedulers.requests()
                            .schedule(
                                    () -> {
                                        try {
                                            requestThreads.add(Thread.currentThread().getName());
                                            requestBarrier.countDown();
                                            assertTrue(requestBarrier.await(5, TimeUnit.SECONDS));
                                            done.complete(null);
                                        } catch (Throwable t) {
                                            done.completeExceptionally(t);
                                        }
                                    });
                }
                for (CompletableFuture<Void> future : requestFutures) {
                    future.get(8, TimeUnit.SECONDS);
                }
                assertEquals(4, requestThreads.size());
                for (String name : requestThreads) {
                    assertTrue(
                            name.startsWith("agentscope-model-request-"),
                            "Unexpected request thread: " + name);
                }

                // Verify JdkHttpTransport and OkHttpTransport stream reads work while
                // boundedElastic is saturated and use agentscope-model-stream-*.
                try (MockWebServer server = new MockWebServer()) {
                    server.start();
                    JdkHttpTransport jdk = JdkHttpTransport.builder().build();
                    OkHttpTransport okhttp = OkHttpTransport.builder().build();
                    try {
                        HttpRequest request =
                                HttpRequest.builder()
                                        .url(server.url("/stream").toString())
                                        .method("GET")
                                        .build();
                        List<String> streamThreads = new CopyOnWriteArrayList<>();
                        for (HttpTransport transport : List.of(jdk, okhttp)) {
                            server.enqueue(
                                    new MockResponse()
                                            .setHeader("Content-Type", "text/event-stream")
                                            .setBody(
                                                    "data: first\n\n"
                                                            + "data: second\n\n"
                                                            + "data: [DONE]\n\n"));
                            List<String> chunks =
                                    transport.stream(request)
                                            .doOnNext(
                                                    chunk ->
                                                            streamThreads.add(
                                                                    Thread.currentThread()
                                                                            .getName()))
                                            .collectList()
                                            .block(Duration.ofSeconds(5));
                            assertEquals(List.of("first", "second"), chunks);
                        }
                        assertFalse(streamThreads.isEmpty());
                        for (String name : streamThreads) {
                            assertTrue(
                                    name.startsWith("agentscope-model-stream-"),
                                    "Unexpected stream thread: " + name);
                        }
                    } finally {
                        jdk.close();
                        okhttp.close();
                    }
                }

                // Verify AgentBase.stream() delivers events on agentscope-model-response-*.
                AgentBase agent = new MinimalAgent("isolated");
                Msg input =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("hello").build())
                                .build();
                List<String> responseThreads = new CopyOnWriteArrayList<>();
                List<Event> events =
                        agent.stream(List.of(input), StreamOptions.defaults())
                                .doOnNext(
                                        event ->
                                                responseThreads.add(
                                                        Thread.currentThread().getName()))
                                .collectList()
                                .block(Duration.ofSeconds(5));
                assertNotNull(events);
                assertFalse(events.isEmpty());
                for (String name : responseThreads) {
                    assertTrue(
                            name.startsWith("agentscope-model-response-"),
                            "Unexpected response thread: " + name);
                }

                release.countDown();
                assertNull(
                        blockerError.get(),
                        () -> "boundedElastic blocker failed prematurely: " + blockerError.get());
            } finally {
                release.countDown();
                elasticBlocker.dispose();
                Schedulers.shutdownNow();
            }
        }
    }

    public static final class InvalidPropertyProbe {

        public static void main(String[] args) {
            String role = args[0];
            String expectedProperty = args[1];
            ExceptionInInitializerError error =
                    assertThrows(
                            ExceptionInInitializerError.class,
                            () -> {
                                switch (role) {
                                    case "request" -> ModelSchedulers.requests();
                                    case "stream" -> ModelSchedulers.streams();
                                    case "response" -> ModelSchedulers.responses();
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "Unknown role: " + role);
                                }
                            });
            assertNotNull(error.getCause());
            assertTrue(error.getCause() instanceof IllegalArgumentException);
            assertTrue(
                    error.getCause().getMessage().contains(expectedProperty + " must be positive"));
        }
    }

    public static final class QueueCapProbe {

        public static void main(String[] args) throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> firstDone = new CompletableFuture<>();
            CompletableFuture<Void> queuedDone = new CompletableFuture<>();
            try {
                ModelSchedulers.requests()
                        .schedule(
                                () -> {
                                    try {
                                        started.countDown();
                                        release.await();
                                        firstDone.complete(null);
                                    } catch (Throwable t) {
                                        firstDone.completeExceptionally(t);
                                    }
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));

                // Occupies the single queued slot on the 1 backing thread.
                ModelSchedulers.requests().schedule(() -> queuedDone.complete(null));

                // Exceeding taskQueueCapacity=1 must reject the third task.
                assertThrows(
                        RejectedExecutionException.class,
                        () -> ModelSchedulers.requests().schedule(() -> {}));
            } finally {
                release.countDown();
            }
            firstDone.get(5, TimeUnit.SECONDS);
            queuedDone.get(5, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("deprecation")
    private static class MinimalAgent extends AgentBase {

        MinimalAgent(String name) {
            super(name);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("ok").build())
                            .build());
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.empty();
        }
    }
}
