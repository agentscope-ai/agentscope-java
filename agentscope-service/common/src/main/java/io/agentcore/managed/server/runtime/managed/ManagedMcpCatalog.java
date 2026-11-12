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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runtime discovers and pins MCP descriptors before Harness construction; calls remain remote. */
public final class ManagedMcpCatalog {
    private static final Logger log = LoggerFactory.getLogger(ManagedMcpCatalog.class);
    public static final String RECOVERY_META = "io.agentcore.managed/execution-recovery";

    /** Version 1 requires atomic deduplication by execution_id and a read-only lookup tool. */
    public record Recovery(int version, String queryTool) {
        public Recovery {
            if (version != 1 || queryTool == null || !queryTool.matches("[A-Za-z0-9_.-]{1,128}"))
                throw new IllegalArgumentException("Unsupported MCP execution recovery contract");
        }
    }

    public record Tool(
            String server,
            String name,
            String remoteName,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean readOnly,
            @JsonInclude(JsonInclude.Include.NON_NULL) Recovery recovery) {
        public Tool(
                String server,
                String name,
                String remoteName,
                String description,
                Map<String, Object> inputSchema,
                Map<String, Object> outputSchema,
                boolean readOnly) {
            this(server, name, remoteName, description, inputSchema, outputSchema, readOnly, null);
        }
    }

    public record Description(List<Tool> tools, List<String> unavailable) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final ManagedToolTransport transport;
    private final Discovery discovery;
    private SessionServiceClient remote;

    @FunctionalInterface
    public interface Discovery {
        Description discover(
                ToolsConfig tools, SessionResolveResult resolved, BooleanSupplier cancelled);

        default Mono<Description> discoverAsync(
                String isolation,
                ToolsConfig tools,
                SessionResolveResult resolved,
                BooleanSupplier cancelled) {
            return Mono.fromCallable(() -> discover(tools, resolved, cancelled))
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    public ManagedMcpCatalog(
            DataSource source,
            ObjectMapper mapper,
            VaultCrypto crypto,
            ManagedToolTransport transport) {
        this(source, mapper, crypto, transport, new ManagedMcpDiscovery());
    }

    public ManagedMcpCatalog(
            DataSource source,
            ObjectMapper mapper,
            VaultCrypto crypto,
            ManagedToolTransport transport,
            Discovery discovery) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.crypto = crypto;
        this.transport = transport;
        this.discovery = discovery;
    }

    public ManagedMcpCatalog(
            SessionServiceClient remote,
            ObjectMapper mapper,
            VaultCrypto crypto,
            Discovery discovery) {
        this.remote = remote;
        this.mapper = mapper;
        this.crypto = crypto;
        jdbc = null;
        tx = null;
        transport = null;
        this.discovery = discovery;
    }

    public static String descriptorHash(Tool tool, ObjectMapper mapper) {
        try {
            return ManagedScope.fingerprint(
                    mapper.copy()
                            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(tool));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot hash MCP descriptor", invalid);
        }
    }

    public String id(ManagedScope scope, ToolsConfig tools) {
        return id(scope, tools, currentTurn(scope));
    }

    private String id(ManagedScope scope, ToolsConfig tools, String turn) {
        return "catalog_" + ManagedScope.fingerprint(scope.messageGroup(), turn, json(tools));
    }

