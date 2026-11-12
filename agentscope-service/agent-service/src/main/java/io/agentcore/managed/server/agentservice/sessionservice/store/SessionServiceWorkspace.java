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
package io.agentcore.managed.server.agentservice.sessionservice.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedTransactionalWorkspaceStore;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.StoreDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.stereotype.Component;

@Component
public final class SessionServiceWorkspace {
    private final BaseStore store;
    private final ManagedJournalStore journal;
    private final ObjectMapper mapper;
    private final SessionServiceOwnership ownership;

    public SessionServiceWorkspace(
            DataSource source, ManagedJournalStore journal, ObjectMapper mapper) {
        this.ownership = new SessionServiceOwnership(source);
        this.journal = journal;
        this.mapper = mapper;
        this.store =
                new ManagedTransactionalWorkspaceStore(
                        JdbcStore.builder(new TransactionAwareDataSourceProxy(source))
                                .dialect(workspaceDialect(source))
                                .build(),
                        source);
    }

    private static StoreDialect workspaceDialect(DataSource source) {
        final StoreDialect vendor;
        try (var connection = source.getConnection()) {
            vendor =
                    switch (connection.getMetaData().getDatabaseProductName()) {
                        case "PostgreSQL" -> new PostgresDialect();
                        case "H2" -> new H2Dialect();
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported Session Service database");
                    };
        } catch (SQLException unavailable) {
            throw new IllegalStateException("Database unavailable", unavailable);
        }
        return new StoreDialect() {
            public String storeTableName() {
                return "ma.harness_store";
            }

            public List<String> storeCreateTableDdls() {
                return List.of();
            } // ServiceSchema owns DDL.

            public BoundSql storeUpsert(String namespace, String key, String json, long timestamp) {
                var sql = vendor.storeUpsert(namespace, key, json, timestamp);
                return new BoundSql(
                        sql.sql().replace(vendor.storeTableName(), storeTableName()), sql.params());
            }
        };
    }

    public JsonNode execute(JsonNode request) {
        var operation = request.required("operation");
        if (!operation.isArray() || operation.size() < 2)
            throw new IllegalArgumentException("Invalid workspace operation");
        String kind = operation.get(0).asText();
        List<String> namespace =
                mapper.convertValue(
                        operation.get(1),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        Supplier<Object> action =
                switch (kind) {
                    case "get" -> () -> store.get(namespace, operation.get(2).asText());
                    case "search" ->
                            () ->
                                    store.search(
                                            namespace,
                                            operation.get(2).intValue(),
                                            operation.get(3).intValue());
                    case "put" ->
                            () -> {
                                store.put(
                                        namespace,
                                        operation.get(2).asText(),
                                        value(operation.get(3)));
                                return true;
                            };
                    case "cas" ->
                            () ->
                                    store.putIfVersion(
                                            namespace,
                                            operation.get(2).asText(),
                                            value(operation.get(3)),
                                            operation.get(4).longValue());
                    case "delete" ->
                            () -> {
                                store.delete(namespace, operation.get(2).asText());
                                return true;
                            };
                    default ->
                            throw new IllegalArgumentException("Unsupported workspace operation");
                };
        if (!request.path("receipt").isObject()) return mapper.valueToTree(action.get());
        var receipt = request.get("receipt");
        // Timestamps are regenerated during replay: exclude them from identity, preserve them
        // in the actual first write, just like the in-process native Store binding.
        var identity = (ArrayNode) operation.deepCopy();
        if (List.of("put", "cas").contains(kind) && identity.path(3).isObject())
            ((ObjectNode) identity.get(3)).remove(List.of("created_at", "modified_at"));
        String hash = hash(identity);
        if (!hash.equals(receipt.required("requestHash").asText()))
            throw new IllegalArgumentException("Private IO identity differs");
        var execution =
                new ManagedJournalStore.ExecutionOwner(
                        mapper.convertValue(receipt.required("scope"), ManagedScope.class),
                        receipt.required("turnId").asText(),
                        receipt.required("epoch").longValue(),
                        receipt.required("token").asText());
        String frame = receipt.required("frame").asText();
        int slot = receipt.required("slot").intValue();
        String result =
                ownership.whileCurrent(
                        execution,
                        () ->
                                List.of("get", "search").contains(kind)
                                        ? journal.finalizationRead(
                                                execution,
                                                frame,
                                                slot,
                                                hash,
                                                () -> json(action.get()))
                                        : journal.finalizationWrite(
                                                execution,
                                                frame,
                                                slot,
                                                hash,
                                                () -> json(action.get())));
        try {
            return mapper.readTree(result);
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid private IO receipt", invalid);
        }
    }

    private Map<String, Object> value(JsonNode value) {
        return mapper.convertValue(
                value,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    private String hash(JsonNode value) {
        try {
            return ManagedScope.fingerprint(
                    mapper.writer()
                            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(mapper.convertValue(value, Object.class)));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid workspace request", invalid);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid workspace result", invalid);
        }
    }
}
