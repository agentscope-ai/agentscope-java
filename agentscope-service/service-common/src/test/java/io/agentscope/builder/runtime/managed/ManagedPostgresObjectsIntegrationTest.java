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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.persistence.ServiceSchema;
import io.agentscope.core.session.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL with separate replica connections, without a shared filesystem. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresObjectsIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    private ManagedSchemaMigrationIntegrationTest.TemporaryDatabase database;
    private ManagedPostgresObjects first;
    private ManagedPostgresObjects second;
    private JdbcTemplate jdbc;

    @BeforeEach
    void start() throws Exception {
        database = new ManagedSchemaMigrationIntegrationTest.TemporaryDatabase();
        try (var owner = database.connect()) {
            ServiceSchema.migrate(owner, "managed", "ma");
            String url = owner.getMetaData().getURL();
            var source = new DriverManagerDataSource(url, "managed", "managed-local-test");
            first = new ManagedPostgresObjects(source);
            second =
                    new ManagedPostgresObjects(
                            new DriverManagerDataSource(url, "managed", "managed-local-test"));
            jdbc = new JdbcTemplate(source);
        }
    }

    @AfterEach
    void close() throws Exception {
        if (database != null) database.close();
    }

    @Test
    void anotherReplicaValidatesAndRestoresACompleteCheckpoint() throws Exception {
        String state = "{\"summary\":\"" + "private state ".repeat(20000) + "\"}";
        var event =
                new SessionEvent(
                        1,
                        "capture",
                        1,
                        123,
                        "state/checkpoint",
                        "run",
                        "turn",
                        true,
                        mapper.writeValueAsString(Map.of("stateJson", state, "reason", "test")));
        var batch =
                new ManagedRuntimeBatch(
                        scope,
                        "turn",
                        7,
                        "lease",
                        "writer",
                        "batch",
                        0,
                        List.of(event),
                        List.of(),
                        List.of(),
                        null);
        var brain = new ManagedCheckpointObjects(mapper, first);
        var hands = new ManagedCheckpointObjects(mapper, second);
        var wire = brain.prepare(batch);
        assertFalse(mapper.writeValueAsString(wire).contains("private state"));
        hands.verify(wire);
        assertEquals(event, hands.hydrate(scope, wire.events().get(0)));
        assertEquals(state, hands.state(hands.reference(scope, wire.events().get(0))));
        assertThrows(
                ManagedStateException.class,
                () ->
                        hands.hydrate(
                                new ManagedScope("other tenant", "namespace", "session"),
                                wire.events().get(0)));
    }

    @Test
    void oversizedToolResultsCrossReplicasButSmallMessagesStayInline() {
        var writer = new ManagedBatchCodec(mapper, first);
        var reader = new ManagedBatchCodec(mapper, second);
        var small = new ManagedToolResult(scope, "small", "execution", "succeeded", "ok");
        assertEquals(small, reader.decodeToolResult(writer.encode(small)));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM ma.private_object", Integer.class));
        var large =
                new ManagedToolResult(scope, "large", "execution", "succeeded", "x".repeat(300000));
        var message = writer.encode(large);
        assertFalse(new String(message.body(), StandardCharsets.UTF_8).contains("xxx"));
        assertEquals(large, reader.decodeToolResult(message));
        assertEquals(
                1, jdbc.queryForObject("SELECT count(*) FROM ma.private_object", Integer.class));
        assertArrayEquals(message.body(), writer.encode(large).body());
        jdbc.update("DELETE FROM ma.private_object");
        assertThrows(ManagedStateException.class, () -> reader.decodeToolResult(message));
    }

    @Test
    void conflictingConcurrentCapturesCannotReplaceTheWinner() throws Exception {
        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<byte[]> one = () -> put(gate, first, "one");
            Callable<byte[]> two = () -> put(gate, second, "two");
            var a = pool.submit(one);
            var b = pool.submit(two);
            var left = a.get();
            var right = b.get();
            assertNotEquals(left == null, right == null);
            assertArrayEquals(
                    left == null ? right : left,
                    second.read(scope.messageGroup(), "checkpoint", "capture"));
        } finally {
            pool.shutdownNow();
        }
    }

    private byte[] put(CyclicBarrier gate, ManagedPostgresObjects store, String value)
            throws Exception {
        gate.await();
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        try {
            store.put(scope.messageGroup(), "checkpoint", "capture", content);
            return content;
        } catch (ManagedStateException conflict) {
            assertEquals("runtime_object_conflict", conflict.code());
            return null;
        }
    }

    @Test
    void scopeIsolationAndCorruptionAreEnforced() {
        byte[] content = "original".getBytes(StandardCharsets.UTF_8);
        first.put(scope.messageGroup(), "batch", "object", content);
        first.put(scope.messageGroup(), "batch", "object", content);
        assertThrows(
                ManagedStateException.class, () -> second.read("other-scope", "batch", "object"));
        assertThrows(
                ManagedStateException.class,
                () -> second.read(scope.messageGroup(), "checkpoint", "object"));
        jdbc.update("UPDATE ma.private_object SET payload=?,size_bytes=?", new byte[] {1, 2}, 2);
        assertThrows(
                ManagedStateException.class,
                () -> second.read(scope.messageGroup(), "batch", "object"));
    }
}