    private String currentTurn(ManagedScope scope) {
        if (remote != null)
            return ManagedServiceCalls.coordinate(remote, "turn-current", Map.of("scope", scope))
                    .asText();
        String current =
                jdbc.queryForObject(
                        "SELECT current_turn_id FROM ma.session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        if (current == null)
            throw new IllegalStateException("MCP materialization requires an active Turn");
        return current;
    }

    public String seal(SessionResolveResult resolved, ToolsConfig tools) {
        var session = resolved.session();
        var identity =
                session == null
                        ? null
                        : new ManagedSessionDto(
                                session.id(),
                                session.ownerId(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                session.environmentId(),
                                null,
                                List.of(),
                                List.of(),
                                session.resources() == null
                                        ? List.of()
                                        : session.resources().stream()
                                                .filter(
                                                        value ->
                                                                "sandbox_binding"
                                                                        .equals(value.get("type")))
                                                .toList(),
                                null,
                                null,
                                0,
                                0,
                                null);
        var minimal =
                new SessionResolveResult(
                        identity,
                        Map.of(),
                        null,
                        null,
                        null,
                        resolved.definitionFiles(),
                        resolved.environment(),
                        List.of(),
                        List.of(),
                        null,
                        Map.of());
        return crypto.encrypt(json(new ManagedToolTarget(minimal, tools)));
    }

    public Description require(
            ManagedScope scope, ToolsConfig tools, SessionResolveResult resolved) {
        if (tools == null || tools.getMcpServers() == null || tools.getMcpServers().isEmpty())
            return new Description(List.of(), List.of());
        return require(scope, tools, resolved, currentTurn(scope));
    }

    public Description require(
            ManagedScope scope, ToolsConfig tools, SessionResolveResult resolved, String turn) {
        if (Schedulers.isInNonBlockingThread())
            throw new IllegalStateException(
                    "MCP catalog requires a blocking caller or requireAsync");
        return requireAsync(scope, tools, resolved, turn).block();
    }

    /** Database transitions use blocking workers; network waiting retains no Runtime worker. */
    public Mono<Description> requireAsync(
            ManagedScope scope, ToolsConfig tools, SessionResolveResult resolved, String turn) {
        if (tools == null || tools.getMcpServers() == null || tools.getMcpServers().isEmpty())
            return Mono.just(new Description(List.of(), List.of()));
        return Mono.fromCallable(() -> lookup(scope, tools, turn))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    long started = System.nanoTime();
                                    String isolation =
                                            ManagedScope.fingerprint(
                                                    scope.tenant(), scope.namespace());
                                    return discovery
                                            .discoverAsync(
                                                    isolation,
                                                    tools,
                                                    resolved,
                                                    () -> cancelledBeforeInference(scope, turn))
                                            .publishOn(Schedulers.boundedElastic())
                                            .map(
                                                    description -> {
                                                        ManagedRuntimeLog.info(
                                                                log,
                                                                "harness_runtime.mcp.catalog.discovered",
                                                                scope,
                                                                turn,
                                                                "tools",
                                                                description.tools().size(),
                                                                "unavailable_servers",
                                                                description.unavailable().size(),
                                                                "duration_ms",
                                                                (System.nanoTime() - started)
                                                                        / 1_000_000);
                                                        return pin(scope, tools, turn, description);
                                                    });
                                }));
    }

    private List<String> requiredServers(ToolsConfig tools) {
        return tools.getMcpServers().entrySet().stream()
                .filter(entry -> entry.getValue().isRequired())
                .map(Map.Entry::getKey)
                .toList();
    }

    private Description lookup(ManagedScope scope, ToolsConfig tools, String turn) {
        return lookup(scope, turn, id(scope, tools, turn), requiredServers(tools));
    }

    /** Metadata lookup only. Discovery stays with the Brain's local/HTTP MCP transport. */
    public Description lookup(
            ManagedScope scope, String turn, String id, List<String> requiredServers) {
        if (remote != null)
            return mapper.convertValue(
                    ManagedServiceCalls.coordinate(
                            remote,
                            "mcp-lookup",
                            Map.of(
                                    "scope",
                                    scope,
                                    "turnId",
                                    turn,
                                    "catalogId",
                                    id,
                                    "requiredServers",
                                    requiredServers)),
                    Description.class);
        // A required server outage must not prevent cancellation before any model/tool invocation.
        if (cancelledBeforeInference(scope, turn)) {
            var original =
                    jdbc.queryForList(
                            "SELECT payload FROM ma.tool_command_outbox WHERE command_id=?",
                            String.class,
                            id);
            if (!original.isEmpty()) {
                var command = parse(original.get(0), ManagedToolCommand.class);
                transport.stage(
                        new ManagedToolCommand(
                                scope, "cancel_" + id, "cancel", command.task(), null));
            }
            return new Description(List.of(), List.of());
        }
        var found =
                jdbc.queryForList(
                        "SELECT payload FROM ma.mcp_catalog WHERE scope_key=? AND catalog_id=?",
                        String.class,
                        scope.messageGroup(),
                        id);
        if (!found.isEmpty()) {
            var description = parse(found.get(0), Description.class);
            for (String server : description.unavailable())
                if (requiredServers.contains(server))
                    throw new ManagedStateException(
                            "mcp_connection_failed",
                            "Required MCP server is unavailable: " + server);
            // Bind the immutable discovery result before the model can emit a tool call.
            tx.executeWithoutResult(
                    ignored -> {
                        jdbc.queryForObject(
                                "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                                String.class,
                                scope.messageGroup());
                        int inserted =
                                jdbc.update(
                                        "INSERT INTO"
                                            + " ma.protocol_mcp_catalog(scope_key,turn_id,catalog_id)"
                                            + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                                        scope.messageGroup(),
                                        turn,
                                        id);
                        if (inserted == 1)
                            ManagedProjectionFacts.catalog(
                                    jdbc, mapper, scope, turn, id, found.get(0));
                    });
            return description;
        }
        return null;
    }

