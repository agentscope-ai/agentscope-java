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
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class ManagedAdministrativeToolsTest {
    DriverManagerDataSource database() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:admin-review-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        return source;
    }

    @Test
    void confirmedMaintenanceSurvivesUnknownProgressAndDuplicateReorderedSources()
            throws Exception {
        var mapper = new ObjectMapper();
        var source = database();
        var physical = database();
        var scope = new ManagedScope("t", "n", "admin");
        new ManagedAdmissionStore(source, mapper).createSession(scope, "{}");
        var transport = new ManagedToolTransport(source, mapper);
        var crypto = new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(scope))
                .thenReturn(
                        new SessionResolveResult(
                                null, Map.of(), null, null, null, Map.of(), null, List.of(),
                                List.of(), null, Map.of()));
        var admin =
                new ManagedAdministrativeTools(
                        source,
                        mapper,
                        transport,
                        new ManagedMcpCatalog(source, mapper, crypto, transport),
                        catalog);
        var task =
                new ToolTask(
                        "snapshot",
                        "administrative",
                        "snapshot",
                        "snapshot",
                        "workspace.snapshot",
                        "{}",
                        0,
                        "ready",
                        null,
                        null,
                        null);
        admin.executor(scope).start(scope, task);
        var command =
                mapper.readValue(
                        new JdbcTemplate(source)
                                .queryForObject(
                                        "SELECT payload FROM ma.tool_command_outbox", String.class),
                        ManagedToolCommand.class);
        var toolWorker = new ManagedToolWorkerStore(physical, mapper);
        toolWorker.receive(command);
        var claimed = toolWorker.claimTool(scope, task.id(), "owner").task();
        toolWorker.toolEvents().open(scope, claimed, "owner", false);
        toolWorker.markToolUnknown(scope, task.id(), "owner");
        var result =
                new ManagedToolResult(
                        scope, task.id(), task.id(), "succeeded", "{\"snapshot\":\"original\"}");
        toolWorker.stageToolResult(result);
        toolWorker.complete(result);
        var events = toolWorker.toolEvents().read(scope, task.id(), 0, 10);
        assertEquals(2, events.size());
        assertTrue(admin.receive(events.get(1)));
        assertTrue(admin.receive(events.get(0)));
        assertTrue(admin.receive(events.get(1)));
        assertEquals(result.result(), admin.executor(scope).query(scope, task).result());
        var changed = new ManagedToolResult(scope, task.id(), task.id(), "failed", "{}");
        assertThrows(
                ManagedStateException.class,
                () ->
                        admin.receive(
                                new ManagedToolEvent(
                                        scope,
                                        task.id(),
                                        task.id(),
                                        2,
                                        "tool.result",
                                        Map.of(
                                                "result",
                                                mapper.convertValue(changed, Map.class)))));
    }
}
