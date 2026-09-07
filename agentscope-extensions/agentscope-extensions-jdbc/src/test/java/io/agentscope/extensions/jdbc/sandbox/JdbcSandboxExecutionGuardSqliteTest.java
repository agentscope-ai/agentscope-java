/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.jdbc.sandbox;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite file-based integration tests for {@link JdbcSandboxExecutionGuard} using the
 * portable table-based lock inherited from {@link SqliteDialect}.
 *
 * <p>SQLite reports primary-key violations as {@code SQLiteException} with
 * {@code sqlState=null} and {@code errorCode=19} (not as an
 * {@link java.sql.SQLIntegrityConstraintViolationException}), so this validates that the
 * lock's duplicate-key detection recognises contention correctly.
 *
 * <p>A real file-based database is used (via {@link TempDir}) rather than an in-memory
 * shared-cache one: the latter is destroyed when the last connection closes, which would
 * wipe the lock table between the DDL and the INSERT that {@code tryEnter} runs on
 * separate connections.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcSandboxExecutionGuard SQLite table-lock tests")
class JdbcSandboxExecutionGuardSqliteTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("acquire and release lock on SQLite")
    void acquireAndReleaseLock() throws Exception {
        JdbcSandboxExecutionGuard guard = newGuard("acquire_release");
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, "sqlite-agent")
                        .orElseThrow();

        SandboxLease lease = guard.tryEnter(key);
        assertTrue(lease != null);
        lease.close();
    }

    @Test
    @DisplayName("duplicate lock on SQLite is treated as contention (timeout), not an error")
    void duplicateLockTimesOut() throws Exception {
        JdbcSandboxExecutionGuard guard = newGuard("duplicate_lock");
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.AGENT, null, "sqlite-mutex")
                        .orElseThrow();

        SandboxLease lease1 = guard.tryEnter(key);
        try {
            assertThrows(InterruptedException.class, () -> guard.tryEnter(key));
        } finally {
            lease1.close();
        }
    }

    private JdbcSandboxExecutionGuard newGuard(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        String dbFile =
                tempDir.resolve(name + ".db").toAbsolutePath().toString().replace('\\', '/');
        ds.setUrl("jdbc:sqlite:" + dbFile);
        SqliteDialect dialect = new SqliteDialect();
        dialect.bindDataSource(ds);
        return JdbcSandboxExecutionGuard.builder(dialect)
                .lockTimeout(Duration.ofMillis(300))
                .build();
    }
}
