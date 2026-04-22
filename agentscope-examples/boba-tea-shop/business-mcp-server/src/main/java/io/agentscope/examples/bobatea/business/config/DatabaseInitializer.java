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

package io.agentscope.examples.bobatea.business.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Database Initializer
 * Checks if database tables exist and structure meets expectations on application startup
 * If tables do not exist or structure does not match, rebuilds tables and inserts initial data
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    // Table name list, ordered by dependencies (creation order)
    private static final List<String> TABLES_CREATE_ORDER =
            Arrays.asList("users", "products", "orders", "feedback");
    // Drop order (reverse of creation order, to handle foreign key constraints)
    private static final List<String> TABLES_DROP_ORDER =
            Arrays.asList("feedback", "orders", "products", "users");

    // Expected table structure (column name list)
    private static final Map<String, Set<String>> EXPECTED_COLUMNS = new HashMap<>();

    static {
        EXPECTED_COLUMNS.put(
                "users",
                new HashSet<>(
                        Arrays.asList(
                                "id",
                                "username",
                                "phone",
                                "email",
                                "nickname",
                                "status",
                                "created_at",
                                "updated_at")));
        EXPECTED_COLUMNS.put(
                "products",
                new HashSet<>(
                        Arrays.asList(
                                "id",
                                "name",
                                "description",
                                "price",
                                "stock",
                                "shelf_time",
                                "preparation_time",
                                "is_seasonal",
                                "season_start",
                                "season_end",
                                "is_regional",
                                "available_regions",
                                "status",
                                "created_at",
                                "updated_at")));
        EXPECTED_COLUMNS.put(
                "orders",
                new HashSet<>(
                        Arrays.asList(
                                "id",
                                "order_id",
                                "user_id",
                                "product_id",
                                "product_name",
                                "sweetness",
                                "ice_level",
                                "quantity",
                                "unit_price",
                                "total_price",
                                "remark",
                                "created_at",
                                "updated_at")));
        EXPECTED_COLUMNS.put(
                "feedback",
                new HashSet<>(
                        Arrays.asList(
                                "id",
                                "order_id",
                                "user_id",
                                "feedback_type",
                                "rating",
                                "content",
                                "solution",
                                "created_at",
                                "updated_at")));
    }

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("========== Starting database initialization check ==========");

        try {
            // Check and initialize all tables
            Map<String, TableStatus> tableStatuses = checkAllTables();

            // Determine if rebuild is needed
            boolean needRebuild =
                    tableStatuses.values().stream()
                            .anyMatch(
                                    status ->
                                            status == TableStatus.NOT_EXISTS
                                                    || status == TableStatus.STRUCTURE_MISMATCH);

            if (needRebuild) {
                logger.info("Tables requiring rebuild detected, starting database rebuild...");
                rebuildAllTables();
                logger.info("Database rebuild completed");
            } else {
                // Check if data needs to be inserted
                boolean needData =
                        tableStatuses.values().stream()
                                .anyMatch(status -> status == TableStatus.EMPTY);
                if (needData) {
                    logger.info("Empty tables detected, starting initial data insertion...");
                    insertAllData();
                    logger.info("Initial data insertion completed");
                } else {
                    logger.info(
                            "Database structure and data meet expectations, no initialization"
                                    + " needed");
                }
            }
        } catch (Exception e) {
            logger.error("Database initialization failed", e);
            throw new RuntimeException("Database initialization failed", e);
        }

        logger.info("========== Database initialization check completed ==========");
    }

    /**
     * Check status of all tables
     */
    private Map<String, TableStatus> checkAllTables() {
        Map<String, TableStatus> statuses = new LinkedHashMap<>();
        for (String tableName : TABLES_CREATE_ORDER) {
            TableStatus status = checkTableStatus(tableName);
            statuses.put(tableName, status);
            logger.info("Table [{}] status: {}", tableName, status.getDescription());
        }
        return statuses;
    }

    /**
     * Check status of a single table
     */
    private TableStatus checkTableStatus(String tableName) {
        // Check if table exists
        if (!tableExists(tableName)) {
            return TableStatus.NOT_EXISTS;
        }

        // Check if table structure meets expectations
        if (!validateTableStructure(tableName)) {
            return TableStatus.STRUCTURE_MISMATCH;
        }

        // Check if table has data
        if (isTableEmpty(tableName)) {
            return TableStatus.EMPTY;
        }

        return TableStatus.OK;
    }

    /**
     * Check if table exists
     */
    private boolean tableExists(String tableName) {
        try {
            String sql =
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                            + " AND table_name = ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            logger.warn(
                    "Exception occurred while checking if table [{}] exists: {}",
                    tableName,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Validate if table structure meets expectations
     */
    private boolean validateTableStructure(String tableName) {
        try {
            Set<String> expectedColumns = EXPECTED_COLUMNS.get(tableName);
            if (expectedColumns == null) {
                logger.warn("Expected structure not defined for table [{}]", tableName);
                return true;
            }

            // Get actual column names
            String sql =
                    "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                            + " DATABASE() AND table_name = ?";
            List<String> actualColumns = jdbcTemplate.queryForList(sql, String.class, tableName);
            Set<String> actualColumnSet = new HashSet<>(actualColumns);

            // Check if all expected columns exist
            for (String expectedColumn : expectedColumns) {
                if (!actualColumnSet.contains(expectedColumn)) {
                    logger.warn("Table [{}] missing column: {}", tableName, expectedColumn);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            logger.warn(
                    "Exception occurred while validating table [{}] structure: {}",
                    tableName,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Check if table is empty
     */
    private boolean isTableEmpty(String tableName) {
        try {
            String sql = "SELECT COUNT(*) FROM `" + tableName + "`";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count == null || count == 0;
        } catch (Exception e) {
            logger.warn(
                    "Exception occurred while checking if table [{}] is empty: {}",
                    tableName,
                    e.getMessage());
            return true;
        }
    }

    /**
     * Rebuild all tables
     */
    private void rebuildAllTables() {
        // First delete all tables (in reverse dependency order)
        for (String tableName : TABLES_DROP_ORDER) {
            dropTableIfExists(tableName);
        }

        // Create all tables
        String schemaSql = loadSqlFile("db/schema.sql");
        executeSqlStatements(schemaSql);
        logger.info("All tables created");

        // Insert initial data
        insertAllData();
    }

    /**
     * Drop table (if exists)
     */
    private void dropTableIfExists(String tableName) {
        try {
            // Temporarily disable foreign key checks
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbcTemplate.execute("DROP TABLE IF EXISTS `" + tableName + "`");
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
            logger.info("Deleted table: {}", tableName);
        } catch (Exception e) {
            logger.warn(
                    "Exception occurred while deleting table [{}]: {}", tableName, e.getMessage());
        }
    }

    /**
     * Insert all initial data
     */
    private void insertAllData() {
        String dataSql = loadSqlFile("db/data.sql");
        executeSqlStatements(dataSql);
        logger.info("All initial data inserted");
    }

    /**
     * Load SQL file content
     */
    private String loadSqlFile(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load SQL file: " + path, e);
        }
    }

    /**
     * Execute SQL statements (supports multiple statements)
     */
    private void executeSqlStatements(String sql) {
        // Remove comment lines
        String[] lines = sql.split("\n");
        StringBuilder cleanSql = new StringBuilder();
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("--") && !trimmedLine.isEmpty()) {
                cleanSql.append(line).append("\n");
            }
        }

        // Split by semicolon and execute
        String[] statements = cleanSql.toString().split(";");
        for (String statement : statements) {
            String trimmedStatement = statement.trim();
            if (!trimmedStatement.isEmpty()) {
                try {
                    jdbcTemplate.execute(trimmedStatement);
                } catch (Exception e) {
                    logger.warn(
                            "Failed to execute SQL statement: {}",
                            trimmedStatement.substring(
                                    0, Math.min(100, trimmedStatement.length())));
                    logger.warn("Error message: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Table status enumeration
     */
    private enum TableStatus {
        NOT_EXISTS("Table does not exist"),
        STRUCTURE_MISMATCH("Table structure does not meet expectations"),
        EMPTY("Table is empty"),
        OK("Normal");

        private final String description;

        TableStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
