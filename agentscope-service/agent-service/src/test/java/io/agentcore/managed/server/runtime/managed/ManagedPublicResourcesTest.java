/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedPublicResourcesTest {
    private final ManagedScope scope = new ManagedScope("t", "n", "s-" + UUID.randomUUID());
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong(1000);
    private ManagedPublicResources resources;
    private DataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private long sequence;
    private ManagedQueryViews views;

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        new ManagedAdmissionStore(source, mapper).createSession(scope, "{}");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        views = ProjectionTestSupport.views(source, mapper);
        resources = new ManagedPublicResources(source, mapper);
        resources.queryViews(views);
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:resources-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void operationCompletionKeepsTheModelToolWaitingAndRetainsDetailsAfterItsCompletion() {
        event(
                "tool.requested",
                Map.of("turn_id", "turn", "tool_call_id", "call", "name", "execute"));
        event(
                "tool.waiting",
                Map.of("turn_id", "turn", "tool_call_id", "call", "status", "waiting"));
        event(
                "tool.operation.completed",
                Map.of(
                        "turn_id",
                        "turn",
                        "tool_call_id",
                        "call",
                        "task_id",
                        "task",
                        "operation_tool_call_id",
                        "call_0",
                        "name",
                        "sandbox.exec",
                        "status",
                        "succeeded"));
        var waiting = resources.snapshot(scope);
        var page = resources.page(scope, waiting.id(), "tools", null, 10).data();
        assertEquals(1, page.size());
        var before = (Map<?, ?>) page.get(0).get("data");
        assertEquals("execute", before.get("name"));
        assertEquals("waiting", before.get("status"));
        event(
                "tool.completed",
                Map.of("turn_id", "turn", "tool_call_id", "call", "status", "completed"));
        event(
                "tool.operation.completed",
                Map.of(
                        "turn_id",
                        "turn",
                        "tool_call_id",
                        "call",
                        "task_id",
                        "task",
                        "name",
                        "sandbox.exec",
                        "status",
                        "succeeded"));
        var current = resources.snapshot(scope);
        var after =
                (Map<?, ?>)
                        resources
                                .page(scope, current.id(), "tools", null, 10)
                                .data()
                                .get(0)
                                .get("data");
        assertEquals("execute", after.get("name"));
        assertEquals("completed", after.get("status"));
        assertEquals(1, ((Map<?, ?>) after.get("operations")).size());
        assertEquals(
                "waiting",
                ((Map<?, ?>)
                                resources
                                        .page(scope, waiting.id(), "tools", null, 10)
                                        .data()
                                        .get(0)
                                        .get("data"))
                        .get("status"));
    }

    @Test
    void pinnedPagesSurviveUpdatesDeletesNewRowsAndAnotherReaderInstance() {
        item("a", "before");
        item("b", "second");
        event(
                "required_action.created",
                Map.of(
                        "request_id",
                        "action",
                        "kind",
                        "confirmation",
                        "tool_call",
                        Map.of("id", "call", "name", "shell")));
        var snapshot = resources.snapshot(scope);
        assertEquals(snapshot.sequence(), resources.snapshot(scope).sequence());
        var first = resources.page(scope, snapshot.id(), "items", null, 1);
        assertTrue(first.hasMore());
        assertEquals("before", message(first.data().get(0)));
        item("b", "after");
        item("c", "new");
        event("required_action.resolved", Map.of("request_id", "action"));
        var other = new ManagedPublicResources(source, mapper);
        other.queryViews(views);
        var second = other.page(scope, snapshot.id(), "items", first.nextCursor(), 1);
        assertEquals(snapshot, second.snapshot());
        assertEquals("second", message(second.data().get(0)));
        assertFalse(second.hasMore());
        assertEquals(
                1, other.page(scope, snapshot.id(), "required_actions", null, 10).data().size());
        var latest = resources.snapshot(scope);
        var current = resources.page(scope, latest.id(), "items", null, 10).data();
        assertEquals(3, current.size());
        assertEquals("after", message(current.get(1)));
        assertTrue(
                resources.page(scope, latest.id(), "required_actions", null, 10).data().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> resources.page(scope, latest.id(), "items", first.nextCursor(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> resources.page(scope, snapshot.id(), "tools", first.nextCursor(), 1));
        var otherScope = new ManagedScope("t", "n", "different");
        new ManagedAdmissionStore(source, mapper).createSession(otherScope, "{}");
        assertThrows(
                ManagedStateException.class,
                () -> resources.page(otherScope, snapshot.id(), "items", first.nextCursor(), 1));
    }

    @Test
    void usageIsIncrementalAndFrozenWithoutDoubleCountingModelUpdates() {
        event(
                "usage.recorded",
                Map.of(
                        "model_call_id",
                        "model-one",
                        "usage",
                        Map.of("input_tokens", 10, "time", 1.5)));
        var first = resources.snapshot(scope);
        event(
                "usage.recorded",
                Map.of(
                        "model_call_id",
                        "model-one",
                        "usage",
                        Map.of("input_tokens", 20, "time", 2.0)));
        event(
                "usage.recorded",
                Map.of(
                        "model_call_id",
                        "model-two",
                        "usage",
                        Map.of("input_tokens", 5, "time", 0.5)));
        var second = resources.snapshot(scope);
        assertEquals(1, ((Number) first.usage().get("model_calls")).intValue());
        assertEquals(
                10, ((Number) map(first.usage().get("totals")).get("input_tokens")).intValue());
        assertEquals(2, ((Number) second.usage().get("model_calls")).intValue());
        assertEquals(
                25, ((Number) map(second.usage().get("totals")).get("input_tokens")).intValue());
        assertEquals(2.5, map(second.usage().get("totals")).get("time"));
        assertEquals(
                first.usage(),
                resources.page(scope, first.id(), "items", null, 10).snapshot().usage());
    }

    @Test
    void runSettlementUsesTheSameFrozenWatermarkAsTheItemPage() {
        event("run.started", Map.of("run_id", "run", "status", "running"));
        event(
                "item.started",
                Map.of(
                        "run_id",
                        "run",
                        "item_id",
                        "a",
                        "item",
                        Map.of("id", "a", "status", "in_progress", "content", List.of())));
        var before = resources.snapshot(scope);
        event("run.ended", Map.of("run_id", "run", "status", "failed"));
        var after = resources.snapshot(scope);
        assertEquals(
                "in_progress",
                map(map(resources
                                        .page(scope, before.id(), "items", null, 1)
                                        .data()
                                        .get(0)
                                        .get("data"))
                                .get("item"))
                        .get("status"));
        assertEquals(
                "incomplete",
                map(map(resources
                                        .page(scope, after.id(), "items", null, 1)
                                        .data()
                                        .get(0)
                                        .get("data"))
                                .get("item"))
                        .get("status"));
    }

    @Test
    void signedSnapshotsNeedNoExecutionDatabaseRowsOrCapacity() {
        item("a", "old");
        var snapshot = resources.snapshot(scope);
        item("a", "new");
        for (int i = 0; i < 40; i++) assertNotNull(resources.snapshot(scope));
        assertEquals(
                "old",
                message(resources.page(scope, snapshot.id(), "items", null, 1).data().get(0)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.snapshot", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.public_resource_version", Integer.class));
    }

    @Test
    void invalidResourceCannotAdvanceVisiblePrefix() {
        item("a", "old");
        assertThrows(
                IllegalArgumentException.class,
                () -> event("item.completed", Map.of("item_id", "b".repeat(513))));
        assertEquals(1, views.progress(scope).visibleSequence());
    }

    @Test
    void turnSnapshotsFilterResourcesAndFreezeOnlyTheirOwnUsage() {
        var admission = new ManagedAdmissionStore(source, mapper);
        String first = admission.acceptTurn(scope, "actor", "first", "{}").id();
        String second = admission.acceptTurn(scope, "actor", "second", "{}").id();
        event(
                "item.completed",
                Map.of(
                        "turn_id",
                        first,
                        "item_id",
                        "first-item",
                        "item",
                        Map.of("id", "first-item", "content", List.of())));
        event(
                "usage.recorded",
                Map.of(
                        "turn_id",
                        first,
                        "model_call_id",
                        "first-model",
                        "usage",
                        Map.of("input_tokens", 10)));
        event(
                "item.completed",
                Map.of(
                        "turn_id",
                        second,
                        "item_id",
                        "second-item",
                        "item",
                        Map.of("id", "second-item", "content", List.of())));
        event(
                "usage.recorded",
                Map.of(
                        "turn_id",
                        second,
                        "model_call_id",
                        "second-model",
                        "usage",
                        Map.of("input_tokens", 99)));
        var pinned = resources.snapshot(scope, first);
        assertEquals(first, pinned.turnId());
        assertEquals(1, resources.page(scope, pinned.id(), "items", null, 10).data().size());
        assertEquals(
                10, ((Number) map(pinned.usage().get("totals")).get("input_tokens")).intValue());
        assertEquals(
                109,
                ((Number)
                                map(resources.usage(scope, null).data().get("totals"))
                                        .get("input_tokens"))
                        .intValue());
        event(
                "usage.recorded",
                Map.of(
                        "turn_id",
                        first,
                        "model_call_id",
                        "first-model",
                        "usage",
                        Map.of("input_tokens", 20)));
        var newer = resources.snapshot(scope, first);
        assertEquals(
                20, ((Number) map(newer.usage().get("totals")).get("input_tokens")).intValue());
        assertEquals(
                10,
                ((Number)
                                map(resources
                                                .page(scope, pinned.id(), "items", null, 10)
                                                .snapshot()
                                                .usage()
                                                .get("totals"))
                                        .get("input_tokens"))
                        .intValue());
        assertEquals(
                2,
                resources
                        .page(scope, resources.snapshot(scope).id(), "items", null, 10)
                        .data()
                        .size());
        assertTrue(
                resources
                        .page(
                                scope,
                                resources.snapshot(scope, "foreign-turn").id(),
                                "items",
                                null,
                                10)
                        .data()
                        .isEmpty());
    }

    private void item(String id, String text) {
        event(
                "item.completed",
                Map.of(
                        "item_id",
                        id,
                        "item",
                        Map.of(
                                "id",
                                id,
                                "status",
                                "completed",
                                "content",
                                List.of(Map.of("type", "text", "text", text)))));
    }

    private void event(String type, Map<String, Object> payload) {
        long next = ++sequence;
        var complete = new LinkedHashMap<String, Object>(payload);
        complete.putIfAbsent("turn_id", "fixture-turn");
        String raw = mapper.valueToTree(complete).toString();
        assertTrue(
                views.apply(
                        scope,
                        List.of(
                                new ManagedSessionProjections.Fact(
                                        next,
                                        "event-" + next,
                                        type,
                                        raw,
                                        ManagedScope.fingerprint(type, raw),
                                        clock.get()))));
    }

    private static String message(Map<String, Object> envelope) {
        return (String)
                map(((List<?>) map(map(envelope.get("data")).get("item")).get("content")).get(0))
                        .get("text");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
