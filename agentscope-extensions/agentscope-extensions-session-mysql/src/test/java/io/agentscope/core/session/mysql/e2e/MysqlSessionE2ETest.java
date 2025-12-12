/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session.mysql.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StateModuleBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end tests for {@link MysqlSession} using an in-memory H2 database in MySQL compatibility
 * mode.
 *
 * <p>This makes the E2E tests runnable in CI without provisioning a real MySQL instance and
 * without requiring any environment variables.
 */
@Tag("e2e")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Session MySQL Storage E2E Tests")
class MysqlSessionE2ETest {

    private String createdSchemaName;
    private DataSource dataSource;

    @AfterEach
    void cleanupDatabase() {
        if (dataSource == null || createdSchemaName == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + createdSchemaName + " CASCADE");
        } catch (SQLException e) {
            // best-effort cleanup
            System.err.println(
                    "Failed to drop e2e schema " + createdSchemaName + ": " + e.getMessage());
        } finally {
            createdSchemaName = null;
            dataSource = null;
        }
    }

    @Test
    @DisplayName("Smoke: auto-create database/table + save/load/list/info/delete flow")
    void testMysqlSessionEndToEndFlow() {
        System.out.println("\n=== Test: MysqlSession E2E Flow ===");

        dataSource = createH2DataSource();
        // H2 folds unquoted identifiers to upper-case. Keep schema/table names upper-case so that
        // INFORMATION_SCHEMA lookups in MysqlSession match exactly.
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        // Prepare state modules
        TestStateModule moduleA = new TestStateModule("moduleA");
        TestStateModule moduleB = new TestStateModule("moduleB");
        moduleA.setValue("hello");
        moduleB.setValue("world");

        String sessionId = "mysql_e2e_session_" + UUID.randomUUID();
        Map<String, StateModule> modules = Map.of("moduleA", moduleA, "moduleB", moduleB);

        // Save
        session.saveSessionState(sessionId, modules);
        assertTrue(session.sessionExists(sessionId));

        // Load into fresh modules
        TestStateModule loadedA = new TestStateModule("moduleA");
        TestStateModule loadedB = new TestStateModule("moduleB");
        session.loadSessionState(sessionId, false, Map.of("moduleA", loadedA, "moduleB", loadedB));

        assertEquals("hello", loadedA.getValue());
        assertEquals("world", loadedB.getValue());

        // listSessions
        List<String> sessions = session.listSessions();
        assertTrue(sessions.contains(sessionId), "listSessions should contain saved session id");

        // getSessionInfo
        SessionInfo info = session.getSessionInfo(sessionId);
        assertNotNull(info);
        assertEquals(sessionId, info.getSessionId());
        assertTrue(info.getSize() > 0, "SessionInfo.size should be > 0");
        assertEquals(2, info.getComponentCount(), "Should contain 2 components");
        assertTrue(info.getLastModified() > 0, "SessionInfo.lastModified should be > 0");

        // deleteSession
        assertTrue(session.deleteSession(sessionId));
        assertFalse(session.sessionExists(sessionId));
        assertFalse(session.deleteSession(sessionId), "Delete again should return false");
    }

    @Test
    @DisplayName("allowNotExist=true should silently ignore missing session")
    void testLoadAllowNotExistTrue() {
        System.out.println("\n=== Test: allowNotExist=true ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        TestStateModule module = new TestStateModule("moduleA");
        session.loadSessionState("missing_" + UUID.randomUUID(), true, Map.of("moduleA", module));
        // Should not throw, and module should remain default
        assertNull(module.getValue());
    }

    @Test
    @DisplayName("createIfNotExist=false should fail fast when database/table do not exist")
    void testCreateIfNotExistFalseFailsWhenMissing() {
        System.out.println("\n=== Test: createIfNotExist=false with missing schema ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E_MISSING").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS_MISSING").toUpperCase();

        // Do not set createdSchemaName because we didn't create it; cleanup not needed.
        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(dataSource, schemaName, tableName, false));
    }

    private static DataSource createH2DataSource() {
        String dbName = "mysql_session_e2e_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Generates a safe MySQL identifier (letters/numbers/underscore) and keeps it <= 64 chars.
     */
    private static String generateSafeIdentifier(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "_");
        String raw = prefix + "_" + suffix;
        // Ensure first char is a letter or underscore
        if (!Character.isLetter(raw.charAt(0)) && raw.charAt(0) != '_') {
            raw = "_" + raw;
        }
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        // Avoid trailing underscore-only truncation weirdness
        raw = raw.replaceAll("_+$", "_e2e");
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        return raw;
    }

    private static void initSchemaAndTable(
            DataSource dataSource, String schemaName, String tableName) throws RuntimeException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            stmt.execute("SET SCHEMA " + schemaName);
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            // Keep DDL compatible with H2 while still exercising MysqlSession's DML (including
            // "ON DUPLICATE KEY UPDATE") in MySQL mode.
            stmt.execute(
                    "CREATE TABLE "
                            + tableName
                            + " ("
                            + "session_id VARCHAR(255) PRIMARY KEY, "
                            + "state_data TEXT NOT NULL, "
                            + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                            + ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init schema/table for H2 e2e", e);
        }
    }

    private static class TestStateModule extends StateModuleBase {
        private final String componentName;
        private String value;

        TestStateModule(String componentName) {
            this.componentName = componentName;
            registerState("value");
        }

        @Override
        public String getComponentName() {
            return componentName;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
