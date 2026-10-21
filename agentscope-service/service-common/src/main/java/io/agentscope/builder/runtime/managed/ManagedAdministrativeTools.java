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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Brain coordinates maintenance; Hands executes immutable workspace/resource operations over MQ. */
public final class ManagedAdministrativeTools {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedToolTransport commands;
    private final ManagedMcpCatalog targets;
    private final ManagedCatalog catalog;

    public ManagedAdministrativeTools(
            DataSource source,
            ObjectMapper mapper,
            ManagedToolTransport commands,
            ManagedMcpCatalog targets,
            ManagedCatalog catalog) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
        this.commands = commands;
        this.targets = targets;
        this.catalog = catalog;
    }

    public ManagedToolExecutor executor(ManagedScope scope) {
        return new ManagedToolExecutor() {
            public boolean idempotentStart() {
                return true;
            }

            public String start(ManagedScope owner, ToolTask task) {
                var admitted =
                        new ToolTask(
                                task.id(),
                                "administrative",
                                task.toolCallId(),
                                task.executionId(),
                                task.name(),
                                task.arguments(),
                                task.checkpointSequence(),
                                "ready",
                                null,
                                null,
                                null);
                tx.executeWithoutResult(
                        ignored -> {
                            jdbc.queryForObject(
                                    "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                                    String.class,
                                    owner.messageGroup());
                            var original =
                                    jdbc.queryForList(
                                            "SELECT payload FROM ma.admin_tool_operation WHERE"
                                                    + " scope_key=? AND task_id=?",
                                            String.class,
                                            owner.messageGroup(),
                                            task.id());
                            String payload = commands.json(admitted);
                            if (!original.isEmpty()) {
                                if (!payload.equals(original.get(0)))
                                    throw new ManagedStateException(
                                            "tool_content_conflict",
                                            "Original maintenance operation differs");
                                return;
                            }
                            jdbc.update(
                                    "INSERT INTO ma.admin_tool_operation(scope_key,task_id,payload)"
                                            + " VALUES(?,?,?)",
                                    owner.messageGroup(),
                                    task.id(),
                                    payload);
                            commands.stage(
                                    new ManagedToolCommand(
                                            owner,
                                            "execute_" + task.id(),
                                            "execute",
                                            admitted,
                                            targets.seal(
                                                    catalog.resolve(owner), new ToolsConfig())));
                        });
                return task.executionId();
            }

            public Outcome query(ManagedScope owner, ToolTask task) {
                var rows =
                        jdbc.queryForList(
                                "SELECT result FROM ma.admin_tool_operation WHERE scope_key=? AND"
                                        + " task_id=?",
                                String.class,
                                owner.messageGroup(),
                                task.id());
                if (rows.isEmpty()) {
                    start(owner, task);
                    return new Outcome(State.RUNNING, null);
                }
                if (rows.get(0) == null) return new Outcome(State.RUNNING, null);
                try {
                    var result = mapper.readValue(rows.get(0), ManagedToolResult.class);
                    return new Outcome(
                            State.valueOf(result.status().toUpperCase(Locale.ROOT)),
                            result.result());
                } catch (Exception invalid) {
                    throw new IllegalStateException("Invalid maintenance result", invalid);
                }
            }

            public Outcome cancel(ManagedScope owner, ToolTask task) {
                commands.stage(
                        new ManagedToolCommand(
                                owner,
                                "cancel_" + task.id(),
                                "cancel",
                                new ToolTask(
                                        task.id(),
                                        "administrative",
                                        task.toolCallId(),
                                        task.executionId(),
                                        task.name(),
                                        task.arguments(),
                                        task.checkpointSequence(),
                                        "ready",
                                        null,
                                        null,
                                        null),
                                null));
                return query(owner, task);
            }
        };
    }

    public boolean receive(ManagedToolEvent event) {
        var rows =
                jdbc.queryForList(
                        "SELECT payload,result FROM ma.admin_tool_operation WHERE scope_key=? AND"
                                + " task_id=?",
                        event.scope().messageGroup(),
                        event.taskId());
        if (rows.isEmpty()) return false;
        if (!("tool.result".equals(event.type()) || "tool.progress".equals(event.type())))
            throw new IllegalArgumentException("Unexpected maintenance source event");
        try {
            var task = mapper.readValue((String) rows.get(0).get("payload"), ToolTask.class);
            if (!task.executionId().equals(event.executionId()))
                throw new IllegalArgumentException("Maintenance execution differs");
            // Maintenance has no streamed output; an unknown observation may precede a
            // confirmed final result, including when the two are redelivered out of order.
            if ("tool.progress".equals(event.type())) return true;
            var result = mapper.convertValue(event.data().get("result"), ManagedToolResult.class);
            if (!event.scope().equals(result.scope())
                    || !task.id().equals(result.taskId())
                    || !task.executionId().equals(result.executionId()))
                throw new IllegalArgumentException("Maintenance execution differs");
            String payload = mapper.writeValueAsString(result);
            jdbc.update(
                    "UPDATE ma.admin_tool_operation SET result=? WHERE scope_key=? AND task_id=?"
                            + " AND result IS NULL",
                    payload,
                    event.scope().messageGroup(),
                    event.taskId());
            if (!payload.equals(
                    jdbc.queryForObject(
                            "SELECT result FROM ma.admin_tool_operation WHERE scope_key=? AND"
                                    + " task_id=?",
                            String.class,
                            event.scope().messageGroup(),
                            event.taskId())))
                throw new ManagedStateException(
                        "tool_result_conflict", "Original maintenance outcome differs");
            return true;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid maintenance event", invalid);
        }
    }
}
