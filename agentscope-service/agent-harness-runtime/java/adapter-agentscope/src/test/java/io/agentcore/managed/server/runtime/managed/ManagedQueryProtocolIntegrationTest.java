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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedQueryProtocolIntegrationTest {
    @TempDir Path directory;

    @Test
    void commandAndLifecycleFactsReplayIntoAnIndependentBuild() {
        var execution =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:command-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var query =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:query-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(execution);
        PostgresSessionProjectionStore.initialize(query);
        var mapper = new ObjectMapper();
        var backend = new PostgresSessionProjectionStore(query, mapper);
        var key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var helpers = new ManagedPublicResources(execution, mapper);
        var views = new ManagedQueryViews(backend, mapper, key, "live", 1000, helpers);
        var admission = new ManagedAdmissionStore(execution, mapper);
        admission.queryViews(views);
        var scope = new ManagedScope("t", "n", UUID.randomUUID().toString());
        admission.createSession(scope, "{}");
        admission.queue().enable(scope);
        var protocol = admission.protocol();
        var command =
                new ManagedSessionProtocol.Command(
                        "user.message",
                        List.of(Map.of("type", "text", "text", "hello")),
                        "new_turn",
                        null,
                        true);
        var accepted = protocol.accept(scope, "a", "key", List.of(command), () -> {});
        assertTrue(
                protocol.history(scope, "a", Map.of(), true).get("data") instanceof List<?> l
                        && l.isEmpty());
        try (var objects = new FileRuntimeObjectStore("fixture", directory)) {
            var projection =
                    new ManagedSessionProjections(
                            execution, mapper, mock(SessionEventNotifier.class), objects);
            projection.queryViews(views);
            while (projection.project(scope, 128) > 0) {}
            assertEquals(
                    1,
                    ((List<?>)
                                    protocol.history(
                                                    scope,
                                                    "a",
                                                    Map.of("types[]", List.of("user.message")),
                                                    true)
                                            .get("data"))
                            .size());
            long version = views.progress(scope).commitVersion();
            assertTrue(protocol.accept(scope, "a", "key", List.of(command), () -> {}).replayed());
            assertEquals(version, projection.acceptedVersion(scope));
            new ManagedLifecycles(execution).stage(scope, "archived", "a", "archive");
            while (projection.project(scope, 128) > 0) {}
            assertTrue(
                    protocol.changes(scope, 0, 256).stream()
                            .anyMatch(
                                    c ->
                                            "managed_agents.turn.cancelled"
                                                    .equals(c.event().get("type"))));
            var shadow = new ManagedQueryViews(backend, mapper, key, "shadow", 1000, helpers);
            var facts =
                    new ManagedProjectionFacts(
                            execution, mapper, new ManagedFactBundles(objects, mapper));
            ManagedProjectionFacts.Page page;
            while ((page = facts.next(scope, shadow.progress(scope).commitVersion())) != null)
                shadow.apply(scope, page.version(), page.facts());
            assertEquals(
                    views.progress(scope).commitVersion(), shadow.progress(scope).commitVersion());
            assertEquals(protocol.changes(scope, 0, 256), shadow.protocol().changes(scope, 0, 256));
            assertEquals(
                    0,
                    new JdbcTemplate(execution)
                            .queryForObject("SELECT count(*) FROM ma.public_event", Integer.class));
        }
    }
}
