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
package io.agentscope.core.skill.repository.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
 * Unit tests for PostgresSkillRepository.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior of
 * PostgresSkillRepository without requiring an actual PostgreSQL database.
 *
 * <p>Test categories:
 * <ul>
 * <li>Constructor tests - validate initialization, schema creation, and compatibility detection
 * <li>CRUD operation tests - verify legacy fallback and full metadata round-trip behavior
 * <li>SQL injection prevention tests - ensure security validations work
 * <li>Repository info tests - verify metadata reporting
 * </ul>
 */
@DisplayName("PostgresSkillRepository Tests")
public class PostgresSkillRepositoryTest {

    @Mock private DataSource mockDataSource;

    @Mock private Connection mockConnection;

    @Mock private PreparedStatement mockStatement;

    @Mock private ResultSet mockResultSet;

    @Mock private ResultSet mockGeneratedKeysResultSet;

    private AutoCloseable mockitoCloseable;

    @BeforeEach
    void setUp() throws SQLException {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
        // Also mock prepareStatement with RETURN_GENERATED_KEYS for insertSkill
        when(mockConnection.prepareStatement(anyString(), anyInt())).thenReturn(mockStatement);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);
        // Mock getGeneratedKeys for insertSkill
        when(mockStatement.getGeneratedKeys()).thenReturn(mockGeneratedKeysResultSet);
        when(mockGeneratedKeysResultSet.next()).thenReturn(true);
        when(mockGeneratedKeysResultSet.getLong(1)).thenReturn(1L);
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
                    () -> new PostgresSkillRepository(null, true, true),
                    "DataSource cannot be null");
        }

        @Test
        @DisplayName("Should create repository with createIfNotExist=true")
        void testConstructorWithCreateIfNotExistTrue() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertEquals(mockDataSource, repo.getDataSource());
            assertTrue(repo.isWriteable());
            assertTrue(repo.isMetadataJsonColumnSupported() == false);
            verify(mockConnection, atLeast(1)).prepareStatement(anyString());
        }

        @Test
        @DisplayName("Should create metadata_json column for new tables")
        void testConstructorCreatesMetadataJsonColumn() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            new PostgresSkillRepository(mockDataSource, true, true);

            verify(mockConnection)
                    .prepareStatement(
                            org.mockito.ArgumentMatchers.contains("metadata_json TEXT NULL"));
        }

        @Test
        @DisplayName("Should throw exception when schema creation fails")
        void testConstructorWithSchemaCreationFailure() throws SQLException {
            when(mockStatement.execute()).thenThrow(new SQLException("create schema failed"));

            RuntimeException exception =
                    assertThrows(
                            RuntimeException.class,
                            () -> new PostgresSkillRepository(mockDataSource, true, true));

            assertTrue(exception.getMessage().contains("Failed to create schema"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should throw exception when table creation fails")
        void testConstructorWithTableCreationFailure() throws SQLException {
            when(mockStatement.execute())
                    .thenReturn(true)
                    .thenThrow(new SQLException("create table failed"));

            RuntimeException exception =
                    assertThrows(
                            RuntimeException.class,
                            () -> new PostgresSkillRepository(mockDataSource, true, true));

            assertTrue(exception.getMessage().contains("Failed to create tables"));
            verify(mockConnection, atLeast(1)).rollback();
        }

        @Test
        @DisplayName("Should preserve rollback exception when schema creation rollback fails")
        void testConstructorWithRollbackFailureDuringSchemaCreation() throws SQLException {
            SQLException createException = new SQLException("create schema failed");
            SQLException rollbackException = new SQLException("rollback failed");
            when(mockStatement.execute()).thenThrow(createException);
            doThrow(rollbackException).when(mockConnection).rollback();

            RuntimeException exception =
                    assertThrows(
                            RuntimeException.class,
                            () -> new PostgresSkillRepository(mockDataSource, true, true));

            assertTrue(exception.getMessage().contains("Failed to create schema"));
            assertEquals(rollbackException, exception.getCause().getSuppressed()[0]);
        }

        @Test
        @DisplayName("Should throw exception when schema existence check fails")
        void testConstructorWithSchemaCheckFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenThrow(new SQLException("schema check failed"));

            RuntimeException exception =
                    assertThrows(
                            RuntimeException.class,
                            () -> new PostgresSkillRepository(mockDataSource, false, true));

            assertTrue(exception.getMessage().contains("Failed to check schema existence"));
        }

        @Test
        @DisplayName("Should throw exception when table existence check fails")
        void testConstructorWithTableCheckFailure() throws SQLException {
            when(mockStatement.executeQuery())
                    .thenReturn(mockResultSet)
                    .thenThrow(new SQLException("table check failed"));
            when(mockResultSet.next()).thenReturn(true);

            RuntimeException exception =
                    assertThrows(
                            RuntimeException.class,
                            () -> new PostgresSkillRepository(mockDataSource, false, true));

            assertTrue(exception.getMessage().contains("Failed to check table existence"));
        }

        @Test
        @DisplayName("Should fall back when metadata_json detection fails")
        void testConstructorWithMetadataDetectionFailure() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeQuery()).thenThrow(new SQLException("metadata check failed"));

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            assertFalse(repo.isMetadataJsonColumnSupported());
        }

        @Test
        @DisplayName("Should keep constructing when restoring auto-commit fails")
        void testConstructorWithAutoCommitRestoreFailure() throws SQLException {
            when(mockConnection.getAutoCommit())
                    .thenReturn(true)
                    .thenThrow(new SQLException("restore failed"))
                    .thenReturn(true)
                    .thenReturn(true);
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            assertNotNull(repo);
        }

        @Test
        @DisplayName("Should create repository with writeable=false")
        void testConstructorWithWriteableFalse() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, false);

            assertEquals("agentscope", repo.getSchemaName());
            assertFalse(repo.isWriteable());
        }

        @Test
        @DisplayName("Should throw exception when schema does not exist and createIfNotExist=false")
        void testConstructorWithSchemaNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new PostgresSkillRepository(mockDataSource, false, true),
                    "Schema does not exist");
        }

        @Test
        @DisplayName("Should throw exception when skills table does not exist")
        void testConstructorWithSkillsTableNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // First call: schema exists, second call: skills table not found
            when(mockResultSet.next()).thenReturn(true, false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new PostgresSkillRepository(mockDataSource, false, true),
                    "Table does not exist");
        }

        @Test
        @DisplayName("Should throw exception when resources table does not exist")
        void testConstructorWithResourcesTableNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // schema exists, skills table exists, resources table not found
            when(mockResultSet.next()).thenReturn(true, true, false);

            assertThrows(
                    IllegalStateException.class,
                    () -> new PostgresSkillRepository(mockDataSource, false, true),
                    "Table does not exist");
        }

        @Test
        @DisplayName("Should create repository when all tables exist")
        void testConstructorWithAllTablesExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // schema exists, skills table exists, resources table exists
            when(mockResultSet.next()).thenReturn(true, true, true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, false, true);

            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
        }

        @Test
        @DisplayName("Should create repository with custom names using Builder")
        void testConstructorWithCustomNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("custom_db")
                            .skillsTableName("custom_skills")
                            .resourcesTableName("custom_resources")
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals("custom_db", repo.getSchemaName());
            assertEquals("custom_skills", repo.getSkillsTableName());
            assertEquals("custom_resources", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should use default names when null provided via Builder")
        void testConstructorWithNullNamesUsesDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName(null)
                            .skillsTableName(null)
                            .resourcesTableName(null)
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should use default names when empty string provided via Builder")
        void testConstructorWithEmptyNamesUsesDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("  ")
                            .skillsTableName("  ")
                            .resourcesTableName("  ")
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
        }
    }

    // ==================== Builder Tests ====================

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should throw exception when builder DataSource is null")
        void testBuilderWithNullDataSource() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PostgresSkillRepository.builder(null),
                    "DataSource cannot be null");
        }

        @Test
        @DisplayName("Should create repository with Builder using defaults")
        void testBuilderWithDefaults() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = PostgresSkillRepository.builder(mockDataSource).build();

            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertTrue(repo.isWriteable());
            assertEquals(mockDataSource, repo.getDataSource());
        }

        @Test
        @DisplayName("Should create repository with Builder setting all options")
        void testBuilderWithAllOptions() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("my_db")
                            .skillsTableName("my_skills")
                            .resourcesTableName("my_resources")
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            assertEquals("my_db", repo.getSchemaName());
            assertEquals("my_skills", repo.getSkillsTableName());
            assertEquals("my_resources", repo.getResourcesTableName());
            assertFalse(repo.isWriteable());
        }

        @Test
        @DisplayName("Should support Builder method chaining")
        void testBuilderMethodChaining() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            // Test that all builder methods return the builder for chaining
            PostgresSkillRepository.Builder builder =
                    PostgresSkillRepository.builder(mockDataSource);

            // Each method should return the same builder instance
            PostgresSkillRepository.Builder result1 = builder.schemaName("db");
            PostgresSkillRepository.Builder result2 = result1.skillsTableName("skills");
            PostgresSkillRepository.Builder result3 = result2.resourcesTableName("resources");
            PostgresSkillRepository.Builder result4 = result3.createIfNotExist(true);
            PostgresSkillRepository.Builder result5 = result4.writeable(true);

            // All should be the same instance
            assertEquals(builder, result1);
            assertEquals(builder, result2);
            assertEquals(builder, result3);
            assertEquals(builder, result4);
            assertEquals(builder, result5);

            // Build should work after chaining
            PostgresSkillRepository repo = result5.build();
            assertNotNull(repo);
            assertEquals("db", repo.getSchemaName());
        }

        @Test
        @DisplayName("Should create repository with Builder using only schemaName")
        void testBuilderWithOnlySchemaName() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource).schemaName("custom_db").build();

            assertEquals("custom_db", repo.getSchemaName());
            // Should use defaults for other options
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertTrue(repo.isWriteable());
        }

        @Test
        @DisplayName("Should create repository with Builder using only writeable=false")
        void testBuilderWithOnlyWriteableFalse() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource).writeable(false).build();

            // Should use defaults for other options
            assertEquals("agentscope", repo.getSchemaName());
            assertEquals("agentscope_skills", repo.getSkillsTableName());
            assertEquals("agentscope_skill_resources", repo.getResourcesTableName());
            assertFalse(repo.isWriteable());
        }

        @Test
        @DisplayName("Should create repository with Builder using createIfNotExist=false")
        void testBuilderWithCreateIfNotExistFalse() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            // schema exists, skills table exists, resources table exists
            when(mockResultSet.next()).thenReturn(true, true, true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource).createIfNotExist(false).build();

            assertNotNull(repo);
            assertEquals("agentscope", repo.getSchemaName());
        }

        @Test
        @DisplayName("Should throw exception when createIfNotExist=false and schema not exist")
        void testBuilderWithCreateIfNotExistFalseAndSchemaNotExist() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false); // schema doesn't exist

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .createIfNotExist(false)
                                    .build(),
                    "Schema does not exist");
        }
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Nested
    @DisplayName("SQL Injection Prevention Tests")
    class SqlInjectionPreventionTests {

        @Test
        @DisplayName("Should reject schema name with semicolon")
        void testRejectsSchemaNameWithSemicolon() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName("db; DROP DATABASE postgresql; --")
                                    .skillsTableName("skills")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Schema name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject table name with semicolon")
        void testRejectsTableNameWithSemicolon() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName("valid_db")
                                    .skillsTableName("table; DROP TABLE users; --")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Table name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject schema name with space")
        void testRejectsSchemaNameWithSpace() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName("db name")
                                    .skillsTableName("skills")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Schema name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject table name with space")
        void testRejectsTableNameWithSpace() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName("valid_db")
                                    .skillsTableName("table name")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Table name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject schema name starting with number")
        void testRejectsSchemaNameStartingWithNumber() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName("123db")
                                    .skillsTableName("skills")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Schema name contains invalid characters");
        }

        @Test
        @DisplayName("Should reject schema name exceeding max length")
        void testRejectsSchemaNameExceedingMaxLength() {
            String longName = "a".repeat(64);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PostgresSkillRepository.builder(mockDataSource)
                                    .schemaName(longName)
                                    .skillsTableName("skills")
                                    .resourcesTableName("resources")
                                    .build(),
                    "Schema name cannot exceed 63 characters");
        }

        @Test
        @DisplayName("Should accept valid identifiers")
        void testAcceptsValidIdentifiers() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("my_database_123")
                            .skillsTableName("my_skills_456")
                            .resourcesTableName("my_resources_789")
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals("my_database_123", repo.getSchemaName());
            assertEquals("my_skills_456", repo.getSkillsTableName());
            assertEquals("my_resources_789", repo.getResourcesTableName());
        }

        @Test
        @DisplayName("Should accept names starting with underscore")
        void testAcceptsNamesStartingWithUnderscore() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("_private_db")
                            .skillsTableName("_private_skills")
                            .resourcesTableName("_private_resources")
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals("_private_db", repo.getSchemaName());
            assertEquals("_private_skills", repo.getSkillsTableName());
        }

        @Test
        @DisplayName("Should accept max length names")
        void testAcceptsMaxLengthNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            String maxLengthName = "a".repeat(63);
            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName(maxLengthName)
                            .skillsTableName(maxLengthName)
                            .resourcesTableName(maxLengthName)
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            assertEquals(maxLengthName, repo.getSchemaName());
        }

        @Test
        @DisplayName("Should reject null and empty identifiers")
        void testRejectsNullAndEmptyIdentifiers() throws Exception {
            when(mockStatement.execute()).thenReturn(true);
            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);
            java.lang.reflect.Method validateIdentifier =
                    PostgresSkillRepository.class.getDeclaredMethod(
                            "validateIdentifier", String.class, String.class);
            validateIdentifier.setAccessible(true);

            java.lang.reflect.InvocationTargetException nullException =
                    assertThrows(
                            java.lang.reflect.InvocationTargetException.class,
                            () -> validateIdentifier.invoke(repo, null, "Identifier"));
            java.lang.reflect.InvocationTargetException emptyException =
                    assertThrows(
                            java.lang.reflect.InvocationTargetException.class,
                            () -> validateIdentifier.invoke(repo, "", "Identifier"));

            assertTrue(
                    nullException
                            .getCause()
                            .getMessage()
                            .contains("Identifier cannot be null or empty"));
            assertTrue(
                    emptyException
                            .getCause()
                            .getMessage()
                            .contains("Identifier cannot be null or empty"));
        }
    }

    // ==================== Skill Name Validation Tests ====================

    @Nested
    @DisplayName("Skill Name Validation Tests")
    class SkillNameValidationTests {

        private PostgresSkillRepository repo;

        @BeforeEach
        void setUp() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            repo = new PostgresSkillRepository(mockDataSource, true, true);
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
        @DisplayName("Should reject skill name with forward slash")
        void testGetSkillWithForwardSlash() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill("dir/skill"),
                    "Skill name cannot contain path separators");
        }

        @Test
        @DisplayName("Should reject skill name with backslash")
        void testGetSkillWithBackslash() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.getSkill("dir\\skill"),
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

        private PostgresSkillRepository repo;

        @BeforeEach
        void setUp() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            repo = new PostgresSkillRepository(mockDataSource, true, true);
        }

        @Test
        @DisplayName("Should get skill successfully")
        void testGetSkill() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");
            when(mockResultSet.getString("metadata_json"))
                    .thenReturn(
                            "{\"name\":\"test-skill\",\"description\":\"Test description\","
                                    + "\"homepage\":\"https://example.com\"}");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            AgentSkill skill = metadataRepo.getSkill("test-skill");

            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
            assertEquals("Test content", skill.getSkillContent());
            assertEquals("postgresql_test", skill.getSource());
            assertEquals("https://example.com", skill.getMetadataValue("homepage"));
        }

        @Test
        @DisplayName("Should get skill resources by skill id")
        void testGetSkillLoadsResources() throws SQLException {
            PreparedStatement skillStatement = mock(PreparedStatement.class);
            PreparedStatement resourceStatement = mock(PreparedStatement.class);
            ResultSet skillResultSet = mock(ResultSet.class);
            ResultSet resourceResultSet = mock(ResultSet.class);
            when(mockConnection.prepareStatement(anyString()))
                    .thenReturn(skillStatement)
                    .thenReturn(resourceStatement);
            when(skillStatement.executeQuery()).thenReturn(skillResultSet);
            when(resourceStatement.executeQuery()).thenReturn(resourceResultSet);
            when(skillResultSet.next()).thenReturn(true);
            when(skillResultSet.getLong("id")).thenReturn(7L);
            when(skillResultSet.getString("description")).thenReturn("Test description");
            when(skillResultSet.getString("skill_content")).thenReturn("Test content");
            when(skillResultSet.getString("source")).thenReturn("postgresql_test");
            when(resourceResultSet.next()).thenReturn(true, false);
            when(resourceResultSet.getString("resource_path")).thenReturn("readme.md");
            when(resourceResultSet.getString("resource_content")).thenReturn("hello");

            AgentSkill skill = repo.getSkill("test-skill");

            assertEquals("hello", skill.getResources().get("readme.md"));
            verify(resourceStatement).setLong(1, 7L);
        }

        @Test
        @DisplayName("Should fall back to core metadata when metadata_json column is absent")
        void testGetSkillLegacySchemaFallback() throws SQLException {
            when(mockResultSet.next()).thenReturn(false, true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");

            PostgresSkillRepository legacyRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);
            AgentSkill skill = legacyRepo.getSkill("test-skill");

            assertEquals(List.of("name", "description"), List.copyOf(skill.getMetadata().keySet()));
            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
        }

        @Test
        @DisplayName("Should fall back to core metadata when metadata_json is invalid")
        void testGetSkillInvalidMetadataJsonFallback() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");
            when(mockResultSet.getString("metadata_json")).thenReturn("{invalid-json");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);
            AgentSkill skill = metadataRepo.getSkill("test-skill");

            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
        }

        @Test
        @DisplayName("Should fall back when metadata_json parses to null")
        void testGetSkillNullMetadataJsonValueFallback() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");
            when(mockResultSet.getString("metadata_json")).thenReturn("null");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);
            AgentSkill skill = metadataRepo.getSkill("test-skill");

            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
        }

        @Test
        @DisplayName("Should ignore blank metadata_json value")
        void testGetSkillBlankMetadataJsonValueFallback() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, true, false);
            when(mockResultSet.getString("name")).thenReturn("test-skill");
            when(mockResultSet.getString("description")).thenReturn("Test description");
            when(mockResultSet.getString("skill_content")).thenReturn("Test content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");
            when(mockResultSet.getString("metadata_json")).thenReturn("  ");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);
            AgentSkill skill = metadataRepo.getSkill("test-skill");

            assertEquals("test-skill", skill.getName());
            assertEquals("Test description", skill.getDescription());
        }

        @Test
        @DisplayName("Should get all skills with metadata_json when column exists")
        void testGetAllSkillsWithMetadataJson() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, true, false, true, false, false);
            when(mockResultSet.getLong("id")).thenReturn(1L, 1L);
            when(mockResultSet.getString("name")).thenReturn("skill1");
            when(mockResultSet.getString("description")).thenReturn("Desc 1");
            when(mockResultSet.getString("skill_content")).thenReturn("Content 1");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");
            when(mockResultSet.getString("metadata_json"))
                    .thenReturn(
                            "{\"name\":\"skill1\",\"description\":\"Desc"
                                    + " 1\",\"homepage\":\"https://example.com/1\"}");
            when(mockResultSet.getString("resource_path")).thenReturn("readme.md");
            when(mockResultSet.getString("resource_content")).thenReturn("hello");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            List<AgentSkill> skills = metadataRepo.getAllSkills();

            assertEquals(1, skills.size());
            assertEquals("https://example.com/1", skills.get(0).getMetadataValue("homepage"));
            assertEquals("hello", skills.get(0).getResources().get("readme.md"));
        }

        @Test
        @DisplayName("Should ignore orphaned resources when loading all skills")
        void testGetAllSkillsWithOrphanedResource() throws SQLException {
            when(mockResultSet.next()).thenReturn(false, true, false);
            when(mockResultSet.getLong("id")).thenReturn(99L);
            when(mockResultSet.getString("resource_path")).thenReturn("orphan.txt");
            when(mockResultSet.getString("resource_content")).thenReturn("orphan");

            List<AgentSkill> skills = repo.getAllSkills();

            assertTrue(skills.isEmpty());
        }

        @Test
        @DisplayName("Should skip invalid skill record when loading all skills")
        void testGetAllSkillsSkipsInvalidRecord() throws SQLException {
            when(mockResultSet.next()).thenReturn(true, false, false);
            when(mockResultSet.getLong("id")).thenReturn(1L);
            when(mockResultSet.getString("name")).thenReturn(null);
            when(mockResultSet.getString("description")).thenReturn("Description");
            when(mockResultSet.getString("skill_content")).thenReturn("Content");
            when(mockResultSet.getString("source")).thenReturn("postgresql_test");

            List<AgentSkill> skills = repo.getAllSkills();

            assertTrue(skills.isEmpty());
        }

        @Test
        @DisplayName("Should throw exception when loading all skills fails")
        void testGetAllSkillsSqlFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenThrow(new SQLException("load all failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.getAllSkills());

            assertTrue(exception.getMessage().contains("Failed to load all skills"));
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
        @DisplayName("Should throw exception when loading skill fails")
        void testGetSkillSqlFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenThrow(new SQLException("load skill failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.getSkill("test-skill"));

            assertTrue(exception.getMessage().contains("Failed to load skill"));
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
        @DisplayName("Should throw exception when listing skill names fails")
        void testGetAllSkillNamesSqlFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenThrow(new SQLException("list failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.getAllSkillNames());

            assertTrue(exception.getMessage().contains("Failed to list skill names"));
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
            // Mock executeUpdate for skill insertion
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(false); // skill doesn't exist
            // Mock executeBatch for resource batch insertion
            when(mockStatement.executeBatch()).thenReturn(new int[] {1, 1});

            Map<String, String> resources =
                    Map.of(
                            "file1.txt", "content1",
                            "file2.txt", "content2");

            AgentSkill skill =
                    new AgentSkill("skill-with-resources", "Desc", "Content", resources, "test");

            boolean saved = repo.save(List.of(skill), false);

            assertTrue(saved);
            // Verify executeUpdate was called for skill insert
            verify(mockStatement, atLeast(1)).executeUpdate();
            // Verify executeBatch was called for resource inserts
            verify(mockStatement, atLeast(1)).executeBatch();
        }

        @Test
        @DisplayName("Should save skill when resources are null")
        void testSaveSkillWithNullResources() throws SQLException {
            AgentSkill skill = mock(AgentSkill.class);
            when(skill.getName()).thenReturn("null-resources");
            when(skill.getDescription()).thenReturn("Description");
            when(skill.getSkillContent()).thenReturn("Content");
            when(skill.getSource()).thenReturn("test");
            when(skill.getResources()).thenReturn(null);
            when(skill.getMetadata()).thenReturn(null);
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenReturn(1);

            assertTrue(repo.save(List.of(skill), false));
            verify(mockStatement, never()).executeBatch();
        }

        @Test
        @DisplayName("Should reject null resource path when saving")
        void testSaveSkillWithNullResourcePath() {
            Map<String, String> resources = new LinkedHashMap<>();
            resources.put(null, "content");
            AgentSkill skill =
                    new AgentSkill("skill-with-null-resource", "Desc", "Content", resources);

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class, () -> repo.save(List.of(skill), false));

            assertEquals("Resource path cannot be null or empty", exception.getMessage());
        }

        @Test
        @DisplayName("Should reject resource path exceeding max length")
        void testSaveSkillWithLongResourcePath() {
            Map<String, String> resources = Map.of("a".repeat(501), "content");
            AgentSkill skill =
                    new AgentSkill("skill-with-long-resource", "Desc", "Content", resources);

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("Resource path cannot exceed 500"));
        }

        @Test
        @DisplayName("Should reject blank resource path when saving")
        void testSaveSkillWithBlankResourcePath() {
            Map<String, String> resources = Map.of("  ", "content");
            AgentSkill skill =
                    new AgentSkill("skill-with-blank-resource", "Desc", "Content", resources);

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class, () -> repo.save(List.of(skill), false));

            assertEquals("Resource path cannot be null or empty", exception.getMessage());
        }

        @Test
        @DisplayName("Should roll back when skill insert fails")
        void testSaveSkillInsertFailure() throws SQLException {
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenThrow(new SQLException("insert failed"));

            AgentSkill skill =
                    new AgentSkill("insert-failure", "Description", "Content", Map.of(), "test");

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("Failed to save skills"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should roll back when generated skill id is missing")
        void testSaveSkillMissingGeneratedId() throws SQLException {
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockGeneratedKeysResultSet.next()).thenReturn(false);

            AgentSkill skill =
                    new AgentSkill("missing-id", "Description", "Content", Map.of(), "test");

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("Failed to save skills"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should roll back when resource batch insert fails")
        void testSaveSkillResourceBatchFailure() throws SQLException {
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeBatch()).thenReturn(new int[] {Statement.EXECUTE_FAILED});

            AgentSkill skill =
                    new AgentSkill(
                            "resource-batch-failure",
                            "Description",
                            "Content",
                            Map.of("readme.md", "content"),
                            "test");

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("Failed to save skills"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should fail when resource batch result is not successful")
        void testSaveSkillResourceBatchZeroResultFailure() throws SQLException {
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeBatch()).thenReturn(new int[] {0});

            AgentSkill skill =
                    new AgentSkill(
                            "resource-batch-zero-result",
                            "Description",
                            "Content",
                            Map.of("readme.md", "content"),
                            "test");

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("Failed to save skills"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should treat SUCCESS_NO_INFO resource batch result as successful")
        void testSaveSkillResourceBatchSuccessNoInfo() throws SQLException {
            when(mockResultSet.next()).thenReturn(false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeBatch()).thenReturn(new int[] {Statement.SUCCESS_NO_INFO});

            AgentSkill skill =
                    new AgentSkill(
                            "resource-batch-success-no-info",
                            "Description",
                            "Content",
                            Map.of("readme.md", "content"),
                            "test");

            assertTrue(repo.save(List.of(skill), false));
        }

        @Test
        @DisplayName("Should save metadata_json when column exists")
        void testSaveSkillWithMetadataJson() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(true, false);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", "new-skill");
            metadata.put("description", "Description");
            metadata.put("homepage", "https://example.com");
            AgentSkill skill = new AgentSkill(metadata, "Content", Map.of(), "test");

            PostgresSkillRepository metadataRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            boolean saved = metadataRepo.save(List.of(skill), false);

            assertTrue(saved);
            verify(mockStatement)
                    .setString(
                            eq(5),
                            org.mockito.ArgumentMatchers.contains(
                                    "\"homepage\":\"https://example.com\""));
        }

        @Test
        @DisplayName("Should skip metadata_json when legacy schema is used")
        void testSaveSkillLegacySchemaFallback() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(false, false);

            PostgresSkillRepository legacyRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", "legacy-skill");
            metadata.put("description", "Description");
            metadata.put("homepage", "https://example.com");
            AgentSkill skill = new AgentSkill(metadata, "Content", Map.of(), "test");

            boolean saved = legacyRepo.save(List.of(skill), false);

            assertTrue(saved);
            verify(mockStatement, never()).setString(eq(5), anyString());
        }

        @Test
        @DisplayName("Should not warn for legacy schema when metadata has only core fields")
        void testSaveSkillLegacySchemaCoreMetadataOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(false, false);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", "core-only");
            metadata.put("description", "Description");
            AgentSkill skill = new AgentSkill(metadata, "Content", Map.of(), "test");

            PostgresSkillRepository legacyRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            assertTrue(legacyRepo.save(List.of(skill), false));
            verify(mockStatement, never()).setString(eq(5), anyString());
        }

        @Test
        @DisplayName("Should detect extended metadata when core description is absent")
        void testSaveSkillLegacySchemaMetadataWithNonCoreFieldOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(false, false);

            AgentSkill skill = mock(AgentSkill.class);
            when(skill.getName()).thenReturn("partial-metadata");
            when(skill.getDescription()).thenReturn("Description");
            when(skill.getSkillContent()).thenReturn("Content");
            when(skill.getSource()).thenReturn("test");
            when(skill.getResources()).thenReturn(Map.of());
            when(skill.getMetadata())
                    .thenReturn(Map.of("name", "partial-metadata", "homepage", "example"));

            PostgresSkillRepository legacyRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            assertTrue(legacyRepo.save(List.of(skill), false));
        }

        @Test
        @DisplayName("Should not warn for legacy schema when metadata is empty")
        void testSaveSkillLegacySchemaEmptyMetadata() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockResultSet.next()).thenReturn(false, false);

            AgentSkill skill = mock(AgentSkill.class);
            when(skill.getName()).thenReturn("empty-metadata");
            when(skill.getDescription()).thenReturn("Description");
            when(skill.getSkillContent()).thenReturn("Content");
            when(skill.getSource()).thenReturn("test");
            when(skill.getResources()).thenReturn(Map.of());
            when(skill.getMetadata()).thenReturn(Map.of());

            PostgresSkillRepository legacyRepo =
                    new PostgresSkillRepository(mockDataSource, true, true);

            assertTrue(legacyRepo.save(List.of(skill), false));
            verify(mockStatement, never()).setString(eq(5), anyString());
        }

        @Test
        @DisplayName("Should throw exception when skill exists and force=false")
        void testSaveSkillExistsNoForce() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next())
                    .thenReturn(true, true); // metadata column exists, skill exists

            AgentSkill skill =
                    new AgentSkill("existing-skill", "Description", "Content", Map.of(), "test");

            // Pre-check now throws IllegalStateException instead of returning false
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class, () -> repo.save(List.of(skill), false));

            assertTrue(exception.getMessage().contains("existing-skill"));
            assertTrue(exception.getMessage().contains("force=false"));
        }

        @Test
        @DisplayName("Should overwrite skill when force=true")
        void testSaveSkillWithForce() throws SQLException {
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next())
                    .thenReturn(true, true, false); // column exists, skill exists, then deleted

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
        @DisplayName("Should restore auto-commit after save when initially enabled")
        void testSaveRestoresAutoCommitWhenInitiallyEnabled() throws SQLException {
            when(mockConnection.getAutoCommit()).thenReturn(true, false);
            when(mockResultSet.next()).thenReturn(false, false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            AgentSkill skill =
                    new AgentSkill("auto-commit-save", "Description", "Content", Map.of(), "test");

            assertTrue(repo.save(List.of(skill), false));

            verify(mockConnection, atLeast(1)).setAutoCommit(false);
            verify(mockConnection, atLeast(1)).setAutoCommit(true);
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
        @DisplayName("Should restore auto-commit after delete when initially enabled")
        void testDeleteRestoresAutoCommitWhenInitiallyEnabled() throws SQLException {
            when(mockConnection.getAutoCommit()).thenReturn(true, false);
            when(mockStatement.executeUpdate()).thenReturn(1);
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true);

            boolean deleted = repo.delete("test-skill");

            assertTrue(deleted);
            verify(mockConnection, atLeast(1)).setAutoCommit(false);
            verify(mockConnection, atLeast(1)).setAutoCommit(true);
        }

        @Test
        @DisplayName("Should roll back when delete fails")
        void testDeleteSkillFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenThrow(new SQLException("delete failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.delete("test-skill"));

            assertTrue(exception.getMessage().contains("Failed to delete skill"));
            verify(mockConnection).rollback();
        }

        @Test
        @DisplayName("Should throw exception when delete cannot open connection")
        void testDeleteConnectionFailure() throws SQLException {
            when(mockDataSource.getConnection()).thenThrow(new SQLException("connection failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.delete("test-skill"));

            assertTrue(exception.getMessage().contains("Failed to delete skill"));
        }

        @Test
        @DisplayName("Should throw exception when delete connection close fails")
        void testDeleteConnectionCloseFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenReturn(mockResultSet);
            when(mockResultSet.next()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(1);
            doThrow(new SQLException("close failed")).when(mockConnection).close();

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.delete("test-skill"));

            assertTrue(exception.getMessage().contains("Failed to delete skill"));
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
        @DisplayName("Should throw exception when checking skill existence fails")
        void testSkillExistsSqlFailure() throws SQLException {
            when(mockStatement.executeQuery()).thenThrow(new SQLException("exists failed"));

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.skillExists("test-skill"));

            assertTrue(exception.getMessage().contains("Failed to check skill existence"));
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

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("db")
                            .skillsTableName("skills")
                            .resourcesTableName("resources")
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            AgentSkill skill = new AgentSkill("test", "desc", "content", Map.of(), "test");
            boolean saved = repo.save(List.of(skill), false);

            assertFalse(saved);
        }

        @Test
        @DisplayName("Should not delete when repository is read-only")
        void testDeleteWhenReadOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("db")
                            .skillsTableName("skills")
                            .resourcesTableName("resources")
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            boolean deleted = repo.delete("test-skill");

            assertFalse(deleted);
        }

        @Test
        @DisplayName("Should not clear all skills when repository is read-only")
        void testClearAllSkillsWhenReadOnly() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("db")
                            .skillsTableName("skills")
                            .resourcesTableName("resources")
                            .createIfNotExist(true)
                            .writeable(false)
                            .build();

            int deleted = repo.clearAllSkills();

            assertEquals(0, deleted);
        }

        @Test
        @DisplayName("Should toggle writeable flag")
        void testSetWriteable() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

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

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            AgentSkillRepositoryInfo info = repo.getRepositoryInfo();

            assertNotNull(info);
            assertEquals("postgresql", info.getType());
            assertEquals("agentscope.agentscope_skills", info.getLocation());
            assertTrue(info.isWritable());
        }

        @Test
        @DisplayName("Should return correct source")
        void testGetSource() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            String source = repo.getSource();

            assertEquals("postgresql_agentscope_agentscope_skills", source);
        }

        @Test
        @DisplayName("Should return correct source with custom names")
        void testGetSourceWithCustomNames() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);

            PostgresSkillRepository repo =
                    PostgresSkillRepository.builder(mockDataSource)
                            .schemaName("custom_db")
                            .skillsTableName("custom_skills")
                            .resourcesTableName("custom_resources")
                            .createIfNotExist(true)
                            .writeable(true)
                            .build();

            String source = repo.getSource();

            assertEquals("postgresql_custom_db_custom_skills", source);
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

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);
            repo.close();

            // Should not throw exception
            assertEquals(mockDataSource, repo.getDataSource());
        }

        @Test
        @DisplayName("Should clear all skills")
        void testClearAllSkills() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(5);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);
            int deleted = repo.clearAllSkills();

            assertEquals(5, deleted);
        }

        @Test
        @DisplayName("Should restore auto-commit after clearing skills when initially enabled")
        void testClearAllSkillsRestoresAutoCommitWhenInitiallyEnabled() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenReturn(5);

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);
            when(mockConnection.getAutoCommit()).thenReturn(true, false);
            int deleted = repo.clearAllSkills();

            assertEquals(5, deleted);
            verify(mockConnection, atLeast(1)).setAutoCommit(false);
            verify(mockConnection, atLeast(1)).setAutoCommit(true);
        }

        @Test
        @DisplayName("Should roll back when clearing skills fails")
        void testClearAllSkillsFailure() throws SQLException {
            when(mockStatement.execute()).thenReturn(true);
            when(mockStatement.executeUpdate()).thenThrow(new SQLException("clear failed"));

            PostgresSkillRepository repo = new PostgresSkillRepository(mockDataSource, true, true);

            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> repo.clearAllSkills());

            assertTrue(exception.getMessage().contains("Failed to clear skills"));
            verify(mockConnection).rollback();
        }
    }
}
