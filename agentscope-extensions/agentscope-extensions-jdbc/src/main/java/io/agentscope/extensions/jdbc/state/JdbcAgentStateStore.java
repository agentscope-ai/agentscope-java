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
package io.agentscope.extensions.jdbc.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.jdbc.dialect.AgentStateStoreDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Database-agnostic session state store backed by {@link AgentStateStoreDialect}.
 *
 * <p>Implements {@link AgentStateStore} with zero inline SQL — every statement is
 * sourced from the dialect. Supports MySQL, PostgreSQL, H2, and SQLite via the
 * unified dialect abstraction.
 *
 * <h2>Storage model</h2>
 *
 * <ul>
 *   <li>Single state: stored as JSON with {@code item_index = 0}
 *   <li>List state: each item in a separate row with {@code item_index = 0, 1, 2, ...}
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>True incremental list storage (only INSERTs new items when append-only)
 *   <li>Hash-based change detection for mutable lists
 *   <li>Type-safe state serialisation using Jackson
 *   <li>Optional automatic schema creation
 * </ul>
 *
 * @author shanhongyu
 */
public class JdbcAgentStateStore implements AgentStateStore {

    public static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /** Suffix for hash storage keys. */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /** item_index value for single state values. */
    private static final int SINGLE_STATE_INDEX = 0;

    /**
     * Pattern for validating table names — only alphanumeric, underscores, and
     * hyphens, starting with a letter or underscore. Prevents SQL injection through
     * the table name (which cannot be parameterised in DDL).
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]*$");

    private static final int MAX_IDENTIFIER_LENGTH = 64;

    private final DataSource dataSource;
    private final AgentStateStoreDialect dialect;
    private final String tableName;
    private final String fullTableRef;

    // Pre-resolved SQL (table reference substituted once at construction)
    private final String upsertStateSql;
    private final String insertStateSql;
    private final String selectStateSql;
    private final String selectStateListSql;
    private final String deleteStateByKeySql;
    private final String deleteSessionSql;
    private final String selectMaxIndexSql;
    private final String existsSql;
    private final String listSessionIdsSql;

    /**
     * Creates a store with default table name and auto-creation disabled.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     * @param dialect the dialect for SQL generation; must not be {@code null}
     */
    public JdbcAgentStateStore(DataSource dataSource, AgentStateStoreDialect dialect) {
        this(dataSource, dialect, DEFAULT_TABLE_NAME, false);
    }

    /**
     * Creates a store with full configuration.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     * @param dialect the dialect for SQL generation; must not be {@code null}
     * @param tableName the sessions table name; defaults to {@value #DEFAULT_TABLE_NAME}
     * @param createIfNotExist when {@code true}, auto-creates the table (and database
     *     if the dialect supports it); when {@code false}, verifies the table exists
     */
    public JdbcAgentStateStore(
            DataSource dataSource,
            AgentStateStoreDialect dialect,
            String tableName,
            boolean createIfNotExist) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
        this.dialect = requireNonNull(dialect, "dialect");
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim();
        validateIdentifier(this.tableName, "Table name");
        this.fullTableRef = this.dialect.getFullTableReference(null, this.tableName);

        // Pre-resolve all SQL templates with the full table reference
        this.upsertStateSql = String.format(dialect.getUpsertStateSql(), fullTableRef);
        this.insertStateSql = String.format(dialect.getInsertStateSql(), fullTableRef);
        this.selectStateSql = String.format(dialect.getSelectStateSql(), fullTableRef);
        this.selectStateListSql = String.format(dialect.getSelectStateListSql(), fullTableRef);
        this.deleteStateByKeySql = String.format(dialect.getDeleteStateByKeySql(), fullTableRef);
        this.deleteSessionSql = String.format(dialect.getDeleteSessionSql(), fullTableRef);
        this.selectMaxIndexSql = String.format(dialect.getSelectMaxIndexSql(), fullTableRef);
        this.existsSql = String.format(dialect.getExistsSql(), fullTableRef);
        this.listSessionIdsSql = String.format(dialect.getListSessionIdsSql(), fullTableRef);

