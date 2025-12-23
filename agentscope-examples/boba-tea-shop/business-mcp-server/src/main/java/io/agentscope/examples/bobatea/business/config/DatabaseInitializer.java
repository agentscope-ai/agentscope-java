/*
 * Copyright 2025 the original author or authors.
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
 * 数据库初始化器
 * 在应用启动时检查数据库表是否存在、结构是否符合预期
 * 如果表不存在或结构不符，则重建表并插入初始数据
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    // 表名列表，按依赖顺序排列（创建顺序）
    private static final List<String> TABLES_CREATE_ORDER =
            Arrays.asList("users", "products", "orders", "feedback");
    // 删除顺序（与创建顺序相反，处理外键约束）
    private static final List<String> TABLES_DROP_ORDER =
            Arrays.asList("feedback", "orders", "products", "users");

    // 预期的表结构（列名列表）
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
        logger.info("========== 开始数据库初始化检查 ==========");

        try {
            // 检查并初始化所有表
            Map<String, TableStatus> tableStatuses = checkAllTables();

            // 判断是否需要重建
            boolean needRebuild =
                    tableStatuses.values().stream()
                            .anyMatch(
                                    status ->
                                            status == TableStatus.NOT_EXISTS
                                                    || status == TableStatus.STRUCTURE_MISMATCH);

            if (needRebuild) {
                logger.info("检测到需要重建的表，开始重建数据库...");
                rebuildAllTables();
                logger.info("数据库重建完成");
            } else {
                // 检查是否需要插入数据
                boolean needData =
                        tableStatuses.values().stream()
                                .anyMatch(status -> status == TableStatus.EMPTY);
                if (needData) {
                    logger.info("检测到空表，开始插入初始数据...");
                    insertAllData();
                    logger.info("初始数据插入完成");
                } else {
                    logger.info("数据库结构和数据均符合预期，无需初始化");
                }
            }
        } catch (Exception e) {
            logger.error("数据库初始化失败", e);
            throw new RuntimeException("数据库初始化失败", e);
        }

        logger.info("========== 数据库初始化检查完成 ==========");
    }

    /**
     * 检查所有表的状态
     */
    private Map<String, TableStatus> checkAllTables() {
        Map<String, TableStatus> statuses = new LinkedHashMap<>();
        for (String tableName : TABLES_CREATE_ORDER) {
            TableStatus status = checkTableStatus(tableName);
            statuses.put(tableName, status);
            logger.info("表 [{}] 状态: {}", tableName, status.getDescription());
        }
        return statuses;
    }

    /**
     * 检查单个表的状态
     */
    private TableStatus checkTableStatus(String tableName) {
        // 检查表是否存在
        if (!tableExists(tableName)) {
            return TableStatus.NOT_EXISTS;
        }

        // 检查表结构是否符合预期
        if (!validateTableStructure(tableName)) {
            return TableStatus.STRUCTURE_MISMATCH;
        }

        // 检查表是否有数据
        if (isTableEmpty(tableName)) {
            return TableStatus.EMPTY;
        }

        return TableStatus.OK;
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(String tableName) {
        try {
            String sql =
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                            + " AND table_name = ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            logger.warn("检查表 [{}] 是否存在时发生异常: {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * 验证表结构是否符合预期
     */
    private boolean validateTableStructure(String tableName) {
        try {
            Set<String> expectedColumns = EXPECTED_COLUMNS.get(tableName);
            if (expectedColumns == null) {
                logger.warn("未定义表 [{}] 的预期结构", tableName);
                return true;
            }

            // 获取实际列名
            String sql =
                    "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                            + " DATABASE() AND table_name = ?";
            List<String> actualColumns = jdbcTemplate.queryForList(sql, String.class, tableName);
            Set<String> actualColumnSet = new HashSet<>(actualColumns);

            // 检查是否所有预期列都存在
            for (String expectedColumn : expectedColumns) {
                if (!actualColumnSet.contains(expectedColumn)) {
                    logger.warn("表 [{}] 缺少列: {}", tableName, expectedColumn);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            logger.warn("验证表 [{}] 结构时发生异常: {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * 检查表是否为空
     */
    private boolean isTableEmpty(String tableName) {
        try {
            String sql = "SELECT COUNT(*) FROM `" + tableName + "`";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count == null || count == 0;
        } catch (Exception e) {
            logger.warn("检查表 [{}] 是否为空时发生异常: {}", tableName, e.getMessage());
            return true;
        }
    }

    /**
     * 重建所有表
     */
    private void rebuildAllTables() {
        // 先删除所有表（按反向依赖顺序）
        for (String tableName : TABLES_DROP_ORDER) {
            dropTableIfExists(tableName);
        }

        // 创建所有表
        String schemaSql = loadSqlFile("db/schema.sql");
        executeSqlStatements(schemaSql);
        logger.info("所有表创建完成");

        // 插入初始数据
        insertAllData();
    }

    /**
     * 删除表（如果存在）
     */
    private void dropTableIfExists(String tableName) {
        try {
            // 暂时禁用外键检查
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbcTemplate.execute("DROP TABLE IF EXISTS `" + tableName + "`");
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
            logger.info("已删除表: {}", tableName);
        } catch (Exception e) {
            logger.warn("删除表 [{}] 时发生异常: {}", tableName, e.getMessage());
        }
    }

    /**
     * 插入所有初始数据
     */
    private void insertAllData() {
        String dataSql = loadSqlFile("db/data.sql");
        executeSqlStatements(dataSql);
        logger.info("所有初始数据插入完成");
    }

    /**
     * 加载 SQL 文件内容
     */
    private String loadSqlFile(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载 SQL 文件失败: " + path, e);
        }
    }

    /**
     * 执行 SQL 语句（支持多条语句）
     */
    private void executeSqlStatements(String sql) {
        // 移除注释行
        String[] lines = sql.split("\n");
        StringBuilder cleanSql = new StringBuilder();
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("--") && !trimmedLine.isEmpty()) {
                cleanSql.append(line).append("\n");
            }
        }

        // 按分号分割并执行
        String[] statements = cleanSql.toString().split(";");
        for (String statement : statements) {
            String trimmedStatement = statement.trim();
            if (!trimmedStatement.isEmpty()) {
                try {
                    jdbcTemplate.execute(trimmedStatement);
                } catch (Exception e) {
                    logger.warn(
                            "执行 SQL 语句失败: {}",
                            trimmedStatement.substring(
                                    0, Math.min(100, trimmedStatement.length())));
                    logger.warn("错误信息: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 表状态枚举
     */
    private enum TableStatus {
        NOT_EXISTS("表不存在"),
        STRUCTURE_MISMATCH("表结构不符合预期"),
        EMPTY("表为空"),
        OK("正常");

        private final String description;

        TableStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
