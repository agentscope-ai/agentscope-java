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
package io.agentscope.extensions.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 tests for the builder's table-group switches: default / override / idempotent
 * semantics, the on-off combinations (both off rejected at build), orthogonality with
 * {@code autoCreateTable}, the skill-only shape (base tables neither created nor
 * validated), and the repository's misconfiguration guard.
 *
 * @author shanhongyu
 */
@DisplayName("AbstractJdbcDialectBuilder table-group switches (H2)")
class DialectBuilderTableGroupsTest {

    // ------------------------------------------------------------------
    //  Four switch combinations and setter semantics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("defaults create and validate the base group only, skill group off")
    void defaultGroups() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("groups_default");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();

        assertTrue(dialect.isBaseTablesEnabled());
        assertFalse(dialect.isSkillTablesEnabled());
        assertTrue(tableExists(ds, "agentscope_store"));
        assertTrue(tableExists(ds, "agentscope_snapshots"));
        assertFalse(tableExists(ds, "agentscope_skills"));
        assertFalse(tableExists(ds, "agentscope_skill_resources"));
    }

    @Test
    @DisplayName("skill-only build creates and validates the skill tables, never the base tables")
    void skillOnly() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("groups_skill_only");
        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(ds)
                        .enableBaseTables(false)
                        .enableSkillTables(true)
                        .build();

        assertTrue(dialect.isSkillTablesEnabled());
        assertFalse(dialect.isBaseTablesEnabled());
        assertTrue(tableExists(ds, "agentscope_skills"));
        assertTrue(tableExists(ds, "agentscope_skill_resources"));
        // Absent base tables prove they were neither created nor validated (validation of a
        // missing table would fail the build).
        assertFalse(tableExists(ds, "agentscope_store"));
        assertFalse(tableExists(ds, "agentscope_sessions"));
        assertFalse(tableExists(ds, "agentscope_snapshots"));
    }

    @Test
    @DisplayName("both groups on create all five tables")
    void bothGroups() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("groups_both");
        AbstractJdbcDialect.from(ds).enableSkillTables(true).build();

        for (String table :
                new String[] {
                    "agentscope_store",
                    "agentscope_sessions",
                    "agentscope_snapshots",
                    "agentscope_skills",
                    "agentscope_skill_resources"
                }) {
            assertTrue(tableExists(ds, table), table + " must exist");
        }
    }

    @Test
    @DisplayName("both groups off fail the build; a reverted skill switch stays off")
    void offGroupsFailBuildAndRevertedSwitch() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("groups_off");
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                AbstractJdbcDialect.from(ds)
                                        .enableBaseTables(false)
                                        .enableSkillTables(false)
                                        .build());
        // The message must name both switches so the misconfiguration is self-explaining.
        assertTrue(exception.getMessage().contains("enableBaseTables(false)"));
        assertTrue(exception.getMessage().contains("enableSkillTables(false)"));
        assertFalse(tableExists(ds, "agentscope_store"));
        assertFalse(tableExists(ds, "agentscope_skills"));

        // Re-enabling then reverting runs the build with the final value — last call wins.
        AbstractJdbcDialect.from(ds)
                .enableSkillTables(true)
                .enableSkillTables(false)
                .enableSkillTables(false)
                .build();
        assertFalse(tableExists(ds, "agentscope_skills"));
    }

    // ------------------------------------------------------------------
    //  Orthogonality with autoCreateTable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("autoCreateTable(false) with the skill group validates instead of creating")
    void autoCreateFalseValidatesSkillGroupOnly() {
        DataSource ds = H2TestSupport.createDataSource("groups_no_ddl");
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                AbstractJdbcDialect.from(ds)
                                        .enableBaseTables(false)
                                        .enableSkillTables(true)
                                        .autoCreateTable(false)
                                        .build());
        assertTrue(exception.getMessage().contains("agentscope_skills"));
        assertTrue(exception.getMessage().toUpperCase(Locale.ROOT).contains("CREATE TABLE"));
    }

    @Test
    @DisplayName("reopening a skill-enabled database with autoCreateTable(false) passes validation")
    void reopenWithAutoCreateFalse() {
        DataSource ds = H2TestSupport.createDataSource("groups_reopen");
        AbstractJdbcDialect.from(ds).enableSkillTables(true).build();

        assertDoesNotThrow(
                () ->
                        AbstractJdbcDialect.from(ds)
                                .enableSkillTables(true)
                                .autoCreateTable(false)
                                .build());
    }

    // ------------------------------------------------------------------
    //  Repository misconfiguration guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the repository fails fast when the skill group was not enabled at build")
    void repositoryConsistencyCheck() {
        DataSource ds = H2TestSupport.createDataSource("groups_mismatch");
        AbstractJdbcDialect baseOnly = AbstractJdbcDialect.from(ds).build();

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> new JdbcAgentSkillRepository(ds, baseOnly));
        // The message must point at the group by its tables and its enabling switch.
        assertTrue(exception.getMessage().contains("agentscope_skills"));
        assertTrue(exception.getMessage().contains("enableSkillTables(true)"));

        // A dialect built with the group enabled satisfies the guard.
        assertDoesNotThrow(
                () ->
                        new JdbcAgentSkillRepository(
                                ds, AbstractJdbcDialect.from(ds).enableSkillTables(true).build()));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Whether the named table exists in H2's PUBLIC schema. */
    private static boolean tableExists(DataSource ds, String table) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                        + " WHERE TABLE_SCHEMA = 'PUBLIC' AND UPPER(TABLE_NAME) = '"
                                        + table.toUpperCase(Locale.ROOT)
                                        + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
