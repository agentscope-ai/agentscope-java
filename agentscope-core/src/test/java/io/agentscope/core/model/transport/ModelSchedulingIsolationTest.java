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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelSchedulers;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ModelSchedulingIsolationTest {

    @TempDir Path tempDir;

    @Test
    void concurrentStreamsAndAgentProgressWithApplicationPoolsSaturated() throws Exception {
        // A separate JVM sets small caps before Schedulers is initialized, without replacing the
        // global scheduler factory or interfering with other tests in the Surefire JVM.
        Path output = tempDir.resolve("isolation.log");
        // Keep the command line short even with long Windows dependency paths.
        Path argumentsFile = tempDir.resolve("isolation.args");
        StringBuilder arguments = new StringBuilder();
        for (String argument :
                List.of(
                        "-Dreactor.schedulers.defaultBoundedElasticSize=1",
                        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2",
                        "-Dagentscope.model.requestThreadCap=2",
                        "-Dagentscope.model.streamThreadCap=4",
                        "-Dagentscope.model.responseThreadCap=4",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Probe.class.getName())) {
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
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Isolated JVM did not finish");
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

    @Test
    void slowConsumerDoesNotHoldReadWorkerAndCustomSchedulersRemainCallerOwned() throws Exception {
        for (boolean jdk : List.of(true, false)) {
            Scheduler reads = Schedulers.newSingle("test-stream-reads");
            Scheduler responses = Schedulers.newSingle("test-stream-responses");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Disposable subscription = null;
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 100; i++) body.append("data: ").append(i).append("\n\n");
                body.append("data: [DONE]\n\n");
                server.enqueue(new MockResponse().setBody(body.toString()));
                HttpTransportConfig config =
                        HttpTransportConfig.builder().responsePrefetch(2).build();
                HttpTransport transport =
                        jdk
                                ? JdkHttpTransport.builder()
                                        .config(config)
                                        .streamScheduler(reads)
                                        .responseScheduler(responses)
                                        .build()
                                : OkHttpTransport.builder()
                                        .config(config)
                                        .streamScheduler(reads)
                                        .responseScheduler(responses)
                                        .build();
                try {
                    subscription =
                            transport.stream(request(server))
                                    .subscribe(
                                            value -> {
                                                if (value.equals("0")) {
                                                    entered.countDown();
                                                    await(release);
                                                }
                                            });
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    CountDownLatch workerFree = new CountDownLatch(1);
                    reads.schedule(workerFree::countDown);
                    assertTrue(
                            workerFree.await(2, TimeUnit.SECONDS),
                            "A blocked consumer must not occupy the stream read worker");
                    subscription.dispose();
                    release.countDown();
                } finally {
                    release.countDown();
                    if (subscription != null) subscription.dispose();
                    transport.close();
                }
                assertFalse(reads.isDisposed());
                assertFalse(responses.isDisposed());
            } finally {
                release.countDown();
                reads.dispose();
                responses.dispose();
            }
        }
    }

    @Test
    void queuedStreamTimeoutCancelsWorkBeforeItSendsHttpRequest() throws Exception {
        Scheduler reads = Schedulers.newBoundedElastic(1, 1, "test-queued-read");
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        reads.schedule(
                () -> {
                    occupied.countDown();
                    await(release);
                });
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            JdkHttpTransport transport =
                    JdkHttpTransport.builder()
                            .streamScheduler(reads)
                            .config(
                                    HttpTransportConfig.builder()
                                            .responseTimeout(Duration.ofMillis(100))
                                            .build())
                            .build();
            JdkHttpTransport recovery = null;
            try {
                StepVerifier.create(transport.stream(request(server)))
                        .expectErrorMatches(
                                error ->
                                        error instanceof HttpTransportException
                                                && error.getMessage().contains("Stream timeout"))
                        .verify(Duration.ofSeconds(2));
                assertEquals(0, server.getRequestCount());
                recovery = JdkHttpTransport.builder().streamScheduler(reads).build();
                server.enqueue(new MockResponse().setBody("data: next\n\ndata: [DONE]\n\n"));
                release.countDown();
                assertEquals(
                        List.of("next"),
                        recovery.stream(request(server))
                                .collectList()
                                .block(Duration.ofSeconds(2)));
                assertEquals(
                        1, server.getRequestCount(), "The timed-out queued send must never run");
            } finally {
                release.countDown();
                if (recovery != null) recovery.close();
                transport.close();
            }
        } finally {
            release.countDown();
            reads.dispose();
        }
    }

    @Test
    void responsePrefetchMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpTransportConfig.builder().responsePrefetch(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpTransportConfig.builder().responsePrefetch(-1));
        assertEquals(32, HttpTransportConfig.defaults().getResponsePrefetch());
    }

    private static HttpRequest request(MockWebServer server) {
        return HttpRequest.builder().url(server.url("/stream").toString()).method("GET").build();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch blocked = new CountDownLatch(3);
            Runnable blocker =
                    () -> {
                        blocked.countDown();
                        await(release);
                    };
            Disposable elasticBlocker = Schedulers.boundedElastic().schedule(blocker);
            ForkJoinPool.commonPool().execute(blocker);
            ForkJoinPool.commonPool().execute(blocker);
            try (MockWebServer server = new MockWebServer()) {
                assertTrue(blocked.await(5, TimeUnit.SECONDS));
                server.start();
                JdkHttpTransport jdk = JdkHttpTransport.builder().build();
                OkHttpTransport okhttp = OkHttpTransport.builder().build();
                try {
                    List<CompletableFuture<List<String>>> calls = new ArrayList<>();
                    for (HttpTransport transport : List.of(jdk, okhttp)) {
                        for (int i = 0; i < 12; i++) {
                            server.enqueue(
                                    new MockResponse()
                                            .setBody(
                                                    "data: first\n\n"
                                                            + "data: second\n\n"
                                                            + "data: [DONE]\n\n"));
                            calls.add(transport.stream(request(server)).collectList().toFuture());
                        }
                    }
                    for (CompletableFuture<List<String>> call : calls) {
                        assertEquals(List.of("first", "second"), call.get(8, TimeUnit.SECONDS));
                    }
                    server.enqueue(new MockResponse().setBody("data: answer\n\ndata: [DONE]\n\n"));
                    Model model =
                            new Model() {
                                public String getModelName() {
                                    return "local-sse";
                                }

                                public Flux<ChatResponse> stream(
                                        List<Msg> messages,
                                        List<ToolSchema> tools,
                                        GenerateOptions options) {
                                    return jdk.stream(request(server))
                                            .map(
                                                    text ->
                                                            ChatResponse.builder()
                                                                    .content(
                                                                            List.of(
                                                                                    TextBlock
                                                                                            .builder()
                                                                                            .text(
                                                                                                    text)
                                                                                            .build()))
                                                                    .build())
                                            .subscribeOn(ModelSchedulers.requests());
                                }
                            };
                    ReActAgent agent =
                            ReActAgent.builder()
                                    .name("isolated")
                                    .model(model)
                                    .threadJournalStore(new InMemoryThreadJournalStore())
                                    .build();
                    assertEquals(
                            "answer",
                            agent.call("hello").block(Duration.ofSeconds(8)).getTextContent());
                    assertEquals(1, Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE);
                    assertEquals(2, ForkJoinPool.getCommonPoolParallelism());
                } finally {
                    jdk.close();
                    okhttp.close();
                }
            } finally {
                release.countDown();
                elasticBlocker.dispose();
                Schedulers.shutdownNow();
            }
        }
    }
}
