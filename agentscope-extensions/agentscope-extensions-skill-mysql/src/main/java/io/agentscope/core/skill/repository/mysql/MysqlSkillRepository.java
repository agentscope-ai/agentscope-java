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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL database-based implementation of AgentSkillRepository.
 *
 * <p>
 * This implementation stores skills in MySQL database tables with the following
 * structure:
 *
 * <ul>
 * <li>Skills table: stores skill metadata (name, description, content, source)
 * <li>Resources table: stores skill resources (skill_name, resource_path,
 * resource_content)
 * </ul>
 *
 * <p>
 * Table Schema (auto-created if createIfNotExist=true):
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_skills (
 *     name VARCHAR(255) NOT NULL PRIMARY KEY,
 *     description TEXT NOT NULL,
 *     skill_content LONGTEXT NOT NULL,
 *     source VARCHAR(255) NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 *
 * CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
 *     skill_name VARCHAR(255) NOT NULL,
 *     resource_path VARCHAR(500) NOT NULL,
 *     resource_content LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     PRIMARY KEY (skill_name, resource_path),
 *     FOREIGN KEY (skill_name) REFERENCES agentscope_skills(name) ON DELETE CASCADE
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Automatic table creation when createIfNotExist=true
 * <li>Full CRUD operations for skills and their resources
 * <li>SQL injection prevention through parameterized queries
 * <li>Transaction support for atomic operations
 * <li>UTF-8 (utf8mb4) character set support for internationalization
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // Using constructor
 * DataSource dataSource = createDataSource();
 * MysqlSkillRepository repo = new MysqlSkillRepository(dataSource, true);
 *
 * // Using builder pattern
 * MysqlSkillRepository repo = MysqlSkillRepository.builder()
 *         .dataSource(dataSource)
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
    private boolean writeable;

    /**
     * Create a MysqlSkillRepository with default settings.
     *
     * <p>
     * This constructor uses default database name ({@code agentscope}) and table
     * names,
     * and does NOT auto-create the database or tables. If the database or tables do
     * not exist,
     * an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException    if database or tables do not exist
     */
    public MysqlSkillRepository(DataSource dataSource) {
        this(dataSource, false);
    }

    /**
     * Create a MysqlSkillRepository with optional auto-creation of database and
     * tables.
     *
     * <p>
     * This constructor uses default database name ({@code agentscope}) and table
     * names.
     * If {@code createIfNotExist} is true, the database and tables will be created
     * automatically
     * if they don't exist. If false and the database or tables don't exist, an
     * {@link IllegalStateException} will be thrown.
     *
     * @param dataSource       DataSource for database connections
     * @param createIfNotExist If true, auto-create database and tables; if false,
     *                         require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException    if createIfNotExist is false and
     *                                  database/tables do not exist
     */
    public MysqlSkillRepository(DataSource dataSource, boolean createIfNotExist) {
        this(
                dataSource,
                DEFAULT_DATABASE_NAME,
                DEFAULT_SKILLS_TABLE_NAME,
                DEFAULT_RESOURCES_TABLE_NAME,
                createIfNotExist,
                true);
    }

    /**
     * Create a MysqlSkillRepository with custom database name, table names, and
     * options.
     *
     * <p>
     * If {@code createIfNotExist} is true, the database and tables will be created
     * automatically
     * if they don't exist. If false and the database or tables don't exist, an
     * {@link IllegalStateException} will be thrown.
     *
     * @param dataSource         DataSource for database connections
     * @param databaseName       Custom database name (uses default if null or
     *                           empty)
     * @param skillsTableName    Custom skills table name (uses default if null or
     *                           empty)
     * @param resourcesTableName Custom resources table name (uses default if null
     *                           or empty)
     * @param createIfNotExist   If true, auto-create database and tables; if false,
     *                           require existing
     * @param writeable          Whether the repository supports write operations
     * @throws IllegalArgumentException if dataSource is null or identifiers are
     *                                  invalid
     * @throws IllegalStateException    if createIfNotExist is false and
     *                                  database/tables do not exist
     */
    public MysqlSkillRepository(
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
     */
    private void createTablesIfNotExist() {
        // Create skills table
        String createSkillsTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(skillsTableName)
                        + " (name VARCHAR(255) NOT NULL PRIMARY KEY, description TEXT NOT NULL,"
                        + " skill_content LONGTEXT NOT NULL, source VARCHAR(255) NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) DEFAULT"
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        // Create resources table with foreign key
        String createResourcesTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName(resourcesTableName)
                        + " (skill_name VARCHAR(255) NOT NULL, resource_path VARCHAR(500) NOT NULL,"
                        + " resource_content LONGTEXT NOT NULL, created_at TIMESTAMP DEFAULT"
                        + " CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON"
                        + " UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (skill_name, resource_path))"
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

    @Override
    public AgentSkill getSkill(String name) {
        validateSkillName(name);

        String selectSkillSql =
                "SELECT name, description, skill_content, source FROM "
                        + getFullTableName(skillsTableName)
                        + " WHERE name = ?";

        String selectResourcesSql =
                "SELECT resource_path, resource_content FROM "
                        + getFullTableName(resourcesTableName)
                        + " WHERE skill_name = ?";

        try (Connection conn = dataSource.getConnection()) {
            // Load skill metadata
            String description;
            String skillContent;
            String source;

            try (PreparedStatement stmt = conn.prepareStatement(selectSkillSql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Skill not found: " + name);
                    }
                    description = rs.getString("description");
                    skillContent = rs.getString("skill_content");
                    source = rs.getString("source");
                }
            }

            // Load skill resources
            Map<String, String> resources = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(selectResourcesSql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString("resource_path");
                        String content = rs.getString("resource_content");
                        resources.put(path, content);
                    }
                }
            }

            return new AgentSkill(name, description, skillContent, resources, source);

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
        List<String> skillNames = getAllSkillNames();
        List<AgentSkill> skills = new ArrayList<>();

        for (String name : skillNames) {
            try {
                skills.add(getSkill(name));
            } catch (Exception e) {
                logger.warn("Failed to load skill '{}': {}", name, e.getMessage(), e);
                // Continue processing other skills
            }
        }

        return skills;
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
            // Use transaction for atomic operations
            conn.setAutoCommit(false);

            try {
                for (AgentSkill skill : skills) {
                    String skillName = skill.getName();
                    validateSkillName(skillName);

                    // Check if skill exists
                    boolean exists = skillExistsInternal(conn, skillName);

                    if (exists && !force) {
                        logger.info("Skill already exists and force=false: {}", skillName);
                        conn.rollback();
                        return false;
                    }

                    if (exists) {
                        // Delete existing skill and its resources
                        deleteSkillInternal(conn, skillName);
                        logger.debug("Deleted existing skill for overwrite: {}", skillName);
                    }

                    // Insert skill
                    insertSkill(conn, skill);

                    // Insert resources
                    insertResources(conn, skillName, skill.getResources());

                    logger.info("Successfully saved skill: {}", skillName);
                }

                conn.commit();
                return true;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * Insert a skill into the database.
     *
     * @param conn  the database connection
     * @param skill the skill to insert
     * @throws SQLException if insertion fails
     */
    private void insertSkill(Connection conn, AgentSkill skill) throws SQLException {
        String insertSql =
                "INSERT INTO "
                        + getFullTableName(skillsTableName)
                        + " (name, description, skill_content, source) VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, skill.getName());
            stmt.setString(2, skill.getDescription());
            stmt.setString(3, skill.getSkillContent());
            stmt.setString(4, skill.getSource());
            stmt.executeUpdate();
        }
    }

    /**
     * Insert resources for a skill.
     *
     * <p>
     * This method inserts each resource one by one instead of using batch
     * processing
     * to ensure better compatibility and error handling across different database
     * drivers.
     *
     * @param conn      the database connection
     * @param skillName the skill name
     * @param resources the resources to insert
     * @throws SQLException if insertion fails
     */
    private void insertResources(Connection conn, String skillName, Map<String, String> resources)
            throws SQLException {
        if (resources == null || resources.isEmpty()) {
            logger.debug("No resources to insert for skill: {}", skillName);
            return;
        }

        String insertSql =
                "INSERT INTO "
                        + getFullTableName(resourcesTableName)
                        + " (skill_name, resource_path, resource_content) VALUES (?, ?, ?)";

        int insertedCount = 0;
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();

            validateResourcePath(path);

            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setString(1, skillName);
                stmt.setString(2, path);
                stmt.setString(3, content);
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    insertedCount++;
                    logger.debug("Inserted resource '{}' for skill '{}'", path, skillName);
                } else {
                    logger.warn("Failed to insert resource '{}' for skill '{}'", path, skillName);
                }
            }
        }

        logger.debug(
                "Inserted {} resources for skill '{}' (total: {})",
                insertedCount,
                skillName,
                resources.size());

        if (insertedCount != resources.size()) {
            throw new SQLException(
                    "Failed to insert all resources for skill '"
                            + skillName
                            + "'. Expected: "
                            + resources.size()
                            + ", Inserted: "
                            + insertedCount);
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
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * Delete a skill and its resources from the database.
     *
     * @param conn      the database connection
     * @param skillName the skill name to delete
     * @throws SQLException if deletion fails
     */
    private void deleteSkillInternal(Connection conn, String skillName) throws SQLException {
        // Delete resources first (if foreign key doesn't have CASCADE)
        String deleteResourcesSql =
                "DELETE FROM " + getFullTableName(resourcesTableName) + " WHERE skill_name = ?";

        try (PreparedStatement stmt = conn.prepareStatement(deleteResourcesSql)) {
            stmt.setString(1, skillName);
            stmt.executeUpdate();
        }

        // Delete skill
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
     * Clear all skills from the database (for testing or cleanup).
     *
     * @return the number of skills deleted
     */
    public int clearAllSkills() {
        String deleteResourcesSql = "DELETE FROM " + getFullTableName(resourcesTableName);
        String deleteSkillsSql = "DELETE FROM " + getFullTableName(skillsTableName);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete all resources first
                try (PreparedStatement stmt = conn.prepareStatement(deleteResourcesSql)) {
                    stmt.executeUpdate();
                }

                // Delete all skills
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
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear skills", e);
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
     * Creates a new Builder instance for constructing MysqlSkillRepository.
     *
     * <p>
     * The builder pattern provides a fluent API for configuring the repository
     * with custom settings.
     *
     * <p>
     * Example usage:
     *
     * <pre>{@code
     * MysqlSkillRepository repo = MysqlSkillRepository.builder()
     *         .dataSource(dataSource)
     *         .databaseName("my_database")
     *         .skillsTableName("my_skills")
     *         .createIfNotExist(true)
     *         .writeable(true)
     *         .build();
     * }</pre>
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing MysqlSkillRepository instances.
     *
     * <p>
     * This builder provides a fluent API for configuring all aspects of the
     * repository,
     * including database connection, table names, and behavior options.
     *
     * <p>
     * Required fields:
     * <ul>
     * <li>{@code dataSource} - Must be set before calling build()
     * </ul>
     *
     * <p>
     * Optional fields with defaults:
     * <ul>
     * <li>{@code databaseName} - defaults to "agentscope"
     * <li>{@code skillsTableName} - defaults to "agentscope_skills"
     * <li>{@code resourcesTableName} - defaults to "agentscope_skill_resources"
     * <li>{@code createIfNotExist} - defaults to false
     * <li>{@code writeable} - defaults to true
     * </ul>
     *
     * <p>
     * Example:
     *
     * <pre>{@code
     * MysqlSkillRepository repo = MysqlSkillRepository.builder()
     *         .dataSource(dataSource)
     *         .databaseName("custom_db")
     *         .skillsTableName("custom_skills")
     *         .resourcesTableName("custom_resources")
     *         .createIfNotExist(true)
     *         .writeable(true)
     *         .build();
     * }</pre>
     */
    public static class Builder {

        private DataSource dataSource;
        private String databaseName = DEFAULT_DATABASE_NAME;
        private String skillsTableName = DEFAULT_SKILLS_TABLE_NAME;
        private String resourcesTableName = DEFAULT_RESOURCES_TABLE_NAME;
        private boolean createIfNotExist = false;
        private boolean writeable = true;

        /**
         * Creates a new Builder instance with default values.
         */
        public Builder() {
            // Default constructor with default values
        }

        /**
         * Sets the DataSource for database connections.
         *
         * <p>
         * This is a required field and must be set before calling build().
         *
         * @param dataSource the DataSource to use (must not be null)
         * @return this builder for method chaining
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Sets the database name for storing skills.
         *
         * <p>
         * If not set, defaults to "agentscope".
         *
         * @param databaseName the database name (uses default if null or empty)
         * @return this builder for method chaining
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * Sets the table name for storing skills.
         *
         * <p>
         * If not set, defaults to "agentscope_skills".
         *
         * @param skillsTableName the skills table name (uses default if null or empty)
         * @return this builder for method chaining
         */
        public Builder skillsTableName(String skillsTableName) {
            this.skillsTableName = skillsTableName;
            return this;
        }

        /**
         * Sets the table name for storing skill resources.
         *
         * <p>
         * If not set, defaults to "agentscope_skill_resources".
         *
         * @param resourcesTableName the resources table name (uses default if null or
         *                           empty)
         * @return this builder for method chaining
         */
        public Builder resourcesTableName(String resourcesTableName) {
            this.resourcesTableName = resourcesTableName;
            return this;
        }

        /**
         * Sets whether to automatically create the database and tables if they don't
         * exist.
         *
         * <p>
         * If set to true, the database and tables will be created during construction
         * if they don't already exist. If false (default), an exception will be thrown
         * if the database or tables are missing.
         *
         * @param createIfNotExist true to auto-create database and tables
         * @return this builder for method chaining
         */
        public Builder createIfNotExist(boolean createIfNotExist) {
            this.createIfNotExist = createIfNotExist;
            return this;
        }

        /**
         * Sets whether the repository supports write operations.
         *
         * <p>
         * If set to false, save and delete operations will be rejected.
         * Defaults to true.
         *
         * @param writeable true to allow write operations
         * @return this builder for method chaining
         */
        public Builder writeable(boolean writeable) {
            this.writeable = writeable;
            return this;
        }

        /**
         * Builds and returns a new MysqlSkillRepository instance.
         *
         * <p>
         * This method validates that all required fields are set and creates
         * the repository with the configured options.
         *
         * @return a new MysqlSkillRepository instance
         * @throws IllegalArgumentException if dataSource is null
         * @throws IllegalStateException    if createIfNotExist is false and
         *                                  database/tables don't exist
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
