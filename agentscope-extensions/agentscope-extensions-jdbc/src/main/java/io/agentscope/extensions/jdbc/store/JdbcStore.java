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
package io.agentscope.extensions.jdbc.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.jdbc.dialect.JdbcStoreDialect;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed {@link BaseStore} with zero inline SQL — all database-specific SQL
 * is delegated to {@link JdbcStoreDialect}.
 *
 * <p>Supports MySQL, PostgreSQL, SQLite, and H2 out of the box via the dialect
 * interface. The business logic (CAS, namespace encoding, hash-based change
 * detection, pagination) is completely database-agnostic.
 *
 * <h2>Schema</h2>
 *
 * <pre>{@code
 * CREATE TABLE agentscope_store (
 *   namespace_path  -- VARCHAR or TEXT, joined namespace + trailing 0x1F
 *   item_key        -- VARCHAR or TEXT, item key
 *   value_json      -- TEXT / LONGTEXT / CLOB, Jackson-serialised Map
 *   version         -- BIGINT / INTEGER, monotonically increasing per row
 *   updated_at      -- BIGINT / INTEGER, epoch millis of the last write
 *   PRIMARY KEY (namespace_path, item_key)
 * )
 * }</pre>
 *
 * <h2>Namespace encoding</h2>
 *
 * <p>To preserve the prefix-search behaviour of {@link InMemoryStore}, namespace
 * components are joined with the ASCII unit separator {@code 0x1F} and stored
 * with a trailing separator.
 *
 * <h2>CAS</h2>
 *
 * <p>{@link #putIfVersion} runs a single-statement conditional UPDATE keyed on
 * {@code version}. The {@code expectedVersion == 0} case (create-if-absent) issues
 * an INSERT and treats a primary-key violation as a failed CAS. Both paths are
 * atomic without additional locking.
 *
 * @author shanhongyu
 */
public class JdbcStore implements BaseStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcStore.class);

    /**
     * ASCII unit separator (U+001F) used between namespace segments. Picked because
     * it does not collide with printable identifiers and is rare in JSON / user
     * input.
     */
    private static final String NS_SEPARATOR = "";

    /** Default table name used when the builder is not customised. */
    public static final String DEFAULT_TABLE_NAME = "agentscope_store";

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final JdbcStoreDialect dialect;
    private final ObjectMapper objectMapper;
    private final String tableName;

    // Pre-resolved SQL (table name substituted once at construction)
    private final String selectSql;
    private final String upsertSql;
    private final String insertSql;
    private final String casUpdateSql;
    private final String deleteSql;
    private final String searchSql;

    private JdbcStore(Builder b) {
        this.dataSource = b.dataSource;
        this.dialect = b.dialect;
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
        this.tableName = b.tableName;
        this.selectSql = String.format(dialect.getSelectSql(), tableName);
        this.upsertSql = String.format(dialect.getUpsertSql(), tableName);
        this.insertSql = String.format(dialect.getInsertSql(), tableName);
        this.casUpdateSql = String.format(dialect.getCasUpdateSql(), tableName);
        this.deleteSql = String.format(dialect.getDeleteSql(), tableName);
        this.searchSql = String.format(dialect.getSearchSql(), tableName);
        if (b.initializeSchema) {
            initializeSchema();
        }
    }

    /** Creates a builder for {@link JdbcStore}. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    private void initializeSchema() {
        String ddl = String.format(dialect.getCreateTableSql(), tableName);
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize JdbcStore schema for table " + tableName, e);
        }
    }

    // -------------------------------------------------------------------------
    //  BaseStore implementation
    // -------------------------------------------------------------------------

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(selectSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String json = rs.getString(1);
                long version = rs.getLong(2);
                return new StoreItem(key, deserialize(json), version);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore get failed", e);
        }
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        String json = serialize(value);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(upsertSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            ps.setString(3, json);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore put failed", e);
        }
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String nsPath = namespacePath(namespace);
        String json = serialize(value);
        long now = System.currentTimeMillis();

        if (expectedVersion == 0L) {
            // Create-if-absent: rely on the PK constraint to detect a pre-existing row.
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(insertSql)) {
                ps.setString(1, nsPath);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLIntegrityConstraintViolationException dup) {
                return false;
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    return false;
                }
                throw new IllegalStateException("JdbcStore putIfVersion (insert) failed", e);
            }
        }

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(casUpdateSql)) {
            ps.setString(1, json);
            ps.setLong(2, now);
            ps.setString(3, nsPath);
            ps.setString(4, key);
            ps.setLong(5, expectedVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore putIfVersion (update) failed", e);
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String pattern = likePrefixPattern(namespace);
        List<StoreItem> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(searchSql)) {
            ps.setString(1, pattern);
            ps.setInt(2, limit);
            ps.setInt(3, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemKey = rs.getString(1);
                    String json = rs.getString(2);
                    long version = rs.getLong(3);
                    result.add(new StoreItem(itemKey, deserialize(json), version));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore search failed", e);
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(deleteSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore delete failed", e);
        }
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode store value", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

    /**
     * Joins namespace segments with the unit separator, always trailing the result
     * with one so prefix queries can distinguish {@code "a/b"} from {@code "a/bc"}.
     */
    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (String segment : namespace) {
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (segment.indexOf(NS_SEPARATOR.charAt(0)) >= 0) {
                throw new IllegalArgumentException(
                        "namespace segment must not contain the unit separator (0x1F)");
            }
            sb.append(segment).append(NS_SEPARATOR);
        }
        return sb.toString();
    }

    private String likePrefixPattern(List<String> namespace) {
        char esc = dialect.getLikeEscapeChar();
        StringBuilder sb = new StringBuilder();
        for (char ch : namespacePath(namespace).toCharArray()) {
            if (ch == esc || ch == '%' || ch == '_') {
                sb.append(esc);
            }
            sb.append(ch);
        }
        sb.append('%');
        return sb.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
    }

    /**
     * Returns {@code true} if the given exception represents a primary-key / unique
     * constraint violation across common JDBC drivers.
     */
    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        // SQLite reports constraint violations as errorCode=19 with a null SQLSTATE.
        return e.getErrorCode() == 19 && e.getClass().getName().startsWith("org.sqlite.");
    }

    // -------------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------------

    /** Builder for {@link JdbcStore}. */
    public static final class Builder {

        private final DataSource dataSource;
        private JdbcStoreDialect dialect;
        private ObjectMapper objectMapper;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean initializeSchema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /**
         * Sets the dialect explicitly. When omitted, the dialect is auto-detected
         * via {@link JdbcStoreDialect} on {@link #build()}.
         */
        public Builder dialect(JdbcStoreDialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
            return this;
        }

        /** Sets a custom Jackson {@link ObjectMapper} for value serialisation. */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Overrides the table name. Must match {@code [A-Za-z_][A-Za-z0-9_]*}; this
         * is the only place a table identifier ever flows into the SQL string
         * verbatim, so the validation here is the SQL-injection guard.
         */
        public Builder tableName(String tableName) {
            if (tableName == null
                    || tableName.isBlank()
                    || !VALID_TABLE_NAME.matcher(tableName).matches()) {
                throw new IllegalArgumentException(
                        "tableName must match [A-Za-z_][A-Za-z0-9_]*, got: " + tableName);
            }
            this.tableName = tableName;
            return this;
        }

        /**
         * When {@code true}, runs the dialect's {@code CREATE TABLE IF NOT EXISTS}
         * during construction. Defaults to {@code false}.
         */
        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /** Builds the {@link JdbcStore}. */
        public JdbcStore build() {
            if (dialect == null) {
                throw new IllegalStateException(
                        "dialect must be set; use JdbcDistributedStore or pass a JdbcDialect");
            }
            JdbcStore store = new JdbcStore(this);
            LOG.debug(
                    "JdbcStore built: table={}, dialect={}",
                    store.tableName,
                    store.dialect.getClass().getSimpleName());
            return store;
        }
    }
}
