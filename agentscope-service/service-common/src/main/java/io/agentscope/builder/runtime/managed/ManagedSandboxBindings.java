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

import io.agentscope.builder.control.SessionResolveResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable provider creation intent. No database TTL; an ambiguous create cannot create another sandbox. */
public final class ManagedSandboxBindings {
    public record Binding(String status, String creationToken, String encryptedState) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private String table() {
        return "ma.hands_sandbox";
    }

    private void lock(ManagedScope scope) {
        jdbc.update(
                "INSERT INTO ma.hands_control(scope_key,task_id,cancel_requested)"
                        + " VALUES(?,'sandbox-binding',FALSE) ON CONFLICT DO NOTHING",
                scope.messageGroup());
        jdbc.queryForObject(
                "SELECT task_id FROM ma.hands_control WHERE scope_key=? AND"
                        + " task_id='sandbox-binding' FOR UPDATE",
                String.class,
                scope.messageGroup());
    }

    public ManagedSandboxBindings(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
    }

    public Binding get(ManagedScope scope) {
        var rows =
                jdbc.query(
                        "SELECT * FROM " + table() + " WHERE scope_key=?",
                        (row, index) ->
                                new Binding(
                                        row.getString("status"),
                                        row.getString("creation_token"),
                                        row.getString("encrypted_state")),
                        scope.messageGroup());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Binding begin(ManagedScope scope) {
        return tx.execute(
                ignored -> {
                    lock(scope);
                    var current = get(scope);
                    if (current != null) return current;
                    String token = UUID.randomUUID().toString();
                    jdbc.update(
                            "INSERT INTO "
                                    + table()
                                    + "(scope_key,status,creation_token)"
                                    + " VALUES(?,'creating',?)",
                            scope.messageGroup(),
                            token);
                    return new Binding("creating", token, null);
                });
    }

    public void borrow(ManagedScope scope, String identity) {
        try {
            tx.executeWithoutResult(
                    ignored -> {
                        lock(scope);
                        var current =
                                jdbc.queryForList(
                                        "SELECT borrowed_identity FROM "
                                                + table()
                                                + " WHERE"
                                                + " scope_key=?",
                                        scope.messageGroup());
                        if (!current.isEmpty()) {
                            if (!identity.equals(current.get(0).get("borrowed_identity")))
                                throw new ManagedStateException(
                                        "sandbox_binding_conflict",
                                        "Session sandbox identity differs");
                            return;
                        }
                        jdbc.update(
                                "INSERT INTO"
                                        + " "
                                        + table()
                                        + "(scope_key,status,creation_token,borrowed_identity)"
                                        + " VALUES(?,'borrowed',?,?)",
                                scope.messageGroup(),
                                UUID.randomUUID().toString(),
                                identity);
                    });
        } catch (DataIntegrityViolationException alreadyBorrowed) {
            throw new ManagedStateException(
                    "borrowed_sandbox_busy", "This existing sandbox belongs to another Session");
        }
    }

    /** Reserve an existing self-hosted instance; only provider connection fields leave the platform. */
    public Map<String, Object> borrow(ManagedScope scope, SessionResolveResult resolved) {
        var env = resolved.environment();
        if (env == null || !"self_hosted".equals(env.type()))
            throw new ManagedStateException(
                    "environment_type_differs", "An existing self-hosted Sandbox is required");
        var config = new LinkedHashMap<String, Object>();
        for (String field :
                List.of(
                        "sandboxId",
                        "apiKey",
                        "apiBaseUrl",
                        "domain",
                        "envdBaseUrl",
                        "envdAccessToken",
                        "workspaceRoot",
                        "codec",
                        "runUser",
                        "readTimeoutSeconds"))
            if (env.config() != null && env.config().get(field) != null)
                config.put(field, env.config().get(field));
        config.put("sandboxId", ManagedToolTarget.existingSandboxId(resolved));
        borrow(scope, ManagedToolTarget.existingSandboxIdentity(resolved));
        return Map.copyOf(config);
    }

    public void confirm(ManagedScope scope, String token, String encryptedState) {
        if (jdbc.update(
                        "UPDATE "
                                + table()
                                + " SET status='active',encrypted_state=? WHERE scope_key=?"
                                + " AND creation_token=? AND status IN ('creating','active')",
                        encryptedState,
                        scope.messageGroup(),
                        token)
                != 1)
            throw new ManagedStateException(
                    "sandbox_creation_conflict", "Sandbox creation intent changed");
    }

    public void releaseBorrowed(ManagedScope scope) {
        jdbc.update(
                "UPDATE "
                        + table()
                        + " SET borrowed_identity=NULL,status='released' WHERE scope_key=?"
                        + " AND status='borrowed'",
                scope.messageGroup());
    }

    public void released(ManagedScope scope) {
        jdbc.update(
                "UPDATE "
                        + table()
                        + " SET status='released' WHERE scope_key=? AND status='active'",
                scope.messageGroup());
    }
}
