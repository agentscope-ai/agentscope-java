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
package io.agentscope.core.skill.repository.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL database-based implementation of AgentSkillRepository.
 *
 * <p>This implementation stores skills in MySQL database tables with the following structure:
 *
 * <ul>
 *   <li>Skills table: stores core lookup fields ({@code name}, {@code description}), skill
 *       content, source, and optionally the full metadata tree in {@code metadata_json}
 *   <li>Resources table: stores skill resources ({@code id}, {@code resource_path},
 *       {@code resource_content})
 * </ul>
 *
 * <p>Table schema for newly created tables ({@code createIfNotExist=true}):
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_skills (
 *     id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 *     name VARCHAR(255) NOT NULL UNIQUE,
 *     description TEXT NOT NULL,
 *     skill_content LONGTEXT NOT NULL,
 *     source VARCHAR(255) NOT NULL,
 *     metadata_json LONGTEXT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 *
 * CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
 *     id BIGINT NOT NULL,
 *     resource_path VARCHAR(500) NOT NULL,
 *     resource_content LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     PRIMARY KEY (id, resource_path),
 *     FOREIGN KEY (id) REFERENCES agentscope_skills(id) ON DELETE CASCADE
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>Compatibility behavior:
 *
 * <ul>
 *   <li>New tables created by this repository include {@code metadata_json}
 *   <li>Existing tables are not auto-migrated with {@code ALTER TABLE}
 *   <li>When {@code metadata_json} exists, full skill metadata is persisted and restored
 *   <li>When {@code metadata_json} does not exist, the repository falls back to the legacy
 *       schema and only round-trips {@code name} and {@code description}
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Automatic database/table creation when {@code createIfNotExist=true}
 *   <li>Runtime compatibility detection for legacy and new schemas
 *   <li>Full CRUD operations for skills and their resources
 *   <li>SQL injection prevention through parameterized queries
 *   <li>Transaction support for atomic operations
 *   <li>UTF-8 (utf8mb4) character set support for internationalization
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // Using simple constructor with default database/table names
 * DataSource dataSource = createDataSource();
 * MysqlSkillRepository repo = new MysqlSkillRepository(dataSource, true, true);
 *
 * // Using Builder for custom configuration
 * MysqlSkillRepository repo = MysqlSkillRepository.builder(dataSource)
 *         .databaseName("my_database")
 *         .skillsTableName("my_skills")
 *         .resourcesTableName("my_resources")
 *         .createIfNotExist(true)
 *         .writeable(true)
 *         .build();
 *
 * // Save a skill
 * AgentSkill skill = new AgentSkill("my-skill", "Description", "Content", resources);
 * repo.save(List.of(skill), false);
 *
 * // Get a skill
 * AgentSkill loaded = repo.getSkill("my-skill");
 * }</pre>
 */
