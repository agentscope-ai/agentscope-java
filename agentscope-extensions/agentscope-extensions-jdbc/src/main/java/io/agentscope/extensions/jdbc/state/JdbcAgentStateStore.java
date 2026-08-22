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
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SessionStateDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database-agnostic session state store backed by {@link SessionStateDialect}.
 *
 * <p>Implements {@link AgentStateStore} with zero inline SQL — every statement is sourced
 * from the dialect via {@link BoundSql}.
 *
 * @author shanhongyu
 */
public class JdbcAgentStateStore implements AgentStateStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentStateStore.class);

    private static final String HASH_KEY_SUFFIX = ":_hash";
    private static final int SINGLE_STATE_INDEX = 0;

    private final DataSource dataSource;
    private final SessionStateDialect dialect;

    /**
     * Creates a store with auto-schema creation disabled.
     *
     * @param dataSource the JDBC data source
     * @param dialect the session-state dialect
     */
    public JdbcAgentStateStore(DataSource dataSource, SessionStateDialect dialect) {
        this(dataSource, dialect, false);
    }

    /**
     * Creates a store with optional auto-schema creation.
     *
     * @param dataSource the JDBC data source
     * @param dialect the session-state dialect
     * @param createIfNotExist when true, auto-creates the sessions table
     */
    public JdbcAgentStateStore(
            DataSource dataSource, SessionStateDialect dialect, boolean createIfNotExist) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
        this.dialect = requireNonNull(dialect, "dialect");
        if (createIfNotExist) {
            createTableIfNotExist();
        } else {
            verifyTableExists();
        }
    }

    // -------------------------------------------------------------------------
    //  Schema management
    // -------------------------------------------------------------------------

    private void createTableIfNotExist() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : dialect.sessionStateCreateTableDdls()) {
                stmt.execute(ddl);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table", e);
        }
    }

    private void verifyTableExists() {
        BoundSql boundSql = dialect.sessionStateCheckTableExists(dialect.sessionStateTableName());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + dialect.sessionStateTableName()
                                    + ". Use createIfNotExist=true to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence", e);
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
                        BoundSql boundSql =
                                dialect.sessionStateUpsert(
                                        slotId,
                                        key,
                                        SINGLE_STATE_INDEX,
                                        JsonUtils.getJsonCodec().toJson(value));
                        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
                            bindParams(stmt, boundSql.params());
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
                            LOG.debug(
                                    "List rewrite for key '{}': existing={}, incoming={}",
                                    key,
                                    existingCount,
                                    values.size());
                            deleteListItems(conn, slotId, key);
                            insertItems(conn, slotId, key, values, 0);
                            saveHash(conn, slotId, hashKey, currentHash);
                        } else if (values.size() > existingCount) {
                            // Incremental append: the stored hash matched the prefix of the
                            // incoming list, so only the tail is inserted. A hash collision
                            // here would silently diverge the stored state — logged for
                            // troubleshooting.
                            LOG.debug(
                                    "Incremental append for key '{}': appending {} items after"
                                            + " existing {}",
                                    key,
                                    values.size() - existingCount,
                                    existingCount);
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

        BoundSql boundSql = dialect.sessionStateSelect(slotId, key, SINGLE_STATE_INDEX);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(
                            JsonUtils.getJsonCodec().fromJson(rs.getString("state_data"), type));
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

        BoundSql boundSql = dialect.sessionStateSelectList(slotId, key);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(
                            JsonUtils.getJsonCodec()
                                    .fromJson(rs.getString("state_data"), itemType));
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

        BoundSql boundSql = dialect.sessionStateExists(slotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
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
                        BoundSql boundSql = dialect.sessionStateDeleteSession(slotId);
                        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
                            bindParams(stmt, boundSql.params());
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

        // Escape LIKE wildcards (_ and %) plus the escape char itself so session IDs that
        // merely resemble the user prefix (e.g. a real user "u_anon_x" vs. the anonymous
        // namespace "__anon__") are not matched by the pattern.
        BoundSql boundSql = dialect.sessionStateListSessionIds(likePrefixPattern(prefix));
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
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

    /**
     * Builds a {@code LIKE} prefix pattern that matches exactly {@code prefix} followed by
     * anything, escaping every {@code _}, {@code %} and the dialect's escape char so the
     * user segment is treated literally.
     */
    private String likePrefixPattern(String prefix) {
        char esc = dialect.sessionStateLikeEscapeChar();
        StringBuilder sb = new StringBuilder();
        for (char ch : prefix.toCharArray()) {
            if (ch == esc || ch == '%' || ch == '_') {
                sb.append(esc);
            }
            sb.append(ch);
        }
        sb.append('%');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Internal helpers — list state operations
    // -------------------------------------------------------------------------

    private String getStoredHash(Connection conn, String slotId, String hashKey)
            throws SQLException {
        BoundSql boundSql = dialect.sessionStateSelect(slotId, hashKey, SINGLE_STATE_INDEX);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("state_data") : null;
            }
        }
    }

    private void saveHash(Connection conn, String slotId, String hashKey, String hash)
            throws SQLException {
        BoundSql boundSql = dialect.sessionStateUpsert(slotId, hashKey, SINGLE_STATE_INDEX, hash);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            stmt.executeUpdate();
        }
    }

    private void deleteListItems(Connection conn, String slotId, String key) throws SQLException {
        BoundSql boundSql = dialect.sessionStateDeleteByKey(slotId, key);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            stmt.executeUpdate();
        }
    }

    private void insertItems(
            Connection conn, String slotId, String key, List<? extends State> items, int startIndex)
            throws Exception {
        // All dialects return the same SQL template from sessionStateInsert (only params vary),
        // so we prepare once and batch — matching the MySQL module's addBatch/executeBatch pattern.
        String firstJson = JsonUtils.getJsonCodec().toJson(items.get(0));
        BoundSql firstBound = dialect.sessionStateInsert(slotId, key, startIndex, firstJson);
        try (PreparedStatement stmt = conn.prepareStatement(firstBound.sql())) {
            bindParams(stmt, firstBound.params());
            stmt.addBatch();
            int index = startIndex + 1;
            for (int i = 1; i < items.size(); i++) {
                String json = JsonUtils.getJsonCodec().toJson(items.get(i));
                BoundSql boundSql = dialect.sessionStateInsert(slotId, key, index, json);
                bindParams(stmt, boundSql.params());
                stmt.addBatch();
                index++;
            }
            stmt.executeBatch();
        }
    }

    private int getListCount(Connection conn, String slotId, String key) throws SQLException {
        BoundSql boundSql = dialect.sessionStateSelectMaxIndex(slotId, key);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
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

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
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

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
