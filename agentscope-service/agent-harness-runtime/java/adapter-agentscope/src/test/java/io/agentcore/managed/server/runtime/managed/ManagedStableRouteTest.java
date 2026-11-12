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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ManagedStableRouteTest {
    @Test
    void toolLogicalGroupRemainsDistinctFromItsPersistedSessionRoute() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:route-tool-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedStableRouteStore.initialize(source);
        var routing = new ManagedStableRouting(source, "cell", 1, 64);
        var scope = new ManagedScope("t", "n", "session");
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(ignored -> routing.assign(scope));
        var codec = new ManagedBatchCodec(new ObjectMapper(), mock(RuntimeObjectStore.class));
        var task =
                new ManagedJournalStore.ToolTask(
                        "task", "turn", "call", "task", "exec", "{}", 1, "ready", null, null, null);
        var message = codec.encode(new ManagedToolCommand(scope, "command", "execute", task, "{}"));
        assertTrue(!scope.messageGroup().equals(message.messageGroup()));
        assertEquals(scope.messageGroup(), routing.envelopeScope(message));
        var route = routing.route(routing.envelopeScope(message));
        assertEquals(
                scope.messageGroup(),
                routing.receive(scope.messageGroup(), route.liteTopic(), "1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> routing.receive(scope.messageGroup(), route.liteTopic(), "2"));
    }

    @Test
    void routesStayBoundedAndValidateScopeVersionAndDestination() {
        var shards = new HashSet<Integer>();
        for (int i = 0; i < 10000; i++) {
            var scope = new ManagedScope("tenant", "namespace", "session-" + i);
            var route = ManagedStableRoute.assign(scope, "cell-a", 1, 64);
            assertEquals(route, ManagedStableRoute.assign(scope, "cell-a", 1, 64));
            route.validate(scope, route.liteTopic(), 1);
            assertTrue(route.liteTopic().length() < 64);
            shards.add(route.shard());
        }
        assertEquals(64, shards.size());
        var scope = new ManagedScope("tenant", "namespace", "session");
        var route = ManagedStableRoute.assign(scope, "cell-a", 1, 64);
        assertThrows(
                ManagedStateException.class,
                () ->
                        route.validate(
                                new ManagedScope("other", "namespace", "session"),
                                route.liteTopic(),
                                1));
        assertThrows(
                ManagedStateException.class, () -> route.validate(scope, route.liteTopic(), 2));
        assertThrows(ManagedStateException.class, () -> route.validate(scope, "wrong", 1));
    }

    @Test
    void persistedRoutesSurviveLayoutChangesAndRollBackWithCreation() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:routes-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedStableRouteStore.initialize(source);
        var store = new ManagedStableRouteStore(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var scope = new ManagedScope("t", "n", "s");
        assertThrows(IllegalStateException.class, () -> store.assign(scope, "a", 1, 16));
        var first = tx.execute(ignored -> store.assign(scope, "a", 1, 16));
        assertEquals(first, tx.execute(ignored -> store.assign(scope, "b", 2, 128)));
        var other = new ManagedScope("t", "n", "other");
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                ignored -> {
                                    store.assign(other, "a", 1, 16);
                                    throw new IllegalStateException("creation failed");
                                }));
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM ma_route.session_route", Integer.class));
    }
}
