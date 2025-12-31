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
package io.agentscope.core.session.mysql.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <p>This makes the E2E tests runnable in CI without provisioning a real MySQL instance and without
 * requiring any environment variables.
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
    @DisplayName("Smoke: auto-create database/table + save/load/list/delete flow")
    void testMysqlSessionEndToEndFlow() {
        System.out.println("\n=== Test: MysqlSession E2E Flow ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        // Prepare test states
        TestState stateA = new TestState("hello", 1);
        TestState stateB = new TestState("world", 2);

        String sessionIdStr = "mysql_e2e_session_" + UUID.randomUUID();
        SessionKey sessionKey = SimpleSessionKey.of(sessionIdStr);

        // Save single states
        session.save(sessionKey, "moduleA", stateA);
        session.save(sessionKey, "moduleB", stateB);
        assertTrue(session.exists(sessionKey));

        // Load states
        Optional<TestState> loadedA = session.get(sessionKey, "moduleA", TestState.class);
        Optional<TestState> loadedB = session.get(sessionKey, "moduleB", TestState.class);

        assertTrue(loadedA.isPresent());
        assertTrue(loadedB.isPresent());
        assertEquals("hello", loadedA.get().value());
        assertEquals("world", loadedB.get().value());
        assertEquals(1, loadedA.get().count());
        assertEquals(2, loadedB.get().count());

        // listSessionKeys
        Set<SessionKey> sessionKeys = session.listSessionKeys();
        assertTrue(
                sessionKeys.contains(sessionKey), "listSessionKeys should contain saved session");

        // delete session
        session.delete(sessionKey);
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Save and load list state correctly")
    void testSaveAndLoadListState() {
        System.out.println("\n=== Test: Save and Load List State ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        String sessionIdStr = "mysql_e2e_list_" + UUID.randomUUID();
        SessionKey sessionKey = SimpleSessionKey.of(sessionIdStr);

        // Save list state
        List<TestState> states = List.of(new TestState("item1", 1), new TestState("item2", 2));
        session.save(sessionKey, "stateList", states);

        // Load list state
        List<TestState> loaded = session.getList(sessionKey, "stateList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("item1", loaded.get(0).value());
        assertEquals("item2", loaded.get(1).value());

        // Add more items incrementally
        List<TestState> moreStates =
                List.of(
                        new TestState("item1", 1),
                        new TestState("item2", 2),
                        new TestState("item3", 3));
        session.save(sessionKey, "stateList", moreStates);

        // Verify all items
        List<TestState> allLoaded = session.getList(sessionKey, "stateList", TestState.class);
        assertEquals(3, allLoaded.size());
        assertEquals("item3", allLoaded.get(2).value());
    }

    @Test
    @DisplayName("Session does not exist should return false")
    void testSessionNotExists() {
        System.out.println("\n=== Test: Session Not Exists ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        SessionKey sessionKey = SimpleSessionKey.of("non_existent_" + UUID.randomUUID());
        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Get non-existent state should return empty")
    void testGetNonExistentState() {
        System.out.println("\n=== Test: Get Non-Existent State ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS").toUpperCase();
        createdSchemaName = schemaName;

        initSchemaAndTable(dataSource, schemaName, tableName);
        MysqlSession session = new MysqlSession(dataSource, schemaName, tableName, false);

        SessionKey sessionKey = SimpleSessionKey.of("missing_" + UUID.randomUUID());
        Optional<TestState> result = session.get(sessionKey, "moduleA", TestState.class);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("createIfNotExist=false should fail fast when database/table do not exist")
    void testCreateIfNotExistFalseFailsWhenMissing() {
        System.out.println("\n=== Test: createIfNotExist=false with missing schema ===");

        dataSource = createH2DataSource();
        String schemaName = generateSafeIdentifier("AGENTSCOPE_E2E_MISSING").toUpperCase();
        String tableName = generateSafeIdentifier("AGENTSCOPE_SESSIONS_MISSING").toUpperCase();

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

    /** Generates a safe MySQL identifier (letters/numbers/underscore) and keeps it <= 64 chars. */
    private static String generateSafeIdentifier(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "_");
        String raw = prefix + "_" + suffix;
        if (!Character.isLetter(raw.charAt(0)) && raw.charAt(0) != '_') {
            raw = "_" + raw;
        }
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
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
            // Table structure with item_index for incremental list storage
            stmt.execute(
                    "CREATE TABLE "
                            + tableName
                            + " ("
                            + "session_id VARCHAR(255) NOT NULL, "
                            + "state_key VARCHAR(255) NOT NULL, "
                            + "item_index INT NOT NULL DEFAULT 0, "
                            + "state_data LONGTEXT NOT NULL, "
                            + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                            + "PRIMARY KEY (session_id, state_key, item_index)"
                            + ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init schema/table for H2 e2e", e);
        }
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
