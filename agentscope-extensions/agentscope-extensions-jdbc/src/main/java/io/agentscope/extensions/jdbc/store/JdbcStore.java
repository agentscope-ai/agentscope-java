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
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.StoreDialect;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
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
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed {@link BaseStore} with zero inline SQL — all database-specific SQL is
 * delegated to {@link StoreDialect}.
 *
 * <p>Supports MySQL, PostgreSQL, SQLite, and H2 via the dialect abstraction. Business
 * logic (CAS, namespace encoding, hash-based change detection, pagination) is completely
 * database-agnostic.
 *
 * @author shanhongyu
 */
public class JdbcStore implements BaseStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcStore.class);

    /** ASCII unit separator (U+001F) between namespace segments. */
    private static final String NS_SEPARATOR = "";

    private final DataSource dataSource;
    private final StoreDialect dialect;
    private final ObjectMapper objectMapper;

    private JdbcStore(Builder b) {
        this.dataSource = b.dataSource;
        this.dialect = b.dialect;
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
        if (b.initializeSchema) {
            initializeSchema();
        }
    }

    /** Creates a builder for {@link JdbcStore}. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    private void initializeSchema() {
        String ddl = dialect.storeCreateTableSql();
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize JdbcStore schema", e);
        }
    }

    // -------------------------------------------------------------------------
    //  BaseStore implementation
    // -------------------------------------------------------------------------

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        BoundSql boundSql = dialect.storeSelect(namespacePath(namespace), key);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
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
        String json = serialize(value);
        BoundSql boundSql =
                dialect.storeUpsert(
                        namespacePath(namespace), key, json, System.currentTimeMillis());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
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
            BoundSql boundSql = dialect.storeInsert(nsPath, key, json, now);
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
                bindParams(ps, boundSql.params());
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

        BoundSql boundSql = dialect.storeCasUpdate(json, now, nsPath, key, expectedVersion);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
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
        BoundSql boundSql = dialect.storeSearch(pattern, limit, safeOffset);
        List<StoreItem> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
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
        BoundSql boundSql = dialect.storeDelete(namespacePath(namespace), key);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore delete failed", e);
        }
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
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
            return objectMapper.readValue(json, new TypeReference<>() {});
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
        char esc = dialect.storeLikeEscapeChar();
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
        if (state != null && state.startsWith("23")) {
            return true;
        }
        return e.getErrorCode() == 19 && e.getClass().getName().startsWith("org.sqlite.");
    }

    // -------------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------------

    /** Builder for {@link JdbcStore}. */
    public static final class Builder {

        private final DataSource dataSource;
        private StoreDialect dialect;
        private ObjectMapper objectMapper;
        private boolean initializeSchema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /** Sets the store dialect (required). */
        public Builder dialect(StoreDialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
            return this;
        }

        /** Sets a custom Jackson ObjectMapper. */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /** When true, runs the dialect's CREATE TABLE during construction. */
        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /** Builds the JdbcStore. */
        public JdbcStore build() {
            if (dialect == null) {
                throw new IllegalStateException(
                        "dialect must be set; use JdbcDistributedStore or pass a"
                                + " AbstractJdbcDialect");
            }
            JdbcStore store = new JdbcStore(this);
            LOG.debug("JdbcStore built: dialect={}", store.dialect.getClass().getSimpleName());
            return store;
        }
    }
}
