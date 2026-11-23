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
package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.ModelExecutions.Batch;
import io.agentscope.durable.ModelExecutions.Key;
import io.agentscope.durable.ModelExecutions.Status;
import io.agentscope.durable.TransactionalModelExecutionStore;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.TransactionalSessionBackend.StoredContent;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.store.ModelOutputStore;
import io.agentscope.durable.store.ObjectModelOutputStore;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.RecordObjectStore;
import io.agentscope.extensions.durable.jdbc.FileObjectStore;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ModelWorkerTest {
    private static final SessionKey SESSION = new SessionKey("tenant", "worker");
    private static final ThreadKey THREAD = new ThreadKey(SESSION, "main");
    private static final String ROUTE = "test-model";

    @TempDir Path directory;

    @Test
    void directModelUsesTheExistingJournalSizeThreshold() {
        var longText = "x".repeat(17_000);
        var model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "fixture";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(chunk("small-"), chunk(longText));
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> ReActAgent.builder().name("agent").model(model), Map.of());
        try (var records = JdbcRecordStore.sqlite(directory.resolve("direct.db"))) {
            var objects = new FileObjectStore(directory.resolve("objects"));
            try (var runtime =
                    DurableRuntime.builder()
                            .backend(new TransactionalSessionBackend(records, objects))
                            .harness("agent", "v1", adapter)
                            .build()) {
                var turn = runtime.createSession(SESSION, "agent").submit("direct", "hello");
                runtime.tick(THREAD);
                assertEquals(TurnStatus.SUCCEEDED, turn.status().status(), turn.status()::toString);
                assertEquals(
                        "small-" + longText,
                        turn.status().output().path("content").get(0).path("text").asText());
                assertEquals(List.of(false, true), journalChunkReferences(records, objects));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "objects", "offsets"})
    @Timeout(25)
    void workerPersistsBatchesForAnotherAgent(String outputBackend) throws Exception {
        var sqlite = directory.resolve("shared.db");
        var offsets = new CopyOnWriteArrayList<Batch>();
        var wakeups = Wakeups.local();
        var local = new AgentScopeAdapterTest.ScriptedModel(false);
        var options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("lookup")).build();
        String turnId;
        Key call;
        try (var records = JdbcRecordStore.sqlite(sqlite)) {
            var objects = objects(outputBackend, records);
            var ledger = ledger(records, objects, outputBackend, offsets, wakeups);
            try (var runtime =
                    runtime(records, objects, ledger, wakeups, LiveEvents.none(), local, options)) {
                var turn = runtime.createSession(SESSION, "agent").submit("turn", "original input");
                turnId = turn.turnId();
                runtime.tick(THREAD);
                assertEquals(TurnStatus.WAITING, turn.status().status(), turn.status()::toString);
                call = ledger.ready(ROUTE, 5).get(0);
                assertEquals(turnId, call.turnId());
            }
        }
        var providerCalls = new AtomicInteger();
        var lastChunk = Sinks.<ChatResponse>one();
        var provider =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "fixture";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions prepared) {
                        providerCalls.incrementAndGet();
                        assertTrue(
                                messages.stream()
                                        .anyMatch(
                                                m -> "original input".equals(m.getTextContent())));
                        assertEquals(options.getToolChoice(), prepared.getToolChoice());
                        return Flux.concat(Flux.just(chunk("first-")), lastChunk.asMono());
                    }
                };
        try (var workerRecords = JdbcRecordStore.sqlite(sqlite);
                var readerRecords = JdbcRecordStore.sqlite(sqlite)) {
            var workerObjects = objects(outputBackend, workerRecords);
            var readerObjects = objects(outputBackend, readerRecords);
            var workerLedger =
                    ledger(workerRecords, workerObjects, outputBackend, offsets, wakeups);
            // A lost notification cannot hide a committed batch or its ready Turn.
            var readerLedger =
                    ledger(readerRecords, readerObjects, outputBackend, offsets, Wakeups.none());
            try (var worker =
                            new ModelWorker(
                                    workerLedger,
                                    ROUTE,
                                    ModelExecutor.using(
                                            name -> {
                                                assertEquals("fixture", name);
                                                return provider;
                                            }));
                    var replacement =
                            runtime(
                                    readerRecords,
                                    readerObjects,
                                    readerLedger,
                                    Wakeups.none(),
                                    LiveEvents.none(),
                                    local,
                                    options)) {
                worker.start();
                var firstDelta = new CountDownLatch(1);
                var live = LiveEvents.local();
                try (var intermediateRecords = JdbcRecordStore.sqlite(sqlite)) {
                    var intermediateObjects = objects(outputBackend, intermediateRecords);
                    var intermediateLedger =
                            ledger(
                                    intermediateRecords,
                                    intermediateObjects,
                                    outputBackend,
                                    offsets,
                                    Wakeups.none());
                    try (var subscription =
                                    live.subscribe(
                                            event -> {
                                                if ("message.delta".equals(event.type())
                                                        && "first-"
                                                                .equals(
                                                                        event.data()
                                                                                .path("text")
                                                                                .asText()))
                                                    firstDelta.countDown();
                                            });
                            var intermediate =
                                    runtime(
                                            intermediateRecords,
                                            intermediateObjects,
                                            intermediateLedger,
                                            Wakeups.none(),
                                            live,
                                            local,
                                            options)) {
                        intermediate.start();
                        assertTrue(firstDelta.await(8, TimeUnit.SECONDS));
                        var view = intermediateLedger.query(call);
                        assertEquals(Status.RUNNING, view.status());
                        assertEquals(1, view.nextSequence());
                    }
                }
                var turn = replacement.getSession(SESSION).getTurn(turnId);
                long end = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                assertEquals(Sinks.EmitResult.OK, lastChunk.tryEmitValue(chunk("second")));
                while (!turn.status().status().terminal() && System.nanoTime() < end) {
                    replacement.tick(THREAD);
                    Thread.sleep(10);
                }
                assertEquals(TurnStatus.SUCCEEDED, turn.status().status(), turn.status()::toString);
                assertEquals(
                        "first-second",
                        turn.status().output().path("content").get(0).path("text").asText());
                var view = readerLedger.query(call);
                assertEquals(Status.SUCCEEDED, view.outcome().status());
                assertEquals(2, view.nextSequence());
                var first = readerLedger.readOutput(call, view.generation(), 0);
                assertEquals(
                        "first-",
                        first.chunks().get(0).path("content").get(0).path("text").asText());
                var second = readerLedger.readOutput(call, view.generation(), first.nextSequence());
                assertNotNull(second);
                assertEquals(1, providerCalls.get());
                assertTrue(local.inputs.isEmpty());
                var chunkReferences = journalChunkReferences(readerRecords, readerObjects);
                assertTrue(chunkReferences.size() >= 2);
                boolean external = !"sqlite".equals(outputBackend);
                assertTrue(chunkReferences.stream().allMatch(reference -> reference == external));
                if (!"sqlite".equals(outputBackend)) {
                    readerRecords.transaction(
                            SESSION.storageKey(),
                            tx -> {
                                var parts = tx.keys("model-execution:", null, 50);
                                assertTrue(
                                        parts.stream().anyMatch(part -> part.contains(":batch:")));
                                assertFalse(
                                        parts.stream()
                                                .map(tx::get)
                                                .map(JsonNode::toString)
                                                .anyMatch(value -> value.contains("first-")));
                                return null;
                            });
                }
            }
        }
    }

    @Test
    @Timeout(10)
    void liveLeaseRejectsDuplicateAndExpiredProviderIsUnknown() throws Exception {
        try (var records = JdbcRecordStore.sqlite(directory.resolve("leases.db"))) {
            var objects = new RecordObjectStore(records);
            var ledger =
                    new TransactionalModelExecutionStore(
                            records, new ObjectModelOutputStore(objects), Wakeups.none());
            try (var runtime =
                    runtime(
                            records,
                            objects,
                            ledger,
                            Wakeups.none(),
                            LiveEvents.none(),
                            new AgentScopeAdapterTest.ScriptedModel(false),
                            GenerateOptions.builder().build())) {
                var turn = runtime.createSession(SESSION, "agent").submit("one", "hello");
                runtime.tick(THREAD);
                var call = ledger.ready(ROUTE, 1).get(0);
                var lease = ledger.claim(call, "original", Duration.ofMillis(500)).orElseThrow();
                ledger.begin(lease);
                assertTrue(ledger.claim(call, "duplicate", Duration.ofSeconds(1)).isEmpty());
                while (ledger.ready(ROUTE, 1).isEmpty()) Thread.sleep(10);
                assertTrue(ledger.claim(call, "takeover", Duration.ofSeconds(1)).isEmpty());
                assertEquals(Status.UNKNOWN, ledger.query(call).outcome().status());
                var late =
                        new Batch(
                                call, lease.epoch(), 0, List.of(DurableJson.value(chunk("stale"))));
                var error = assertThrows(DurableException.class, () -> ledger.append(lease, late));
                assertEquals(DurableException.Code.FENCED, error.code());
                runtime.tick(THREAD);
                assertEquals(TurnStatus.FAILED, turn.status().status());
            }
        }
    }

    private static List<Boolean> journalChunkReferences(
            JdbcRecordStore records, ObjectStore objects) {
        var prefix = "thread-data:" + DurableJson.hash(THREAD.threadId()) + ":journal:";
        var journal =
                records.transaction(
                        SESSION.storageKey(),
                        tx ->
                                tx.keys(prefix, null, 1000).stream()
                                        .map(tx::get)
                                        .map(
                                                value ->
                                                        DurableJson.convert(
                                                                value, StoredContent.class))
                                        .toList());
        return journal.stream()
                .filter(
                        stored -> {
                            var event =
                                    stored.reference() == null
                                            ? stored.inline()
                                            : DurableJson.parse(
                                                    new String(
                                                            objects.read(stored.reference()),
                                                            StandardCharsets.UTF_8));
                            return "model/chunk".equals(event.path("type").asText());
                        })
                .map(stored -> stored.reference() != null)
                .toList();
    }

    private ObjectStore objects(String type, JdbcRecordStore records) {
        return "sqlite".equals(type)
                ? new RecordObjectStore(records)
                : new FileObjectStore(directory.resolve("objects"));
    }

    private static TransactionalModelExecutionStore ledger(
            JdbcRecordStore records,
            ObjectStore objects,
            String type,
            List<Batch> offsets,
            Wakeups wakeups) {
        ModelOutputStore outputs =
                "offsets".equals(type)
                        ? new ModelOutputStore() {
                            @Override
                            public JsonNode write(Batch batch) {
                                synchronized (offsets) {
                                    int offset = offsets.size();
                                    offsets.add(batch);
                                    return DurableJson.value(
                                            Map.of("partition", 0, "offset", offset));
                                }
                            }

                            @Override
                            public Batch read(JsonNode locator) {
                                return offsets.get(locator.path("offset").asInt());
                            }
                        }
                        : new ObjectModelOutputStore(objects);
        return new TransactionalModelExecutionStore(records, outputs, wakeups);
    }

    private static DurableRuntime runtime(
            JdbcRecordStore records,
            ObjectStore objects,
            TransactionalModelExecutionStore ledger,
            Wakeups wakeups,
            LiveEvents live,
            Model local,
            GenerateOptions options) {
        var adapter =
                AgentScopeAdapter.forReAct(
                                binding ->
                                        ReActAgent.builder()
                                                .name("agent")
                                                .model(local)
                                                .generateOptions(options),
                                Map.of())
                        .withModelExecutions(ledger, ROUTE, Duration.ofSeconds(10));
        return DurableRuntime.builder()
                .backend(new TransactionalSessionBackend(records, objects).withWakeups(wakeups))
                .liveEvents(live)
                .harness("agent", "v1", adapter)
                .build();
    }

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .id("response")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
