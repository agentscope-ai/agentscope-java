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
package io.agentscope.extensions.durable.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.CommittedEventOutbox;
import io.agentscope.durable.DurableTypes.CommandReceipt;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.contract.UserApiContract;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DurableUserApiTest extends UserApiContract {
    @TempDir protected Path directory;

    private String schema;
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");

    @Test
    void committedEventOutboxReusesEventIdentityAfterFailedDeliveryAndReopen() throws Exception {
        var directoryPath = directory.resolve("hooks");
        String cursor;
        var seen = new ArrayList<String>();
        int captured;
        try (var backend = LocalBackend.open(directoryPath);
                var api = connect(backend)) {
            var session = api.createSession(key, "agent");
            cursor = session.snapshot().cursor();
            session.submit("request", "user input");
            var outbox =
                    new CommittedEventOutbox(
                            session,
                            "webhook",
                            EventFilter.all(),
                            cursor,
                            backend.localStorage().orElseThrow().records(),
                            Duration.ofSeconds(30),
                            Duration.ofMillis(1));
            captured = outbox.capture(25);
            assertTrue(captured > 0);
            backend.pruneEvents(key, session.snapshot().cursor());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            outbox.deliver(
                                    event -> {
                                        seen.add(event.eventId());
                                        throw new IllegalStateException("Web endpoint unavailable");
                                    }));
        }
        try (var backend = LocalBackend.open(directoryPath);
                var api = connect(backend)) {
            var outbox =
                    new CommittedEventOutbox(
                            api.getSession(key),
                            "webhook",
                            EventFilter.all(),
                            cursor,
                            backend.localStorage().orElseThrow().records(),
                            Duration.ofSeconds(30),
                            Duration.ofMillis(1));
            assertEquals(0, outbox.capture(25));
            for (int i = 0; i < captured; i++)
                assertTrue(outbox.deliver(event -> seen.add(event.eventId())));
            assertEquals(seen.get(0), seen.get(1));
            assertEquals(captured, seen.stream().distinct().count());
            assertFalse(outbox.deliver(event -> seen.add(event.eventId())));
        }
    }

    @Test
    void convertedInputAndReceiptSurviveReopeningLocalBackend() {
        Path data = directory.resolve("local");
        String activeId;
        String queuedId;
        CommandReceipt receipt;
        Snapshot snapshot;
        try (var local = LocalBackend.open(data);
                var localApi = connect(local)) {
            var thread = localApi.createSession(key, "agent").mainThread();
            activeId = thread.submit("active", "First").turnId();
            queuedId = thread.submit("queued", "Second").turnId();
            local.claim(thread.key(), "owner", Duration.ofSeconds(5)).orElseThrow();
            receipt = thread.convertQueuedToSteer("convert", queuedId, activeId, 0);
            snapshot = localApi.getSession(key).snapshot();
        }
        try (var reopened = LocalBackend.open(data);
                var reopenedApi = connect(reopened)) {
            var recovered = reopenedApi.getSession(key).mainThread();
            assertEquals(receipt, recovered.convertQueuedToSteer("convert", queuedId, activeId, 0));
            assertEquals(TurnStatus.CONVERTED, recovered.getTurn(queuedId).status().status());
            assertEquals(snapshot, reopenedApi.getSession(key).snapshot());
        }
    }

    @Override
    protected SessionBackend openBackend() {
        if (postgresUrl == null)
            return new TransactionalSessionBackend(
                    JdbcRecordStore.sqlite(directory.resolve("user.db")));
        String created = "durable_user_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgresUrl);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + created);
            schema = created;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        return new TransactionalSessionBackend(JdbcRecordStore.postgres(postgresUrl, properties));
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (schema != null) {
            if (api != null) api.close();
            if (backend != null) backend.close();
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
