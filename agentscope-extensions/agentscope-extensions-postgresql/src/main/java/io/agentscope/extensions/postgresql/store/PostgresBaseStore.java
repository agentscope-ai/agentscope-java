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
package io.agentscope.extensions.postgresql.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * PostgreSQL-backed {@link BaseStore}.
 *
 * <h2>Schema</h2>
 *
 * <pre>{@code
 * CREATE TABLE agentscope_store (
 *   namespace_path  VARCHAR(2048) NOT NULL,
 *   item_key        VARCHAR(255)  NOT NULL,
 *   value_json      TEXT          NOT NULL,
 *   version         BIGINT        NOT NULL,
 *   updated_at      BIGINT        NOT NULL,
 *   PRIMARY KEY (namespace_path, item_key)
 * )
 * }</pre>
 *
 * <p>The optional {@code Builder#initializeSchema(true)} flag runs
 * {@code CREATE TABLE IF NOT EXISTS} on construction.
 *
 * <h2>Namespace encoding</h2>
 *
 * <p>To preserve the prefix-search behaviour of {@link InMemoryStore},
 * the namespace components are joined with the ASCII unit-separator {@code 0x1F} and stored with a
 * trailing separator. Searching by namespace {@code [a, b]} translates into
 * {@code WHERE namespace_path LIKE 'ab%' ESCAPE '!'}, which matches both that exact
 * namespace and any deeper sub-namespace. Namespace segments that contain {@code 0x1F} are
 * rejected.
 *
 * <h2>CAS</h2>
 *
 * <p>{@link #putIfVersion} uses a single-statement conditional {@code UPDATE} keyed on
 * {@code version}. The {@code expectedVersion == 0} case issues an {@code INSERT} and treats a
 * primary-key conflict as a failed CAS. Both paths are atomic without additional locking, so the
 * store is safe to share among multiple JVMs writing to the same database.
 */
public class PostgresBaseStore implements BaseStore {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresBaseStore.class);

    /**
     * ASCII unit separator (U+001F) used between namespace segments inside {@code namespace_path}.
     * Picked because it does not collide with printable identifiers and is rare in JSON / user
     * input. Namespace segments containing it are rejected.
     */
    private static final String NS_SEPARATOR = "";

    /** Default table name used when the builder is not customised. */
    public static final String DEFAULT_TABLE_NAME = "agentscope_store";

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String CREATE_TABLE_SQL =
            """
            CREATE TABLE IF NOT EXISTS %s (
                namespace_path VARCHAR(2048) NOT NULL,
                item_key       VARCHAR(255)  NOT NULL,
                value_json     TEXT          NOT NULL,
                version        BIGINT        NOT NULL,
                updated_at     BIGINT        NOT NULL,
                PRIMARY KEY (namespace_path, item_key)
            )
            """;

    private static final String SELECT_SQL =
            "SELECT value_json, version FROM %s WHERE namespace_path = ? AND item_key = ?";

    private static final String UPSERT_SQL =
            """
            INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (namespace_path, item_key) DO UPDATE SET
                value_json = EXCLUDED.value_json,
                version    = %1$s.version + 1,,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String INSERT_SQL =
            """
            INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)
            VALUES (?, ?, ?, 1, ?)
            """;

    private static final String CAS_UPDATE_SQL =
            """
            UPDATE %s SET
                value_json = ?,
                version    = version + 1,
                updated_at = ?
            WHERE namespace_path = ? AND item_key = ? AND version = ?
            """;

    private static final String DELETE_SQL =
            "DELETE FROM %s WHERE namespace_path = ? AND item_key = ?";

    private static final String SEARCH_SQL =
            """
            SELECT item_key, value_json, version FROM %s
            WHERE namespace_path LIKE ? ESCAPE '!'
            ORDER BY item_key
            LIMIT ? OFFSET ?
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;

    private final String selectSql;
    private final String upsertSql;
    private final String insertSql;
    private final String casUpdateSql;
    private final String deleteSql;
    private final String searchSql;

    private PostgresBaseStore(Builder b) {
        this.dataSource = b.dataSource;
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
        this.tableName = b.tableName;
        this.selectSql = String.format(SELECT_SQL, tableName);
        this.upsertSql = String.format(UPSERT_SQL, tableName);
        this.insertSql = String.format(INSERT_SQL, tableName);
        this.casUpdateSql = String.format(CAS_UPDATE_SQL, tableName);
        this.deleteSql = String.format(DELETE_SQL, tableName);
        this.searchSql = String.format(SEARCH_SQL, tableName);
        if (b.initializeSchema) {
            initializeSchema();
        }
    }

    /** Creates a builder for {@link PostgresBaseStore}. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    private void initializeSchema() {
        String ddl = String.format(CREATE_TABLE_SQL, tableName);
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize PostgresBaseStore schema for table " + tableName, e);
        }
    }

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
            throw new IllegalStateException("PostgresBaseStore get failed", e);
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
            throw new IllegalStateException("PostgresBaseStore put failed", e);
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
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(insertSql)) {
                ps.setString(1, nsPath);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    return false;
                }
                throw new IllegalStateException(
                        "PostgresBaseStore putIfVersion (insert) failed", e);
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
            throw new IllegalStateException("PostgresBaseStore putIfVersion (update) failed", e);
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
            throw new IllegalStateException("PostgresBaseStore search failed", e);
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
            throw new IllegalStateException("PostgresBaseStore delete failed", e);
        }
    }

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
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

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
        char esc = '!';
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

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /** Builder for {@link PostgresBaseStore}. */
    public static final class Builder {

        private final DataSource dataSource;
        private ObjectMapper objectMapper;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean initializeSchema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /** Sets a custom Jackson {@link ObjectMapper} for value serialisation. */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Overrides the table name. Must match the regex {@code [A-Za-z_][A-Za-z0-9_]*}; this is
         * the only place a table identifier ever flows into the SQL string verbatim, so the
         * validation here is the SQL-injection guard.
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
         * When {@code true}, runs {@code CREATE TABLE IF NOT EXISTS} during construction.
         * Defaults to {@code false} — production deployments usually own their schema via
         * migration tooling.
         */
        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /** Builds the {@link PostgresBaseStore}. */
        public PostgresBaseStore build() {
            PostgresBaseStore store = new PostgresBaseStore(this);
            LOG.debug("PostgresBaseStore built: table={}", store.tableName);
            return store;
        }
    }
}