    private Description pin(
            ManagedScope scope, ToolsConfig tools, String turn, Description description) {
        return pin(scope, turn, id(scope, tools, turn), requiredServers(tools), description);
    }

    /** The first description wins; concurrent discovery cannot replace an admitted catalog. */
    public Description pin(
            ManagedScope scope,
            String turn,
            String id,
            List<String> requiredServers,
            Description description) {
        if (remote != null)
            return mapper.convertValue(
                    ManagedServiceCalls.coordinate(
                            remote,
                            "mcp-pin",
                            Map.of(
                                    "scope",
                                    scope,
                                    "turnId",
                                    turn,
                                    "catalogId",
                                    id,
                                    "requiredServers",
                                    requiredServers,
                                    "description",
                                    description)),
                    Description.class);
        return tx.execute(
                ignored -> {
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    if (cancelledBeforeInference(scope, turn))
                        return new Description(List.of(), List.of());
                    jdbc.update(
                            "INSERT INTO ma.mcp_catalog(scope_key,catalog_id,payload) VALUES(?,?,?)"
                                    + " ON CONFLICT DO NOTHING",
                            scope.messageGroup(),
                            id,
                            json(description));
                    // Another replica may have pinned the same Turn first. Always reuse that
                    // immutable snapshot rather than changing the model's admitted descriptors.
                    return lookup(scope, turn, id, requiredServers);
                });
    }

    public boolean cancelledBeforeInference(ManagedScope scope, String turn) {
        if (remote != null)
            return ManagedServiceCalls.coordinate(
                            remote, "mcp-cancelled", Map.of("scope", scope, "turnId", turn))
                    .booleanValue();
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.session s WHERE s.scope_key=? AND"
                            + " (s.lifecycle<>'active' OR EXISTS(SELECT 1 FROM ma.input i WHERE"
                            + " i.scope_key=s.scope_key AND i.turn_id=? AND i.type='CANCEL' AND"
                            + " i.status<>'rejected')) AND NOT EXISTS(SELECT 1 FROM ma.model_call m"
                            + " WHERE m.source_scope=s.scope_key AND m.turn_id=?) AND NOT"
                            + " EXISTS(SELECT 1 FROM ma.tool_task t WHERE t.scope_key=s.scope_key"
                            + " AND t.turn_id=? AND t.tool_name='mcp.call')",
                        Long.class,
                        scope.messageGroup(),
                        turn,
                        turn,
                        turn)
                > 0;
    }

    public boolean receive(ManagedToolEvent event) {
        if (!event.taskId().startsWith("catalog_")) return false;
        if (!event.taskId().equals(event.executionId())
                || !("tool.result".equals(event.type()) || "tool.progress".equals(event.type())))
            throw new IllegalArgumentException("Invalid MCP discovery result");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE command_id=? AND"
                                + " scope_key=? AND kind='execute'",
                        Long.class,
                        event.taskId(),
                        event.scope().messageGroup())
                != 1) throw new IllegalArgumentException("Unknown MCP discovery identity");
        // Discovery has no streamed output. Recovery may append unknown progress before its
        // final result; these observations need no ordered runtime output projection.
        if ("tool.progress".equals(event.type())) return true;
        var result = mapper.convertValue(event.data().get("result"), ManagedToolResult.class);
        if (!event.scope().equals(result.scope())
                || !event.taskId().equals(result.taskId())
                || !event.executionId().equals(result.executionId()))
            throw new IllegalArgumentException("Discovery result identity differs");
        // Descriptors are immutable for one Turn; later Turns may reuse a fresh cached directory.
        String payload = result.result();
        var description = parse(payload, Description.class);
        if (description.tools() == null || description.unavailable() == null)
            throw new IllegalArgumentException("Invalid MCP tool catalog");
        jdbc.update(
                "INSERT INTO ma.mcp_catalog(scope_key,catalog_id,payload) VALUES(?,?,?) ON CONFLICT"
                        + " DO NOTHING",
                event.scope().messageGroup(),
                event.taskId(),
                payload);
        String prior =
                jdbc.queryForObject(
                        "SELECT payload FROM ma.mcp_catalog WHERE scope_key=? AND catalog_id=?",
                        String.class,
                        event.scope().messageGroup(),
                        event.taskId());
        if (!prior.equals(payload))
            throw new ManagedStateException(
                    "mcp_catalog_conflict", "Immutable MCP description differs");
        return true;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode MCP catalog", e);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read MCP catalog", e);
        }
    }
}
