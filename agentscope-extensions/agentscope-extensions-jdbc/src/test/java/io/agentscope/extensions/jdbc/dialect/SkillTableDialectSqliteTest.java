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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite execution tests for the skill table group — the vendor whose DDL needs
 * {@code INTEGER PRIMARY KEY AUTOINCREMENT} for generated keys and, unlike the others,
 * enforces the cascading foreign key only with {@code PRAGMA foreign_keys} on. Verifies the
 * DDL executes, validation passes, and the repository's explicit resource deletion keeps
 * behavior identical to the cascade-backed vendors.
 *
 * <p>A file-backed database is used (via {@link TempDir}) so separate connections see the
 * same schema.
 *
 * @author shanhongyu
 */
@DisplayName("Skill table dialects (SQLite)")
class SkillTableDialectSqliteTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("skill DDLs execute, validate, and return generated keys")
    void ddlExecutesAndReturnsGeneratedKeys() throws Exception {
        DataSource ds = createDataSource("skill_ddl");
        SqliteDialect dialect = new SqliteDialect();

        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : dialect.skillCreateTableDdls()) {
                stmt.execute(ddl);
            }
            for (String ddl : dialect.skillResourcesCreateTableDdls()) {
                stmt.execute(ddl);
            }
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillTableName(),
                                    dialect.skillCreateTableDdls()));

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO agentscope_skills (name, description, skill_content,"
                                    + " source) VALUES ('s1', 'd', 'c', 'test')",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next(), "SQLite AUTOINCREMENT must return a generated key");
                    assertTrue(keys.getLong(1) > 0);
                }
            }
        }
    }

    @Test
    @DisplayName("repository round-trip on SQLite deletes resources explicitly with the skill")
    void repositoryRoundTripDeletesResources() throws Exception {
        DataSource ds = createDataSource("skill_repo");
        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(ds)
                        .enableBaseTables(false)
                        .enableSkillTables(true)
                        .build();
        JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, dialect);

        var skill =
                new io.agentscope.core.skill.AgentSkill(
                        Map.of("name", "sqlite-skill", "description", "d"),
                        "content",
                        Map.of("docs/readme.md", "hello"),
                        "test");
        assertTrue(repo.save(List.of(skill), false));
        assertEquals("hello", repo.getSkill("sqlite-skill").getResource("docs/readme.md"));

        // Foreign keys are off by default in SQLite, so this passing proves the repository
        // deletes the resource rows itself instead of relying on ON DELETE CASCADE.
        assertTrue(repo.delete("sqlite-skill"));
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery("SELECT COUNT(*) FROM agentscope_skill_resources")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "resources must be gone with the skill");
        }
    }

    /** A file-backed SQLite DataSource, one database file per test. */
    private DataSource createDataSource(String name) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve(name + ".db").toAbsolutePath());
        return ds;
    }
}
