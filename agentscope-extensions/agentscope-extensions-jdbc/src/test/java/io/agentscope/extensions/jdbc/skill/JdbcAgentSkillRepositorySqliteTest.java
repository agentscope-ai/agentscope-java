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
package io.agentscope.extensions.jdbc.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite conflict-classification tests — the one vendor reporting every {@code
 * SQLITE_CONSTRAINT_*} as error 19 with a null SQLState, so {@link
 * JdbcAgentSkillRepository#isUniqueViolation} must narrow via the driver's {@code
 * getResultCode()}. Uses real driver exceptions: a plain {@code SQLException} never reaches
 * that branch, and the narrowing once silently matched neither. File-backed (via {@link
 * TempDir}) so separate connections see the same schema.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcAgentSkillRepository (SQLite)")
class JdbcAgentSkillRepositorySqliteTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("a duplicate save surfaces the force=false conflict and rolls back")
    void duplicateSaveSurfacesConflictAndRollsBack() {
        DataSource ds = createDataSource("skill_repo_conflict");
        JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));

        IllegalStateException conflict =
                assertThrows(
                        IllegalStateException.class,
                        () -> repo.save(List.of(sampleSkill("dup"), sampleSkill("dup")), false));

        assertTrue(conflict.getMessage().contains("force=false"), conflict.getMessage());
        assertTrue(
                conflict.getCause() instanceof SQLException,
                "the driver's error must stay chained as the cause");
        assertFalse(repo.skillExists("dup"), "the first row must be rolled back");
    }

    @Test
    @DisplayName("real driver errors classify: SQLITE_CONSTRAINT_UNIQUE matches, NOT NULL not")
    void realDriverErrorsClassify() throws Exception {
        DataSource ds = createDataSource("skill_classifier");
        skillDialect(ds); // build creates the tables the raw inserts target
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            String insert =
                    "INSERT INTO agentscope_skills (name, description, skill_content, source)"
                            + " VALUES ";
            String row = "('n', 'd', 'c', 'test')";
            stmt.execute(insert + row);
            SQLException duplicate =
                    assertThrows(SQLException.class, () -> stmt.execute(insert + row));
            assertTrue(
                    JdbcAgentSkillRepository.isUniqueViolation(duplicate),
                    "SQLITE_CONSTRAINT_UNIQUE must classify as a duplicate: " + duplicate);
            SQLException notNull =
                    assertThrows(
                            SQLException.class,
                            () -> stmt.execute(insert + "('n2', NULL, 'c', 'test')"));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(notNull),
                    "SQLITE_CONSTRAINT_NOTNULL must not classify as a duplicate: " + notNull);
        }
    }

    /** Assembles the dialect with the skill group enabled — the only schema entry point. */
    private static AbstractJdbcDialect skillDialect(DataSource ds) {
        return AbstractJdbcDialect.from(ds).enableBaseTables(false).enableSkillTables(true).build();
    }

    /** A minimal skill — the conflict path needs no resources. */
    private static AgentSkill sampleSkill(String name) {
        return new AgentSkill(Map.of("name", name, "description", "d"), "c", Map.of(), "test");
    }

    /** A file-backed SQLite DataSource, one database file per test. */
    private DataSource createDataSource(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve(name + ".db").toAbsolutePath());
        return ds;
    }
}
