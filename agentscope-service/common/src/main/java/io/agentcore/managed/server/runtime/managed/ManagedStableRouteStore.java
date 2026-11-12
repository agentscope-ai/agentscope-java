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

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Session creation transaction owns routing. Resizing a layout cannot silently move existing Sessions. */
public final class ManagedStableRouteStore {
    private final JdbcTemplate jdbc;

    public ManagedStableRouteStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
    }

    public static void initialize(DataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("managed-stable-routes.sql"))
                .execute(source);
    }

    public ManagedStableRoute assign(ManagedScope scope, String cell, int layout, int shards) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException(
                    "Route assignment must join Session creation transaction");
        var candidate = ManagedStableRoute.assign(scope, cell, layout, shards);
        jdbc.update(
                "INSERT INTO"
                    + " ma_route.session_route(scope_key,cell,layout_version,shard_count,logical_shard)"
                    + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                candidate.scopeKey(),
                candidate.cell(),
                candidate.layoutVersion(),
                candidate.shardCount(),
                candidate.shard());
        return require(scope);
    }

    public ManagedStableRoute require(ManagedScope scope) {
        return require(scope.messageGroup());
    }

    public ManagedStableRoute require(String scopeKey) {
        return jdbc.queryForObject(
                "SELECT * FROM ma_route.session_route WHERE scope_key=?",
                (row, index) ->
                        new ManagedStableRoute(
                                row.getString("scope_key"),
                                row.getString("cell"),
                                row.getInt("layout_version"),
                                row.getInt("shard_count"),
                                row.getInt("logical_shard")),
                scopeKey);
    }
}
