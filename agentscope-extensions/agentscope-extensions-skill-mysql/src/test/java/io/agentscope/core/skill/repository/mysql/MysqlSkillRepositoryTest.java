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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for MysqlSkillRepository.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior of
 * MysqlSkillRepository without requiring an actual MySQL database.
 *
 * <p>Test categories:
 * <ul>
 *   <li>Constructor tests - validate initialization and parameter handling
 *   <li>CRUD operation tests - verify skill save, get, delete operations
 *   <li>SQL injection prevention tests - ensure security validations work
 *   <li>Repository info tests - verify metadata reporting
 * </ul>
 */
@DisplayName("MysqlSkillRepository Tests")
public class MysqlSkillRepositoryTest {

    @Mock private DataSource mockDataSource;

    @Mock private Connection mockConnection;

    @Mock private PreparedStatement mockStatement;

    @Mock private ResultSet mockResultSet;

    private AutoCloseable mockitoCloseable;

    @BeforeEach
    void setUp() throws SQLException {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    // ==================== Constructor Tests ====================

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should throw exception when DataSource is null")
        void testConstructorWithNullDataSource() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MysqlSkillRepository(null),
                    "DataSource cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when DataSource is null with createIfNotExist flag")
        void testConstructorWithNullDataSourceAndCreateIfNotExist() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MysqlSkillRepository(null, true),
                    "DataSource cannot be null");
        }

        @Test
        @DisplayName("Should create repository with createIfNotExist=true")
        void testConstructorWithCreateIfNotExistTrue() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);

            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertEquals(mockDataSource, repo.getDataSource());
            assertTrue(repo.isWriteable());
        }

        @Test
        @DisplayName(
                "Should throw exception when database does not exist and createIfNotExist=false")
        void testConstructorWithDatabaseNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new MysqlSkillRepository(mockDataSource, false),
                    "Database does not exist");
        }

        @Test
        @DisplayName("Should throw exception when skills table does not exist")
        void testConstructorWithSkillsTableNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // First call: database exists, second call: skills table not found
            when(mockResultSet.next()).thenReturn(true, false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new MysqlSkillRepository(mockDataSource, false),
                    "Table does not exist");
        }

        @Test
        @DisplayName("Should throw exception when resources table does not exist")
        void testConstructorWithResourcesTableNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // database exists, skills table exists, resources table not found
            when(mockResultSet.next()).thenReturn(true, true, false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new MysqlSkillRepository(mockDataSource, false),
                    "Table does not exist");
        }

        @Test
        @DisplayName("Should create repository when all tables exist")
        void testConstructorWithAllTablesExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // database exists, skills table exists, resources table exists
            when(mockResultSet.next()).thenReturn(true, true, true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, false);

            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
        }

        @Test
        @DisplayName("Should create repository with custom names")
        void testConstructorWithCustomNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource,
                            "custom_db",
                            "custom_skills",
                            "custom_resources",
                            true,
                            true);

            assertEquals("custom_db", repo.getDatabaseName());
            assertEquals("custom_skills", repo.getSkillsTableName());
            assertEquals("custom_resources", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should use default names when null provided")
        void testConstructorWithNullNamesUsesDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(mockDataSource, null, null, null, true, true);

            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should use default names when empty string provided")
        void testConstructorWithEmptyNamesUsesDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(mockDataSource, "  ", "  ", "  ", true, true);

            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
        }
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Nested
    @DisplayName("SQL Injection Prevention Tests")
    class SqlInjectionPreventionTests {

        @Test
        @DisplayName("Should reject database name with semicolon")
        void testRejectsDatabaseNameWithSemicolon() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource,
                                    "db; DROP DATABASE mysql; --",
                                    "skills",
                                    "resources",
                                    true,
                                    true),
                    "Database name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject table name with semicolon")
        void testRejectsTableNameWithSemicolon() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource,
                                    "valid_db",
                                    "table; DROP TABLE users; --",
                                    "resources",
                                    true,
                                    true),
                    "Table name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject database name with space")
        void testRejectsDatabaseNameWithSpace() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource, "db name", "skills", "resources", true, true),
                    "Database name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject table name with space")
        void testRejectsTableNameWithSpace() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource,
                                    "valid_db",
                                    "table name",
                                    "resources",
                                    true,
                                    true),
                    "Table name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject database name starting with number")
        void testRejectsDatabaseNameStartingWithNumber() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource, "123db", "skills", "resources", true, true),
                    "Database name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject database name exceeding max length")
        void testRejectsDatabaseNameExceedingMaxLength() {
            String longName = "a".repeat(65);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new MysqlSkillRepository(
                                    mockDataSource, longName, "skills", "resources", true, true),
                    "Database name cannot exceed 64 characters");
        }

        @Test
        @DisplayName("Should accept valid identifiers")
        void testAcceptsValidIdentifiers() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource,
                            "my_database_123",
                            "my_skills_456",
                            "my_resources_789",
                            true,
                            true);

            assertEquals("my_database_123", repo.getDatabaseName());
            assertEquals("my_skills_456", repo.getSkillsTableName());
            assertEquals("my_resources_789", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should accept names starting with underscore")
        void testAcceptsNamesStartingWithUnderscore() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource,
                            "_private_db",
                            "_private_skills",
                            "_private_resources",
                            true,
                            true);

            assertEquals("_private_db", repo.getDatabaseName());
            assertEquals("_private_skills", repo.getSkillsTableName());
        }

        @Test
        @DisplayName("Should accept max length names")
        void testAcceptsMaxLengthNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            String maxLengthName = "a".repeat(64);
            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource,
                            maxLengthName,
                            maxLengthName,
                            maxLengthName,
                            true,
                            true);

            assertEquals(maxLengthName, repo.getDatabaseName());
        }
    }

    // ==================== Skill Name Validation Tests ====================

    @Nested
    @DisplayName("Skill Name Validation Tests")
    class SkillNameValidationTests {

        private MysqlSkillRepository repo;

        @BeforeEach
        void setUp() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            repo = new MysqlSkillRepository(mockDataSource, true);
        }

        @Test
        @DisplayName("Should reject null skill name in getSkill")
        void testGetSkillWithNullName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill(null),
                    "Skill name cannot be null or empty");
        }

        @Test
        @DisplayName("Should reject empty skill name in getSkill")
        void testGetSkillWithEmptyName() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill(""),
                    "Skill name cannot be null or empty");
        }

        @Test
        @DisplayName("Should reject skill name with path traversal")
        void testGetSkillWithPathTraversal() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill("../etc/passwd"),
                    "Skill name cannot contain path separators");
        }

        @Test
        @DisplayName("Should reject skill name exceeding max length")
        void testGetSkillWithExceedingMaxLength() {
            String longName = "a".repeat(256);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill(longName),
                    "Skill name cannot exceed 255 characters");
        }
    }

    // ==================== CRUD Operation Tests ====================

    @Nested
    @DisplayName("CRUD Operation Tests")
    class CrudOperationTests {

        private MysqlSkillRepository repo;

        @BeforeEach
        void setUp() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            repo = new MysqlSkillRepository(mockDataSource, true);
        }

        @Test
        @DisplayName("Should get skill successfully")
        void testGetSkill() throws SQLException {
            // Setup mock for skill query
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // First query: skill exists, second query: no resources
            when(mockResultSet.next()).thenReturn(true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("mysql_test");

            AgentSkill skill = repo.getSkill("test-skill");

            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
            assertEquals("Test content", skill.getSkillContent());
            assertEquals("mysql_test", skill.getSource());
        }

        @Test
        @DisplayName("Should throw exception when skill not found")
        void testGetSkillNotFound() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill("non-existent"),
                    "Skill not found");
        }

        @Test
        @DisplayName("Should get all skill names")
        void testGetAllSkillNames() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true, true, false);
            when(mockResultSet.getString("name")).thenReturn("skill1", "skill2");

            List<String> names = repo.getAllSkillNames();

            assertEquals(2, names.size());
            assertEquals("skill1", names.get(0));
            assertEquals("skill2", names.get(1));
        }

        @Test
        @DisplayName("Should return empty list when no skills exist")
        void testGetAllSkillNamesEmpty() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            List<String> names = repo.getAllSkillNames();

            assertTrue(names.isEmpty());
        }

        @Test
        @DisplayName("Should save skill successfully")
        void testSaveSkill() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false); // skill doesn't exist

            AgentSkill skill =
                    new AgentSkill("new-skill", "Description", "Content", Map.of(), "test");

            boolean saved = repo.save(List.of(skill), false);

            assertTrue(saved);
            verify(mockStatement, atLeast(1)).executeUpdate();
        }

        @Test
        @DisplayName("Should save skill with resources")
        void testSaveSkillWithResources() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false); // skill doesn't exist
            when(mockStatement.executeBatch()).thenReturn(new int[] {1, 1});

            Map<String, String> resources =
                    Map.of(
                            "file1.txt", "content1",
                            "file2.txt", "content2");

            AgentSkill skill =
                    new AgentSkill("skill-with-resources", "Desc", "Content", resources, "test");

            boolean saved = repo.save(List.of(skill), false);

            assertTrue(saved);
        }

        @Test
        @DisplayName("Should not save when skill exists and force=false")
        void testSaveSkillExistsNoForce() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true); // skill exists

            AgentSkill skill =
                    new AgentSkill("existing-skill", "Description", "Content", Map.of(), "test");

            boolean saved = repo.save(List.of(skill), false);

            assertFalse(saved);
        }

        @Test
        @DisplayName("Should overwrite skill when force=true")
        void testSaveSkillWithForce() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true, false); // skill exists, then deleted

            AgentSkill skill =
                    new AgentSkill(
                            "existing-skill", "New Description", "New Content", Map.of(), "test");

            boolean saved = repo.save(List.of(skill), true);

            assertTrue(saved);
        }

        @Test
        @DisplayName("Should return false when saving null list")
        void testSaveNullList() {
            boolean saved = repo.save(null, false);
            assertFalse(saved);
        }

        @Test
        @DisplayName("Should return false when saving empty list")
        void testSaveEmptyList() {
            boolean saved = repo.save(List.of(), false);
            assertFalse(saved);
        }

        @Test
        @DisplayName("Should delete skill successfully")
        void testDeleteSkill() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true); // skill exists

            boolean deleted = repo.delete("test-skill");

            assertTrue(deleted);
        }

        @Test
        @DisplayName("Should return false when deleting non-existent skill")
        void testDeleteNonExistentSkill() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false); // skill doesn't exist

            boolean deleted = repo.delete("non-existent");

            assertFalse(deleted);
        }

        @Test
        @DisplayName("Should check skill exists correctly")
        void testSkillExists() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true);

            assertTrue(repo.skillExists("existing-skill"));
        }

        @Test
        @DisplayName("Should return false for non-existent skill")
        void testSkillNotExists() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            assertFalse(repo.skillExists("non-existent"));
        }

        @Test
        @DisplayName("Should return false for null skill name in exists")
        void testSkillExistsWithNullName() {
            assertFalse(repo.skillExists(null));
        }

        @Test
        @DisplayName("Should return false for empty skill name in exists")
        void testSkillExistsWithEmptyName() {
            assertFalse(repo.skillExists(""));
        }
    }

    // ==================== Read-Only Mode Tests ====================

    @Nested
    @DisplayName("Read-Only Mode Tests")
    class ReadOnlyModeTests {

        @Test
        @DisplayName("Should not save when repository is read-only")
        void testSaveWhenReadOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource, "db", "skills", "resources", true, false);

            AgentSkill skill = new AgentSkill("test", "desc", "content", Map.of(), "test");
            boolean saved = repo.save(List.of(skill), false);

            assertFalse(saved);
        }

        @Test
        @DisplayName("Should not delete when repository is read-only")
        void testDeleteWhenReadOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource, "db", "skills", "resources", true, false);

            boolean deleted = repo.delete("test-skill");

            assertFalse(deleted);
        }

        @Test
        @DisplayName("Should toggle writeable flag")
        void testSetWriteable() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);

            assertTrue(repo.isWriteable());

            repo.setWriteable(false);
            assertFalse(repo.isWriteable());

            repo.setWriteable(true);
            assertTrue(repo.isWriteable());
        }
    }

    // ==================== Repository Info Tests ====================

    @Nested
    @DisplayName("Repository Info Tests")
    class RepositoryInfoTests {

        @Test
        @DisplayName("Should return correct repository info")
        void testGetRepositoryInfo() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);

            AgentSkillRepositoryInfo info = repo.getRepositoryInfo();

            assertNotNull(info);
            assertEquals("mysql", info.getType());
            assertEquals("agentscope.agentscope_skills", info.getLocation());
            assertTrue(info.isWritable());
        }

        @Test
        @DisplayName("Should return correct source")
        void testGetSource() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);

            String source = repo.getSource();

            assertEquals("mysql_agentscope_agentscope_skills", source);
        }

        @Test
        @DisplayName("Should return correct source with custom names")
        void testGetSourceWithCustomNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    new MysqlSkillRepository(
                            mockDataSource,
                            "custom_db",
                            "custom_skills",
                            "custom_resources",
                            true,
                            true);

            String source = repo.getSource();

            assertEquals("mysql_custom_db_custom_skills", source);
        }
    }

    // ==================== Close and Cleanup Tests ====================

    @Nested
    @DisplayName("Close and Cleanup Tests")
    class CloseAndCleanupTests {

        @Test
        @DisplayName("Should close without error")
        void testClose() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);
            repo.close();

            // Should not throw exception
            assertEquals(mockDataSource, repo.getDataSource());
        }

        @Test
        @DisplayName("Should clear all skills")
        void testClearAllSkills() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(5);

            MysqlSkillRepository repo = new MysqlSkillRepository(mockDataSource, true);
            int deleted = repo.clearAllSkills();

            assertEquals(5, deleted);
        }
    }

    // ==================== Builder Tests ====================

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create repository with builder using defaults")
        void testBuilderWithDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    MysqlSkillRepository.builder()
                            .dataSource(mockDataSource)
                            .createIfNotExist(true)
                            .build();

            assertNotNull(repo);
            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertEquals(mockDataSource, repo.getDataSource());
            assertTrue(repo.isWriteable());
        }

        @Test
        @DisplayName("Should create repository with builder using custom values")
        void testBuilderWithCustomValues() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    MysqlSkillRepository.builder()
                            .dataSource(mockDataSource)
                            .databaseName("custom_db")
                            .skillsTableName("custom_skills")
                            .resourcesTableName("custom_resources")
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            assertNotNull(repo);
            assertEquals("custom_db", repo.getDatabaseName());
            assertEquals("custom_skills", repo.getSkillsTableName());
            assertEquals("custom_resources", repo.getResourcesTableName());
            assertFalse(repo.isWriteable());
        }

        @Test
        @DisplayName("Should throw exception when dataSource is null in builder")
        void testBuilderWithNullDataSource() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            MysqlSkillRepository.builder()
                                    .dataSource(null)
                                    .createIfNotExist(true)
                                    .build(),
                    "DataSource cannot be null");
        }

        @Test
        @DisplayName("Should use defaults when null values provided to builder")
        void testBuilderWithNullValues() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    MysqlSkillRepository.builder()
                            .dataSource(mockDataSource)
                            .databaseName(null)
                            .skillsTableName(null)
                            .resourcesTableName(null)
                            .createIfNotExist(true)
                            .build();

            assertEquals("agentscope", repo.getDatabaseName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should create read-only repository with builder")
        void testBuilderReadOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            MysqlSkillRepository repo =
                    MysqlSkillRepository.builder()
                            .dataSource(mockDataSource)
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            assertFalse(repo.isWriteable());
        }

        @Test
        @DisplayName("Should support method chaining in builder")
        void testBuilderMethodChaining() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            // Verify that all methods return the same builder instance for chaining
            MysqlSkillRepository.Builder builder = MysqlSkillRepository.builder();
            MysqlSkillRepository.Builder returned =
                    builder.dataSource(mockDataSource)
                            .databaseName("db")
                            .skillsTableName("skills")
                            .resourcesTableName("resources")
                            .createIfNotExist(true)
                            .writeable(true);

            // All calls should return the same builder
            assertEquals(builder, returned.dataSource(mockDataSource));
        }

        @Test
        @DisplayName("Should reject invalid identifiers in builder")
        void testBuilderWithInvalidIdentifiers() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            MysqlSkillRepository.builder()
                                    .dataSource(mockDataSource)
                                    .databaseName("db; DROP TABLE users;")
                                    .createIfNotExist(true)
                                    .build(),
                    "Database name contains invalid characters");
        }

        @Test
        @DisplayName(
                "Should throw exception when database does not exist and createIfNotExist=false")
        void testBuilderWithoutCreateAndDatabaseNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            MysqlSkillRepository.builder()
                                    .dataSource(mockDataSource)
                                    .createIfNotExist(false)
                                    .build(),
                    "Database does not exist");
        }
    }
}