public class MysqlSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(MysqlSkillRepository.class);

    /** Default database name for skill storage. */
    private static final String DEFAULT_DATABASE_NAME = "agentscope";

    /** Default table name for storing skills. */
    private static final String DEFAULT_SKILLS_TABLE_NAME = "agentscope_skills";

    /** Default table name for storing skill resources. */
    private static final String DEFAULT_RESOURCES_TABLE_NAME = "agentscope_skill_resources";

    /**
     * Pattern for validating database and table names.
     * Only allows alphanumeric characters and underscores, must start with letter
     * or underscore.
     * This prevents SQL injection attacks through malicious database/table names.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** MySQL identifier length limit. */
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    /** Maximum length for skill name. */
    private static final int MAX_SKILL_NAME_LENGTH = 255;

    /** Maximum length for resource path. */
    private static final int MAX_RESOURCE_PATH_LENGTH = 500;

    private final DataSource dataSource;
    private final String databaseName;
    private final String skillsTableName;
    private final String resourcesTableName;
    private final boolean metadataJsonColumnSupported;
    private boolean writeable;

    /**
     * Create a MysqlSkillRepository with default database and table names.
     *
     * <p>
     * This constructor uses default database name ({@code agentscope}) and table
     * names ({@code agentscope_skills} and {@code agentscope_skill_resources}).
     *
     * @param dataSource       DataSource for database connections
     * @param createIfNotExist If true, auto-create the database and tables for new deployments; if
     *                         false, require existing schema. Existing tables are not auto-migrated
     *                         to add {@code metadata_json}
     * @param writeable        Whether the repository supports write operations
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException    if createIfNotExist is false and
     *                                  database/tables do not exist
     */
    public MysqlSkillRepository(
            DataSource dataSource, boolean createIfNotExist, boolean writeable) {
        this(
                dataSource,
                DEFAULT_DATABASE_NAME,
                DEFAULT_SKILLS_TABLE_NAME,
                DEFAULT_RESOURCES_TABLE_NAME,
                createIfNotExist,
                writeable);
    }

    /**
     * Create a MysqlSkillRepository with custom database name, table names, and
     * options.
     *
     * <p>
     * If {@code createIfNotExist} is true, the database and tables will be created automatically if
     * they don't exist. If false and the database or tables don't exist, an
     * {@link IllegalStateException} will be thrown. Existing tables are validated as-is and are not
     * auto-migrated to add {@code metadata_json}.
     *
     * <p>
     * This constructor is private. Use {@link #builder(DataSource)} to create instances
     * with custom configuration.
     *
     * @param dataSource         DataSource for database connections
     * @param databaseName       Custom database name (uses default if null or
     *                           empty)
     * @param skillsTableName    Custom skills table name (uses default if null or
     *                           empty)
     * @param resourcesTableName Custom resources table name (uses default if null
     *                           or empty)
     * @param createIfNotExist   If true, auto-create the database and tables for new deployments;
     *                           if false, require existing schema. Existing tables are not
     *                           auto-migrated to add {@code metadata_json}
     * @param writeable          Whether the repository supports write operations
     * @throws IllegalArgumentException if dataSource is null or identifiers are
     *                                  invalid
     * @throws IllegalStateException    if createIfNotExist is false and
     *                                  database/tables do not exist
     */
    private MysqlSkillRepository(
            DataSource dataSource,
            String databaseName,
            String skillsTableName,
            String resourcesTableName,
            boolean createIfNotExist,
            boolean writeable) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.writeable = writeable;

        // Use defaults if null or empty, then validate
        this.databaseName =
                (databaseName == null || databaseName.trim().isEmpty())
                        ? DEFAULT_DATABASE_NAME
                        : databaseName.trim();
        this.skillsTableName =
                (skillsTableName == null || skillsTableName.trim().isEmpty())
                        ? DEFAULT_SKILLS_TABLE_NAME
                        : skillsTableName.trim();
        this.resourcesTableName =
                (resourcesTableName == null || resourcesTableName.trim().isEmpty())
                        ? DEFAULT_RESOURCES_TABLE_NAME
                        : resourcesTableName.trim();

        // Validate identifiers to prevent SQL injection
        validateIdentifier(this.databaseName, "Database name");
        validateIdentifier(this.skillsTableName, "Skills table name");
        validateIdentifier(this.resourcesTableName, "Resources table name");

        if (createIfNotExist) {
            // Create database and tables if they don't exist
            createDatabaseIfNotExist();
            createTablesIfNotExist();
        } else {
            // Verify database and tables exist
            verifyDatabaseExists();
            verifyTablesExist();
        }

        this.metadataJsonColumnSupported = detectMetadataJsonColumnSupport();

        logger.info(
                "MysqlSkillRepository initialized with database: {}, skills table: {},"
                        + " resources table: {}",
                this.databaseName,
                this.skillsTableName,
                this.resourcesTableName);
    }

    /**
     * Create the database if it doesn't exist.
     *
     * <p>
     * Creates the database with UTF-8 (utf8mb4) character set and unicode collation
     * for proper internationalization support.
     */
    private void createDatabaseIfNotExist() {
        String createDatabaseSql =
                "CREATE DATABASE IF NOT EXISTS "
                        + databaseName
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createDatabaseSql)) {
            stmt.execute();
            logger.debug("Database created or already exists: {}", databaseName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database: " + databaseName, e);
        }
    }

    /**
     * Create the skills and resources tables if they don't exist.
     *
     * <p>Newly created skills tables include the optional {@code metadata_json} column so complete
     * skill metadata can be persisted without changing the legacy lookup columns.
     */
    private void createTablesIfNotExist() {
        // Create skills table with id as primary key and name as unique
        String createSkillsTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(skillsTableName)
                        + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + " name VARCHAR(255) NOT NULL UNIQUE, description TEXT NOT NULL,"
                        + " skill_content LONGTEXT NOT NULL, source VARCHAR(255) NOT NULL,"
                        + " metadata_json LONGTEXT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) DEFAULT"
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        // Create resources table with id as foreign key
        String createResourcesTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(resourcesTableName)
                        + " (id BIGINT NOT NULL, resource_path VARCHAR(500) NOT NULL,"
                        + " resource_content LONGTEXT NOT NULL, created_at TIMESTAMP DEFAULT"
                        + " CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON"
                        + " UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id, resource_path),"
                        + " FOREIGN KEY (id) REFERENCES "
                        + getFullTableName(skillsTableName)
                        + "(id) ON DELETE CASCADE)"
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(createSkillsTableSql)) {
                stmt.execute();
                logger.debug("Skills table created or already exists: {}", skillsTableName);
            }

            try (PreparedStatement stmt = conn.prepareStatement(createResourcesTableSql)) {
                stmt.execute();
                logger.debug("Resources table created or already exists: {}", resourcesTableName);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create tables", e);
        }
    }

    /**
     * Verify that the database exists.
     *
     * @throws IllegalStateException if database does not exist
     */
    private void verifyDatabaseExists() {
        String checkSql =
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Database does not exist: "
                                    + databaseName
                                    + ". Use MysqlSkillRepository(dataSource, true) to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check database existence: " + databaseName, e);
        }
    }

    /**
     * Verify that the required tables exist.
     *
     * @throws IllegalStateException if any table does not exist
     */
    private void verifyTablesExist() {
        verifyTableExists(skillsTableName);
        verifyTableExists(resourcesTableName);
    }

    /**
     * Verify that a specific table exists.
     *
     * @param tableName the table name to check
     * @throws IllegalStateException if table does not exist
     */
    private void verifyTableExists(String tableName) {
        String checkSql =
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + databaseName
                                    + "."
                                    + tableName
                                    + ". Use MysqlSkillRepository(dataSource, true) to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * Get the full table name with database prefix.
     *
     * @param tableName the table name
     * @return The full table name (database.table)
     */
    private String getFullTableName(String tableName) {
        return databaseName + "." + tableName;
    }

    /**
     * Detect whether the current skills table supports the optional {@code metadata_json} column.
     *
     * <p>This capability is cached at repository construction time and drives the read/write
     * compatibility path: full metadata round-trip when present, legacy fallback when absent.
     */
    private boolean detectMetadataJsonColumnSupport() {
        String checkSql =
                "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
                        + " AND COLUMN_NAME = ? LIMIT 1";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, skillsTableName);
            stmt.setString(3, "metadata_json");
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warn(
                    "Failed to detect metadata_json column support, falling back to legacy schema",
                    e);
            return false;
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        validateSkillName(name);

        String selectSkillSql =
                "SELECT id, name, description, skill_content, source"
                        + (metadataJsonColumnSupported ? ", metadata_json" : "")
                        + " FROM "
                        + getFullTableName(skillsTableName)
                        + " WHERE name = ?";

        String selectResourcesSql =
                "SELECT resource_path, resource_content FROM "
                        + getFullTableName(resourcesTableName)
                        + " WHERE id = ?";

        try (Connection conn = dataSource.getConnection()) {
            // Load skill metadata
            long skillId;
            String description;
            String skillContent;
            String source;
            String metadataJson = null;

            try (PreparedStatement stmt = conn.prepareStatement(selectSkillSql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Skill not found: " + name);
                    }
                    skillId = rs.getLong("id");
                    description = rs.getString("description");
                    skillContent = rs.getString("skill_content");
                    source = rs.getString("source");
                    if (metadataJsonColumnSupported) {
                        metadataJson = rs.getString("metadata_json");
                    }
                }
            }

            // Load skill resources using skillId
            Map<String, String> resources = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(selectResourcesSql)) {
                stmt.setLong(1, skillId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString("resource_path");
                        String content = rs.getString("resource_content");
                        resources.put(path, content);
                    }
                }
            }

            return buildSkill(name, description, skillContent, source, metadataJson, resources);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load skill: " + name, e);
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        String selectSql =
                "SELECT name FROM " + getFullTableName(skillsTableName) + " ORDER BY name";

        List<String> skillNames = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                skillNames.add(rs.getString("name"));
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list skill names", e);
        }

        return skillNames;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        String selectAllSkillsSql =
                "SELECT id, name, description, skill_content, source"
                        + (metadataJsonColumnSupported ? ", metadata_json" : "")
                        + " FROM "
                        + getFullTableName(skillsTableName)
                        + " ORDER BY name";

        String selectAllResourcesSql =
                "SELECT id, resource_path, resource_content FROM "
                        + getFullTableName(resourcesTableName);

        try (Connection conn = dataSource.getConnection()) {
            // Load all skills in one query, use id as key for mapping resources
            Map<Long, LoadedSkillRecord> skillRecords = new HashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(selectAllSkillsSql);
                    ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long skillId = rs.getLong("id");
                    String name = rs.getString("name");
                    String description = rs.getString("description");
                    String skillContent = rs.getString("skill_content");
                    String source = rs.getString("source");
                    String metadataJson =
                            metadataJsonColumnSupported ? rs.getString("metadata_json") : null;

                    skillRecords.put(
                            skillId,
                            new LoadedSkillRecord(
                                    name, description, skillContent, source, metadataJson));
                }
            }

            // Load all resources in one query and map them to skills using id
            try (PreparedStatement stmt = conn.prepareStatement(selectAllResourcesSql);
                    ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long skillId = rs.getLong("id");
                    String resourcePath = rs.getString("resource_path");
                    String resourceContent = rs.getString("resource_content");

                    LoadedSkillRecord record = skillRecords.get(skillId);
                    if (record != null) {
                        record.resources.put(resourcePath, resourceContent);
                    } else {
                        logger.warn("Found orphaned resource for non-existent id: {}", skillId);
                    }
                }
            }

            // Build all skills
            List<AgentSkill> skills = new ArrayList<>(skillRecords.size());
            for (LoadedSkillRecord record : skillRecords.values()) {
                try {
                    skills.add(
                            buildSkill(
                                    record.name,
                                    record.description,
                                    record.skillContent,
                                    record.source,
                                    record.metadataJson,
                                    record.resources));
                } catch (Exception e) {
                    logger.warn("Failed to build skill: {}", e.getMessage(), e);
                }
            }

            return skills;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load all skills", e);
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        if (!writeable) {
            logger.warn("Cannot save skills: repository is read-only");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            // Pre-check: validate all skill names and resource paths before transaction
            for (AgentSkill skill : skills) {
                validateSkillName(skill.getName());

                // Validate resource paths before transaction to avoid unnecessary rollback
                Map<String, String> resources = skill.getResources();
                if (resources != null && !resources.isEmpty()) {
                    for (String path : resources.keySet()) {
                        validateResourcePath(path);
                    }
                }
            }

            // Pre-check: if force=false, check all skills for existence before starting
            // transaction
            if (!force) {
                List<String> existingSkills = new ArrayList<>();
                for (AgentSkill skill : skills) {
                    if (skillExistsInternal(conn, skill.getName())) {
                        existingSkills.add(skill.getName());
                    }
                }
                if (!existingSkills.isEmpty()) {
                    String conflictingSkills = String.join(", ", existingSkills);
                    throw new IllegalStateException(
                            "Cannot save skills: the following skills already exist and"
                                    + " force=false: "
                                    + conflictingSkills
                                    + ". Use force=true to overwrite existing skills.");
                }
            }

            // Use transaction for atomic operations
            conn.setAutoCommit(false);

            try {
                for (AgentSkill skill : skills) {
                    String skillName = skill.getName();

                    // Check if skill exists (for force=true case)
                    boolean exists = skillExistsInternal(conn, skillName);

                    if (exists) {
                        // Delete existing skill and its resources
                        deleteSkillInternal(conn, skillName);
                        logger.debug("Deleted existing skill for overwrite: {}", skillName);
                    }

                    // Insert skill and get generated id
                    long skillId = insertSkill(conn, skill);

                    // Insert resources using skillId
                    insertResources(conn, skillId, skill.getResources());

                    logger.info("Successfully saved skill: {} (id={})", skillName, skillId);
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }

        } catch (SQLException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * Insert a skill into the database and return the generated id.
     *
     * @param conn  the database connection
     * @param skill the skill to insert
     * @return the generated id
     * @throws SQLException if insertion fails
     */
    private long insertSkill(Connection conn, AgentSkill skill) throws SQLException {
        String insertSql =
                "INSERT INTO "
                        + getFullTableName(skillsTableName)
                        + (metadataJsonColumnSupported
                                ? " (name, description, skill_content, source, metadata_json)"
                                        + " VALUES (?, ?, ?, ?, ?)"
                                : " (name, description, skill_content, source) VALUES (?, ?, ?,"
                                        + " ?)");

        try (PreparedStatement stmt =
                conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, skill.getName());
            stmt.setString(2, skill.getDescription());
            stmt.setString(3, skill.getSkillContent());
            stmt.setString(4, skill.getSource());
            if (metadataJsonColumnSupported) {
                stmt.setString(5, serializeMetadata(skill.getMetadata()));
            } else if (hasExtendedMetadata(skill.getMetadata())) {
                logger.warn(
                        "metadata_json column not found in {}.{}; extended metadata for skill '{}'"
                                + " will not be persisted",
                        databaseName,
                        skillsTableName,
                        skill.getName());
            }
            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                } else {
                    throw new SQLException(
                            "Failed to get generated id for skill: " + skill.getName());
                }
            }
        }
    }

    /**
     * Insert resources for a skill using batch processing.
     *
     * <p>
     * This method uses JDBC batch processing to insert all resources in a single
     * network round-trip, significantly improving performance for skills with
     * multiple resources.
     *
     * @param conn      the database connection
     * @param skillId   the id to associate resources with
     * @param resources the resources to insert
     * @throws SQLException if insertion fails
     */
    private void insertResources(Connection conn, long skillId, Map<String, String> resources)
            throws SQLException {
        if (resources == null || resources.isEmpty()) {
            logger.debug("No resources to insert for id: {}", skillId);
            return;
        }

        // Note: Resource paths are validated in save() before transaction starts

        String insertSql =
                "INSERT INTO "
                        + getFullTableName(resourcesTableName)
                        + " (id, resource_path, resource_content) VALUES (?, ?, ?)";

        // Use batch processing for better performance
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String path = entry.getKey();
                String content = entry.getValue();

                stmt.setLong(1, skillId);
                stmt.setString(2, path);
                stmt.setString(3, content);

                stmt.addBatch();
            }

            // Execute all inserts in one batch
            int[] results = stmt.executeBatch();

            // Count successful insertions
            int insertedCount = 0;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > 0 || results[i] == Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                } else if (results[i] == Statement.EXECUTE_FAILED) {
                    logger.error("Failed to insert resource at batch index {}", i);
                }
            }

            logger.debug(
                    "Batch inserted {} resources for id '{}' (total: {})",
                    insertedCount,
                    skillId,
                    resources.size());

            if (insertedCount != resources.size()) {
                throw new SQLException(
                        "Failed to insert all resources for id '"
                                + skillId
                                + "'. Expected: "
                                + resources.size()
                                + ", Inserted: "
                                + insertedCount);
            }
        }
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        validateSkillName(skillName);

        try (Connection conn = dataSource.getConnection()) {
            if (!skillExistsInternal(conn, skillName)) {
                logger.warn("Skill does not exist: {}", skillName);
                return false;
            }

            conn.setAutoCommit(false);
            try {
                deleteSkillInternal(conn, skillName);
                conn.commit();
                logger.info("Successfully deleted skill: {}", skillName);
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }

        } catch (SQLException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * Delete a skill and its resources from the database.
     *
     * <p>
     * Resources are deleted automatically via ON DELETE CASCADE, but we also
     * delete the skill by name which triggers the cascade.
     *
     * @param conn      the database connection
     * @param skillName the skill name to delete
     * @throws SQLException if deletion fails
     */
    private void deleteSkillInternal(Connection conn, String skillName) throws SQLException {
        // Delete skill by name - resources will be deleted via ON DELETE CASCADE
        String deleteSkillSql =
                "DELETE FROM " + getFullTableName(skillsTableName) + " WHERE name = ?";

        try (PreparedStatement stmt = conn.prepareStatement(deleteSkillSql)) {
            stmt.setString(1, skillName);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            return skillExistsInternal(conn, skillName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check skill existence: " + skillName, e);
        }
    }

    /**
     * Check if a skill exists using an existing connection.
     *
     * @param conn      the database connection
     * @param skillName the skill name to check
     * @return true if the skill exists
     * @throws SQLException if query fails
     */
    private boolean skillExistsInternal(Connection conn, String skillName) throws SQLException {
        String checkSql =
                "SELECT 1 FROM " + getFullTableName(skillsTableName) + " WHERE name = ? LIMIT 1";

        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, skillName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                "mysql", databaseName + "." + skillsTableName, writeable);
    }

    @Override
    public String getSource() {
        return "mysql_" + databaseName + "_" + skillsTableName;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void close() {
        // DataSource is managed externally, so we don't close it here
        logger.debug("MysqlSkillRepository closed");
    }

    /**
     * Get the database name used for storing skills.
     *
     * @return the database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Get the skills table name.
     *
     * @return the skills table name
     */
    public String getSkillsTableName() {
        return skillsTableName;
    }

    /**
     * Get the resources table name.
     *
     * @return the resources table name
     */
    public String getResourcesTableName() {
        return resourcesTableName;
    }

    /**
     * Get the DataSource used for database connections.
     *
     * @return the DataSource instance
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Exposes whether the connected skills table supports {@code metadata_json}.
     *
     * <p>Package-private for tests.
     */
    boolean isMetadataJsonColumnSupported() {
        return metadataJsonColumnSupported;
    }

    /**
     * Build an {@link AgentSkill} from SQL row data, restoring full metadata when available and
     * otherwise falling back to legacy core metadata.
     */
    private AgentSkill buildSkill(
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson,
            Map<String, String> resources) {
        Map<String, Object> metadata = deserializeMetadata(metadataJson, name, description);
        return new AgentSkill(metadata, skillContent, resources, source);
    }

    /**
     * Deserialize {@code metadata_json} when present, then overlay the authoritative SQL columns
     * for {@code name} and {@code description}.
     */
    private Map<String, Object> deserializeMetadata(
            String metadataJson, String name, String description) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                Map<String, Object> parsed =
                        JsonUtils.getJsonCodec()
                                .fromJson(
                                        metadataJson, new TypeReference<Map<String, Object>>() {});
                if (parsed != null) {
                    metadata.putAll(parsed);
                }
            } catch (RuntimeException e) {
                logger.warn(
                        "Failed to deserialize metadata_json for skill '{}', falling back to core"
                                + " metadata",
                        name,
                        e);
            }
        }
        metadata.put("name", name);
        metadata.put("description", description);
        return metadata;
    }

    /** Serialize the complete skill metadata tree for storage in {@code metadata_json}. */
    private String serializeMetadata(Map<String, Object> metadata) {
        return JsonUtils.getJsonCodec().toJson(metadata);
    }

    /**
     * Check whether metadata contains fields beyond the legacy core columns.
     *
     * <p>This is used only to emit a downgrade warning when writing to a legacy schema.
     */
    private boolean hasExtendedMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return metadata.size() > 2
                || metadata.keySet().stream()
                        .anyMatch(key -> !"name".equals(key) && !"description".equals(key));
    }

    /** Temporary holder used while stitching skills and resources from separate result sets. */
    private static final class LoadedSkillRecord {
        private final String name;
        private final String description;
        private final String skillContent;
        private final String source;
        private final String metadataJson;
        private final Map<String, String> resources = new HashMap<>();

        private LoadedSkillRecord(
                String name,
                String description,
                String skillContent,
                String source,
                String metadataJson) {
            this.name = name;
            this.description = description;
            this.skillContent = skillContent;
            this.source = source;
            this.metadataJson = metadataJson;
        }
    }

    /**
     * Clear all skills from the database (for testing or cleanup).
     *
     * <p>
     * Resources are deleted automatically via ON DELETE CASCADE when skills are deleted.
     *
     * @return the number of skills deleted
     */
    public int clearAllSkills() {
        if (!writeable) {
            logger.warn("Cannot clear skills: repository is read-only");
            return 0;
        }

        // Resources will be deleted automatically via ON DELETE CASCADE
        String deleteSkillsSql = "DELETE FROM " + getFullTableName(skillsTableName);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete all skills (resources are deleted via CASCADE)
                int deleted;
                try (PreparedStatement stmt = conn.prepareStatement(deleteSkillsSql)) {
                    deleted = stmt.executeUpdate();
                }

                conn.commit();
                logger.info("Cleared all skills, {} skills deleted", deleted);
                return deleted;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear skills", e);
        }
    }

    /**
     * Safely restore auto-commit mode on a connection.
     *
     * <p>
     * This method catches and logs any SQLException that may occur when restoring
     * auto-commit mode, preventing it from masking the original exception in a
     * finally block.
     *
     * @param conn the connection to restore auto-commit on
     */
    private void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            logger.warn("Failed to restore auto-commit mode on connection", e);
        }
    }

    /**
     * Validate a skill name.
     *
     * @param skillName the skill name to validate
     * @throws IllegalArgumentException if the skill name is invalid
     */
    private void validateSkillName(String skillName) {
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        if (skillName.length() > MAX_SKILL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill name cannot exceed " + MAX_SKILL_NAME_LENGTH + " characters");
        }
        // Check for path traversal attempts
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) {
            throw new IllegalArgumentException("Skill name cannot contain path separators or '..'");
        }
    }

    /**
     * Validate a resource path.
     *
     * @param path the resource path to validate
     * @throws IllegalArgumentException if the path is invalid
     */
    private void validateResourcePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path cannot be null or empty");
        }
        if (path.length() > MAX_RESOURCE_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "Resource path cannot exceed " + MAX_RESOURCE_PATH_LENGTH + " characters");
        }
    }

    /**
     * Validate a database or table identifier to prevent SQL injection.
     *
     * <p>
     * This method ensures that identifiers only contain safe characters
     * (alphanumeric and
     * underscores) and start with a letter or underscore. This is critical for
     * security since
     * database and table names cannot be parameterized in prepared statements.
     *
     * @param identifier     The identifier to validate (database name or table
     *                       name)
     * @param identifierType Description of the identifier type for error messages
     * @throws IllegalArgumentException if the identifier is invalid or contains
     *                                  unsafe characters
     */
    private void validateIdentifier(String identifier, String identifierType) {
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
                            + " contains invalid characters. Only alphanumeric characters and"
                            + " underscores are allowed, and it must start with a letter or"
                            + " underscore. Invalid value: "
                            + identifier);
        }
    }

    /**
     * Create a new Builder for MysqlSkillRepository.
     *
     * <p>
     * Example usage:
     *
     * <pre>{@code
     * MysqlSkillRepository repo = MysqlSkillRepository.builder(dataSource)
     *         .databaseName("my_database")
     *         .skillsTableName("my_skills")
     *         .resourcesTableName("my_resources")
     *         .createIfNotExist(true)
     *         .writeable(true)
     *         .build();
     * }</pre>
     *
     * @param dataSource DataSource for database connections (required)
     * @return a new Builder instance
     * @throws IllegalArgumentException if dataSource is null
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /**
     * Builder for creating MysqlSkillRepository instances with custom configuration.
     *
     * <p>
     * This builder provides a fluent API for configuring all aspects of the repository,
     * including database name, table names, and behavior options.
     */
    public static class Builder {

        private final DataSource dataSource;
        private String databaseName = DEFAULT_DATABASE_NAME;
        private String skillsTableName = DEFAULT_SKILLS_TABLE_NAME;
        private String resourcesTableName = DEFAULT_RESOURCES_TABLE_NAME;
        private boolean createIfNotExist = true;
        private boolean writeable = true;

        /**
         * Create a new Builder with the required DataSource.
         *
         * @param dataSource DataSource for database connections
         * @throws IllegalArgumentException if dataSource is null
         */
        private Builder(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalArgumentException("DataSource cannot be null");
            }
            this.dataSource = dataSource;
        }

        /**
         * Set the database name for storing skills.
         *
         * @param databaseName the database name (default: "agentscope")
         * @return this builder for method chaining
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * Set the skills table name.
         *
         * @param skillsTableName the skills table name (default: "agentscope_skills")
         * @return this builder for method chaining
         */
        public Builder skillsTableName(String skillsTableName) {
            this.skillsTableName = skillsTableName;
            return this;
        }

        /**
         * Set the resources table name.
         *
         * @param resourcesTableName the resources table name (default:
         *                           "agentscope_skill_resources")
         * @return this builder for method chaining
         */
        public Builder resourcesTableName(String resourcesTableName) {
            this.resourcesTableName = resourcesTableName;
            return this;
        }

        /**
         * Set whether to create database and tables if they don't exist.
         *
         * @param createIfNotExist true to auto-create, false to require existing
         *                         (default: true)
         * @return this builder for method chaining
         */
        public Builder createIfNotExist(boolean createIfNotExist) {
            this.createIfNotExist = createIfNotExist;
            return this;
        }

        /**
         * Set whether the repository supports write operations.
         *
         * @param writeable true to enable write operations, false for read-only
         *                  (default: true)
         * @return this builder for method chaining
         */
        public Builder writeable(boolean writeable) {
            this.writeable = writeable;
            return this;
        }

        /**
         * Build the MysqlSkillRepository instance.
         *
         * @return a new MysqlSkillRepository instance
         * @throws IllegalArgumentException if identifiers are invalid
         * @throws IllegalStateException    if createIfNotExist is false and
         *                                  database/tables do not exist
         */
        public MysqlSkillRepository build() {
            return new MysqlSkillRepository(
                    dataSource,
                    databaseName,
                    skillsTableName,
                    resourcesTableName,
                    createIfNotExist,
                    writeable);
        }
    }
}