        if (createIfNotExist) {
            createDatabaseIfSupported();
            createTableIfNotExist();
        } else {
            verifyTableExists();
        }
    }

    // -------------------------------------------------------------------------
    //  Schema management
    // -------------------------------------------------------------------------

    private void createDatabaseIfSupported() {
        String ddl = dialect.getCreateDatabaseSql(null);
        if (ddl == null) {
            return; // ANSI databases skip database creation
        }
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(ddl)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database", e);
        }
    }

    private void createTableIfNotExist() {
        String ddl = String.format(dialect.getCreateSessionsTableSql(), fullTableRef);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(ddl)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    private void verifyTableExists() {
        String checkSql = dialect.getCheckTableExistsSql();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + tableName
                                    + "."
                                    + " Use createIfNotExist=true to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    // -------------------------------------------------------------------------
    //  AgentStateStore implementation
    // -------------------------------------------------------------------------

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(upsertStateSql)) {
                            String json = JsonUtils.getJsonCodec().toJson(value);
                            stmt.setString(1, slotId);
                            stmt.setString(2, key);
                            stmt.setInt(3, SINGLE_STATE_INDEX);
                            stmt.setString(4, json);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        if (values.isEmpty()) {
            return;
        }

        String hashKey = key + HASH_KEY_SUFFIX;

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        String currentHash = ListHashUtil.computeHash(values);
                        String storedHash = getStoredHash(conn, slotId, hashKey);
                        int existingCount = getListCount(conn, slotId, key);
                        boolean needsFullRewrite =
                                ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

                        if (needsFullRewrite) {
                            deleteListItems(conn, slotId, key);
                            insertItems(conn, slotId, key, values, 0);
                            saveHash(conn, slotId, hashKey, currentHash);
                        } else if (values.size() > existingCount) {
                            List<? extends State> newItems =
                                    values.subList(existingCount, values.size());
                            insertItems(conn, slotId, key, newItems, existingCount);
                            saveHash(conn, slotId, hashKey, currentHash);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectStateSql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_data");
                    return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectStateListSql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    String json = rs.getString("state_data");
                    result.add(JsonUtils.getJsonCodec().fromJson(json, itemType));
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(existsSql)) {
            stmt.setString(1, slotId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(deleteSessionSql)) {
                            stmt.setString(1, slotId);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        String prefix = userSegment + ":";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(listSessionIdsSql)) {
            stmt.setString(1, prefix + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> sessionIds = new HashSet<>();
                while (rs.next()) {
                    String slot = rs.getString("session_id");
                    sessionIds.add(slot.substring(prefix.length()));
                }
                return sessionIds;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    // -------------------------------------------------------------------------
    //  Internal helpers — list state operations
    // -------------------------------------------------------------------------

    private String getStoredHash(Connection conn, String slotId, String hashKey)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(selectStateSql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("state_data") : null;
            }
        }
    }

    private void saveHash(Connection conn, String slotId, String hashKey, String hash)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(upsertStateSql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.executeUpdate();
        }
    }

    private void deleteListItems(Connection conn, String slotId, String key) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(deleteStateByKeySql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.executeUpdate();
        }
    }

    private void insertItems(
            Connection conn, String slotId, String key, List<? extends State> items, int startIndex)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(insertStateSql)) {
            int index = startIndex;
            for (State item : items) {
                String json = JsonUtils.getJsonCodec().toJson(item);
                stmt.setString(1, slotId);
                stmt.setString(2, key);
                stmt.setInt(3, index);
                stmt.setString(4, json);
                stmt.addBatch();
                index++;
            }
            stmt.executeBatch();
        }
    }

    private int getListCount(Connection conn, String slotId, String key) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(selectMaxIndexSql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int maxIndex = rs.getInt(1);
                    if (rs.wasNull()) {
                        return 0;
                    }
                    return maxIndex + 1;
                }
                return 0;
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Transaction + validation helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }

    private void executeInWriteTransaction(Connection conn, SqlOperation operation)
            throws Exception {
        boolean originalAutoCommit = conn.getAutoCommit();
        if (originalAutoCommit) {
            conn.setAutoCommit(false);
        }
        try {
            operation.execute();
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    private static void validateSlotId(String slotId) {
        if (slotId == null || slotId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (slotId.contains("/") || slotId.contains("\\")) {
            throw new IllegalArgumentException("Session ID cannot contain path separators");
        }
        if (slotId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    private static void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    private static void validateIdentifier(String identifier, String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " cannot be null or empty");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters,"
                            + " underscores, and hyphens are allowed. Invalid value: "
                            + identifier);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
