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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedQueryViewsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("tenant", "namespace", "session-" + UUID.randomUUID());
    private ManagedQueryViews views;
    private JdbcTemplate jdbc;
    private SessionProjectionStore store;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:views-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        store = spy(backend(source));
        views =
                new ManagedQueryViews(
                        store,
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        2,
                        new ManagedPublicResources(source, mapper));
    }

    protected SessionProjectionStore backend(DataSource source) {
        return new PostgresSessionProjectionStore(source, mapper);
    }

    private ManagedSessionProjections.Fact fact(long seq, String type, Map<String, Object> data)
            throws Exception {
        var json = mapper.writeValueAsString(data);
        return new ManagedSessionProjections.Fact(
                seq, "e" + seq, type, json, ManagedScope.fingerprint(type, json), 1);
    }

    @Test
    void manifestBatchMatchesSingleCommitReductionAndRejectsMissingInteriorBoundary()
            throws Exception {
        var first =
                fact(
                        1,
                        "tool.requested",
                        Map.of("turn_id", "turn", "tool_call_id", "call", "name", "execute"));
        var last =
                fact(
                        2,
                        "tool.completed",
                        Map.of(
                                "turn_id",
                                "turn",
                                "tool_call_id",
                                "call",
                                "name",
                                "execute",
                                "result",
                                "done"));
        var before = views.progress(scope);
        assertTrue(
                views.applyBatch(
                        scope,
                        before,
                        List.of(
                                new ManagedProjectionFacts.Page(1, 1, List.of(first)),
                                new ManagedProjectionFacts.Page(2, 1, List.of()),
                                new ManagedProjectionFacts.Page(3, 2, List.of(last)))));
        assertEquals(3, views.progress(scope).commitVersion());
        assertEquals(1, views.progress(scope).version());
        assertEquals(2, views.events(scope, 0, 10).size());
        assertThrows(
                ManagedStateException.class,
                () ->
                        views.applyBatch(
                                scope,
                                views.progress(scope),
                                List.of(
                                        new ManagedProjectionFacts.Page(4, 3, List.of()),
                                        new ManagedProjectionFacts.Page(5, 3, List.of()))));
        assertEquals(3, views.progress(scope).commitVersion());
    }

    @Test
    void reducerNeedsNoExecutionTablesAndPinnedPagesSurviveUpdates() throws Exception {
        assertTrue(
                views.apply(
                        scope,
                        List.of(
                                fact(
                                        1,
                                        "tool.requested",
                                        Map.of(
                                                "turn_id",
                                                "turn",
                                                "tool_call_id",
                                                "call",
                                                "name",
                                                "execute")))));
        var pinned = views.snapshot(scope, null);
        assertTrue(
                views.apply(
                        scope,
                        List.of(
                                fact(
                                        2,
                                        "tool.completed",
                                        Map.of(
                                                "turn_id",
                                                "turn",
                                                "tool_call_id",
                                                "call",
                                                "status",
                                                "completed")))));
        var before = views.page(scope, pinned.id(), "tools", null, 10).data();
        assertEquals(1, before.size());
        assertEquals("requested", ((Map<?, ?>) before.get(0).get("data")).get("status"));
        assertEquals(
                "completed",
                ((Map<?, ?>) views.resource(scope, null, "tools", "turn:call").get("data"))
                        .get("status"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " lower(table_schema)='ma'",
                        Integer.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> views.page(scope, pinned.id() + "x", "tools", null, 10));
        assertThrows(
                ManagedStateException.class,
                () ->
                        views.page(
                                new ManagedScope("other", "namespace", "session"),
                                pinned.id(),
                                "tools",
                                null,
                                10));
    }

    @Test
    void batchReducerUsesPriorEventsBeforePublishingAndRejectsGaps() throws Exception {
        var facts =
                List.of(
                        fact(
                                1,
                                "tool.requested",
                                Map.of("turn_id", "turn", "tool_call_id", "a", "name", "first")),
                        fact(
                                2,
                                "tool.requested",
                                Map.of("turn_id", "turn", "tool_call_id", "b", "name", "second")),
                        fact(
                                3,
                                "tool.completed",
                                Map.of(
                                        "turn_id",
                                        "turn",
                                        "tool_call_id",
                                        "a",
                                        "status",
                                        "completed")));
        assertTrue(views.apply(scope, facts));
        assertFalse(views.apply(scope, facts));
        var snap = views.snapshot(scope, "turn");
        var page = views.page(scope, snap.id(), "tools", null, 1);
        assertTrue(page.hasMore());
        assertNotNull(page.nextCursor());
        var next = views.page(scope, snap.id(), "tools", page.nextCursor(), 1);
        assertEquals(1, next.data().size());
        assertFalse(next.hasMore());
        assertThrows(
                IllegalArgumentException.class,
                () -> views.page(scope, snap.id(), "items", page.nextCursor(), 1));
        assertThrows(
                ManagedStateException.class,
                () ->
                        views.apply(
                                scope,
                                List.of(fact(5, "turn.completed", Map.of("turn_id", "turn")))));
        assertEquals(3, views.progress(scope).visibleSequence());
    }

    @Test
    void protocolHistoryAndReliableStreamUsePublishedPrefixWithoutExecutionTables()
            throws Exception {
        assertEquals(0, views.protocol().head(scope));
        var first =
                fact(
                        1,
                        "protocol.event",
                        Map.of(
                                "id",
                                "user",
                                "type",
                                "user.message",
                                "processed_at",
                                "2026-10-25T00:00:00Z",
                                "content",
                                List.of(Map.of("type", "text", "text", "hello"))));
        var second =
                fact(
                        2,
                        "item.finalized",
                        Map.of(
                                "turn_id",
                                "turn",
                                "item_id",
                                "i1",
                                "item",
                                Map.of(
                                        "role",
                                        "assistant",
                                        "message_id",
                                        "message",
                                        "content",
                                        List.of(Map.of("type", "text", "text", "answer")))));
        views.apply(scope, List.of(first, second));
        var query = views.protocol();
        var page =
                query.history(
                        scope,
                        "user",
                        Map.of(
                                "limit",
                                List.of("1"),
                                "types[]",
                                List.of("user.message", "agent.message")),
                        true);
        assertEquals(1, ((List<?>) page.get("data")).size());
        var cursor = (String) page.get("next_page");
        assertNotNull(cursor);
        var before = query.changes(scope, 0, 256);
        assertEquals(3, before.size());
        assertEquals("user.message", before.get(0).event().get("type"));
        assertEquals("agent.message", before.get(1).event().get("type"));
        long high = query.head(scope);
        views.apply(scope, List.of(fact(3, "turn.completed", Map.of("turn_id", "turn"))));
        assertTrue(
                query.changes(scope, high, 256).stream()
                        .anyMatch(
                                c ->
                                        "managed_agents.turn.completed"
                                                .equals(c.event().get("type"))));
        var pinned =
                query.history(
                        scope,
                        "user",
                        Map.of(
                                "limit",
                                List.of("1"),
                                "page",
                                List.of(cursor),
                                "types[]",
                                List.of("user.message", "agent.message")),
                        true);
        assertEquals(1, ((List<?>) pinned.get("data")).size());
        assertEquals(null, pinned.get("next_page"));
        assertThrows(
                ManagedStateException.class,
                () -> query.history(scope, "other", Map.of("page", List.of(cursor)), true));
        assertEquals(1, query.outputIds(scope, "turn").size());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " lower(table_schema)='ma'",
                        Integer.class));
    }

    @Test
    void protocolReplayAfterPartialWriteUsesOriginalFactTimeAndPayload() throws Exception {
        var once = new AtomicBoolean();
        doAnswer(
                        call -> {
                            if (once.compareAndSet(false, true))
                                throw new ManagedTransportException("Crash before query CAS");
                            return call.callRealMethod();
                        })
                .when(store)
                .advanceBatch(any(), any());
        var facts =
                List.of(
                        fact(
                                1,
                                "item.finalized",
                                Map.of(
                                        "turn_id",
                                        "t",
                                        "item_id",
                                        "i",
                                        "item",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "message_id",
                                                "m",
                                                "content",
                                                List.of(
                                                        Map.of(
                                                                "type", "text", "text",
                                                                "answer"))))));
        assertThrows(ManagedTransportException.class, () -> views.apply(scope, 1, facts));
        assertEquals(0, views.progress(scope).visibleSequence());
        assertTrue(views.protocol().changes(scope, 0, 256).isEmpty());
        Thread.sleep(5);
        assertTrue(views.apply(scope, 1, facts));
        assertEquals(2, views.protocol().changes(scope, 0, 256).size());
    }
}
