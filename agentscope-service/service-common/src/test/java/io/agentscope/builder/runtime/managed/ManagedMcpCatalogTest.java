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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class ManagedMcpCatalogTest {
    @Test
    void recoveryMetadataPreservesLegacyDescriptorsAndHashes() throws Exception {
        var mapper = new ObjectMapper();
        String legacy =
                "{\"server\":\"s\",\"name\":\"s__echo\",\"remoteName\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":null,\"readOnly\":false}";
        var tool = mapper.readValue(legacy, ManagedMcpCatalog.Tool.class);
        assertEquals(legacy, mapper.writeValueAsString(tool));
        assertEquals(
                ManagedScope.fingerprint(legacy), ManagedMcpCatalog.descriptorHash(tool, mapper));
        var recovery = new ManagedMcpCatalog.Recovery(1, "execution_get");
        var recoverable =
                new ManagedMcpCatalog.Tool(
                        tool.server(),
                        tool.name(),
                        tool.remoteName(),
                        tool.description(),
                        tool.inputSchema(),
                        tool.outputSchema(),
                        tool.readOnly(),
                        recovery);
        assertEquals(
                recoverable,
                mapper.readValue(
                        mapper.writeValueAsString(recoverable), ManagedMcpCatalog.Tool.class));
        assertFalse(
                ManagedMcpCatalog.descriptorHash(tool, mapper)
                        .equals(ManagedMcpCatalog.descriptorHash(recoverable, mapper)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedMcpCatalog.Recovery(2, "execution_get"));
    }

    @Test
    void discoveryIsPinnedWithinATurnAndOptionalConnectionsAreRetriedOnTheNextTurn()
            throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-catalog-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("t", "n", "s");
        new ManagedAdmissionStore(source, mapper).createSession(scope, "{}");
        var crypto = new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
        var transport = new ManagedToolTransport(source, mapper);
        var discoveries = new AtomicInteger();
        var degraded = new ManagedMcpCatalog.Description(List.of(), List.of("server"));
        var catalogs =
                new ManagedMcpCatalog(
                        source,
                        mapper,
                        crypto,
                        transport,
                        (connections, definition, cancelled) -> {
                            discoveries.incrementAndGet();
                            return degraded;
                        });
        var server = new McpServerConfig();
        server.setTransport("http");
        server.setUrl("http://mcp.invalid");
        server.setHeaders(Map.of("Authorization", "private-mcp-credential"));
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("server", server));
        var resolved =
                new SessionResolveResult(
                        null,
                        Map.of("modelSecret", "must-not-leave-brain"),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(Map.of("secret", "vault-other-secret")),
                        List.of(),
                        null,
                        Map.of());
        assertEquals(degraded, catalogs.require(scope, tools, resolved, "first"));
        assertEquals(degraded, catalogs.require(scope, tools, resolved, "first"));
        assertEquals(1, discoveries.get());
        assertEquals(degraded, catalogs.require(scope, tools, resolved, "second"));
        assertEquals(2, discoveries.get());
        assertEquals(degraded, catalogs.require(scope, tools, resolved, "first"));
        assertEquals(2, discoveries.get());
        var jdbc = new JdbcTemplate(source);
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM ma.tool_command_outbox", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ma.mcp_catalog", Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM ma.protocol_mcp_catalog", Integer.class));
        assertFalse(
                jdbc.queryForObject("SELECT payload FROM ma.mcp_catalog LIMIT 1", String.class)
                        .contains("private-mcp-credential"));
    }

    @Test
    void aLegacyInFlightCatalogResultIsDrainedWithoutStartingAnotherDiscovery() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-legacy-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("t", "n", "legacy");
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        var transport = new ManagedToolTransport(source, mapper);
        var catalogs =
                new ManagedMcpCatalog(
                        source,
                        mapper,
                        new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901"),
                        transport,
                        (connections, resolved, cancelled) -> {
                            throw new AssertionError("Legacy snapshot must be reused");
                        });
        var config = new McpServerConfig();
        config.setTransport("http");
        config.setUrl("http://mcp.invalid");
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("server", config));
        String id = catalogs.id(scope, tools);
        var task =
                new ManagedJournalStore.ToolTask(
                        id, "catalog", id, id, "mcp.describe", "{}", 0, "ready", null, null, null);
        transport.stage(new ManagedToolCommand(scope, id, "execute", task, "legacy-sealed-target"));
        var description = new ManagedMcpCatalog.Description(List.of(), List.of("server"));
        var result =
                new ManagedToolResult(
                        scope, id, id, "succeeded", mapper.writeValueAsString(description));
        assertTrue(
                catalogs.receive(
                        new ManagedToolEvent(
                                scope, id, id, 1, "tool.progress", Map.of("status", "unknown"))));
        assertTrue(
                catalogs.receive(
                        new ManagedToolEvent(
                                scope,
                                id,
                                id,
                                2,
                                "tool.result",
                                Map.of("result", mapper.convertValue(result, Map.class)))));
        assertEquals(description, catalogs.require(scope, tools, null, turn));
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_command_outbox", Integer.class));
    }

    @Test
    void cancellationCanAdvanceWithoutWaitingForARequiredServer() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-cancel-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("t", "n", "cancel");
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        var transport = new ManagedToolTransport(source, mapper);
        var catalogs =
                new ManagedMcpCatalog(
                        source,
                        mapper,
                        new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901"),
                        transport);
        var connection = new McpServerConfig();
        connection.setRequired(true);
        connection.setTransport("http");
        connection.setUrl("http://unavailable.invalid");
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("required", connection));
        var resolved =
                new SessionResolveResult(
                        null, Map.of(), null, null, null, Map.of(), null, List.of(), List.of(),
                        null, Map.of());
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        assertTrue(catalogs.require(scope, tools, resolved, turn).tools().isEmpty());
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        assertTrue(catalogs.require(scope, tools, resolved).tools().isEmpty());
        assertEquals(
                0,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE kind='cancel'",
                                Integer.class));
    }
}
