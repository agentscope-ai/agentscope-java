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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * H2 behavior tests for {@link JdbcAgentSkillRepository} — the interface-behavior cases of
 * the deprecated Mysql/PostgresSkillRepositoryTest suites (CRUD semantics, force conflicts,
 * read-only rejection, existence, info/source, validation) executed against a real
 * database instead of mocks. The builder's legacy-table interception is covered by {@link
 * io.agentscope.extensions.jdbc.dialect.SkillTableDialectH2Test}.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcAgentSkillRepository (H2)")
class JdbcAgentSkillRepositoryH2Test {

    /** Creates a repository over tables created by the builder's skill group. */
    private static JdbcAgentSkillRepository newRepository(String dbName) {
        DataSource ds = H2TestSupport.createDataSource(dbName);
        return new JdbcAgentSkillRepository(ds, skillDialect(ds));
    }

    /** Assembles the dialect with the skill group enabled — the only schema entry point. */
    private static AbstractJdbcDialect skillDialect(DataSource ds) {
        return AbstractJdbcDialect.from(ds).enableBaseTables(false).enableSkillTables(true).build();
    }

    /** A skill with extended metadata and two resources. */
    private static AgentSkill sampleSkill(String name) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("description", "Description of " + name);
        metadata.put("homepage", "https://example.com/" + name);
        return new AgentSkill(
                metadata,
                "Content of " + name,
                Map.of("docs/readme.md", "hello", "docs/guide.md", "world"),
                "test");
    }

    @Nested
    @DisplayName("CRUD and listing")
    class CrudTests {

        @Test
        @DisplayName("save then getSkill round-trips content, resources, and extended metadata")
        void saveAndGetRoundTrip() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_roundtrip");

            assertTrue(repo.save(List.of(sampleSkill("demo")), false));

            AgentSkill loaded = repo.getSkill("demo");
            assertEquals("demo", loaded.getName());
            assertEquals("Description of demo", loaded.getDescription());
            assertEquals("Content of demo", loaded.getSkillContent());
            assertEquals("test", loaded.getSource());
            assertEquals("https://example.com/demo", loaded.getMetadataValue("homepage"));
            assertEquals("hello", loaded.getResource("docs/readme.md"));
            assertEquals("world", loaded.getResource("docs/guide.md"));
        }

        @Test
        @DisplayName("getAllSkillNames and getAllSkills list everything ordered, empty when none")
        void listOperations() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_list");
            assertTrue(repo.getAllSkillNames().isEmpty());
            assertTrue(repo.getAllSkills().isEmpty());

            repo.save(List.of(sampleSkill("beta"), sampleSkill("alpha")), false);

            assertEquals(List.of("alpha", "beta"), repo.getAllSkillNames());
            List<AgentSkill> skills = repo.getAllSkills();
            assertEquals(2, skills.size());
            assertEquals("alpha", skills.get(0).getName());
            assertEquals("hello", skills.get(1).getResource("docs/readme.md"));
        }

        @Test
        @DisplayName("getSkill of an unknown name throws IllegalArgumentException")
        void unknownSkillThrows() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_unknown");
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill("missing"));
        }

        @Test
        @DisplayName("save without force throws listing conflicts; with force it replaces the row")
        void forceSemantics() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_force");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            repo.save(List.of(sampleSkill("conflict")), false);

            IllegalStateException conflict =
                    assertThrows(
                            IllegalStateException.class,
                            () -> repo.save(List.of(sampleSkill("conflict")), false));
            assertTrue(
                    conflict.getMessage().contains("the following skills already exist"),
                    conflict.getMessage());
            assertTrue(conflict.getMessage().contains("force=false"));

            // The forced write carries different content, so a broken overwrite (stale row or
            // duplicated resources) cannot pass by accident.
            AgentSkill replacement =
                    new AgentSkill(
                            Map.of("name", "conflict", "description", "replaced"),
                            "replaced content",
                            Map.of("docs/new.md", "new"),
                            "test");
            assertTrue(repo.save(List.of(replacement), true));

            AgentSkill loaded = repo.getSkill("conflict");
            assertEquals("replaced", loaded.getDescription());
            assertEquals("replaced content", loaded.getSkillContent());
            assertEquals("new", loaded.getResource("docs/new.md"));
            assertNull(loaded.getResource("docs/readme.md"), "old resources must be replaced");
            assertEquals(1, countRows(ds, "agentscope_skill_resources"));
        }

        @Test
        @DisplayName("delete removes the skill with its resources; unknown skill returns false")
        void deleteSemantics() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_delete");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            assertFalse(repo.delete("missing"));

            repo.save(List.of(sampleSkill("doomed")), false);
            assertTrue(repo.delete("doomed"));
            assertFalse(repo.skillExists("doomed"));
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill("doomed"));
            assertEquals(0, countRows(ds, "agentscope_skill_resources"), "resources must be gone");
        }

        @Test
        @DisplayName("skillExists answers for present, absent, null, and empty names")
        void skillExistsCases() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_exists");
            repo.save(List.of(sampleSkill("present")), false);

            assertTrue(repo.skillExists("present"));
            assertFalse(repo.skillExists("absent"));
            assertFalse(repo.skillExists(null));
            assertFalse(repo.skillExists(""));
        }

        @Test
        @DisplayName(
                "a duplicate name inside one save fails on the UNIQUE constraint and rolls back")
        void duplicateWithinOneSaveFailsOnUniqueConstraint() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_self_conflict");

            IllegalStateException conflict =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    repo.save(
                                            List.of(sampleSkill("dup"), sampleSkill("dup")),
                                            false));

            assertTrue(conflict.getMessage().contains("force=false"), conflict.getMessage());
            assertTrue(
                    conflict.getCause() instanceof SQLException,
                    "the driver's error must stay chained as the cause");
            assertFalse(repo.skillExists("dup"), "the first row must be rolled back");
        }

        @Test
        @DisplayName("a database failure during the resource batch rolls the skill insert back")
        void resourceBatchFailureRollsBack() throws Exception {
            // The null value the old variant relied on is now pre-rejected, so a CHECK
            // constraint forces the batch failure instead.
            DataSource ds = H2TestSupport.createDataSource("skill_repo_batch_failure");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            try (Connection conn = ds.getConnection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "ALTER TABLE agentscope_skill_resources ADD CONSTRAINT"
                                + " resource_poison_check CHECK (resource_content <> 'poison')");
            }
            Map<String, String> resources = new HashMap<>();
            resources.put("ok.md", "content");
            resources.put("broken.md", "poison");
            AgentSkill skill =
                    new AgentSkill(
                            Map.of("name", "batch", "description", "d"), "c", resources, "test");

            RuntimeException failure =
                    assertThrows(RuntimeException.class, () -> repo.save(List.of(skill), false));

            assertFalse(
                    failure instanceof IllegalStateException,
                    "a CHECK violation is not a duplicate conflict: " + failure.getMessage());
            assertTrue(failure.getCause() instanceof SQLException);
            assertFalse(repo.skillExists("batch"), "the skill row must be rolled back");
        }

        @Test
        @DisplayName("clearAllSkills deletes every skill and resource row")
        void clearAllSkills() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_clear");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            repo.save(List.of(sampleSkill("a"), sampleSkill("b")), false);

            assertEquals(2, repo.clearAllSkills());
            assertTrue(repo.getAllSkillNames().isEmpty());
            // H2's cascade would also clean resources, so the raw count is what proves the
            // repository issues its own resource delete (needed on SQLite).
            assertEquals(0, countRows(ds, "agentscope_skill_resources"));
        }

        @Test
        @DisplayName("a skill without resources round-trips with an empty resource map")
        void skillWithoutResources() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_no_resources");
            AgentSkill bare =
                    new AgentSkill(
                            Map.of("name", "bare", "description", "d"), "content", null, "test");

            assertTrue(repo.save(List.of(bare), false));

            assertTrue(repo.getSkill("bare").getResources().isEmpty());
            assertTrue(repo.getAllSkills().get(0).getResources().isEmpty());
        }
    }

    @Nested
    @DisplayName("Namespace isolation")
    class NamespaceTests {

        @Test
        @DisplayName(
                "the same name coexists in two namespaces and each namespace only sees its own")
        void sameNameCoexistsAcrossNamespaces() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_ns_coexist");
            AgentSkill teamA = sampleSkill("code-review");
            AgentSkill teamB =
                    new AgentSkill(
                            Map.of("name", "code-review", "description", "team-b flavor"),
                            "Content of team-b",
                            Map.of("docs/team-b.md", "b"),
                            "test");

            assertTrue(repo.save("team-a", List.of(teamA), false));
            assertTrue(repo.save("team-b", List.of(teamB), false));

            assertEquals(List.of("code-review"), repo.getAllSkillNames("team-a"));
            assertEquals(List.of("code-review"), repo.getAllSkillNames("team-b"));
            assertEquals(
                    "Content of code-review",
                    repo.getSkill("team-a", "code-review").getSkillContent());
            assertEquals(
                    "Content of team-b", repo.getSkill("team-b", "code-review").getSkillContent());
            assertEquals(
                    "b",
                    repo.getSkill("team-b", "code-review").getResource("docs/team-b.md"),
                    "resources must be stitched to the owning namespace's row");

            assertTrue(repo.skillExists("team-a", "code-review"));
            assertFalse(repo.skillExists("team-a", "never-saved"));

            assertTrue(repo.getAllSkillNames().isEmpty(), "the default namespace stays empty");
        }

        @Test
        @DisplayName("getAllSkills loads one namespace only, resources included")
        void getAllSkillsIsNamespaced() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_ns_list_all");
            AgentSkill teamA = sampleSkill("alpha");
            AgentSkill teamB =
                    new AgentSkill(
                            Map.of("name", "beta", "description", "team-b skill"),
                            "Content of beta",
                            Map.of("docs/team-b.md", "b"),
                            "test");
            repo.save("team-a", List.of(teamA), false);
            repo.save("team-b", List.of(teamB), false);

            List<AgentSkill> teamASkills = repo.getAllSkills("team-a");

            assertEquals(List.of("alpha"), teamASkills.stream().map(AgentSkill::getName).toList());
            assertEquals(
                    "hello",
                    teamASkills.get(0).getResource("docs/readme.md"),
                    "resources of the loaded namespace must be stitched");
            assertEquals("Content of alpha", teamASkills.get(0).getSkillContent());
            assertTrue(
                    repo.getAllSkills("ghost").isEmpty(), "an unknown namespace is simply empty");
        }

        @Test
        @DisplayName("force=true overwrites only within the namespace, never across")
        void forceIsNamespaced() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_ns_force");
            repo.save("team-a", List.of(sampleSkill("shared")), false);
            repo.save("team-b", List.of(sampleSkill("shared")), false);

            AgentSkill replacement =
                    new AgentSkill(
                            Map.of("name", "shared", "description", "replaced"),
                            "replaced content",
                            Map.of("docs/new.md", "new"),
                            "test");
            assertTrue(repo.save("team-a", List.of(replacement), true));

            assertEquals("replaced content", repo.getSkill("team-a", "shared").getSkillContent());
            assertNull(repo.getSkill("team-a", "shared").getResource("docs/readme.md"));
            assertEquals(
                    "Content of shared",
                    repo.getSkill("team-b", "shared").getSkillContent(),
                    "the other namespace's same-name skill must stay untouched");
            assertEquals(
                    "hello",
                    repo.getSkill("team-b", "shared").getResource("docs/readme.md"),
                    "the other namespace's resources must stay untouched");
        }

        @Test
        @DisplayName("a duplicate within one namespace is rejected by UNIQUE(namespace, name)")
        void duplicateWithinOneNamespaceRejected() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_ns_unique");
            repo.save("team-a", List.of(sampleSkill("dup")), false);

            IllegalStateException conflict =
                    assertThrows(
                            IllegalStateException.class,
                            () -> repo.save("team-a", List.of(sampleSkill("dup")), false));
            assertTrue(conflict.getMessage().contains("force=false"), conflict.getMessage());
        }

        @Test
        @DisplayName("delete and clearAllSkills stay within the namespace")
        void deleteAndClearAreNamespaced() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_ns_delete");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            repo.save("team-a", List.of(sampleSkill("gone"), sampleSkill("kept")), false);
            repo.save("team-b", List.of(sampleSkill("gone")), false);

            assertTrue(repo.delete("team-a", "gone"));
            assertFalse(repo.skillExists("team-a", "gone"));
            assertTrue(repo.skillExists("team-b", "gone"), "the other namespace keeps its row");
            assertEquals(List.of("kept"), repo.getAllSkillNames("team-a"));

            assertEquals(1, repo.clearAllSkills("team-a"));
            assertTrue(repo.getAllSkillNames("team-a").isEmpty());
            assertTrue(repo.skillExists("team-b", "gone"));
            assertEquals(
                    2,
                    countRows(ds, "agentscope_skill_resources"),
                    "only team-b's resources must remain");
        }

        @Test
        @DisplayName("invalid namespaces are rejected at the entry points")
        void invalidNamespacesRejected() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_ns_invalid");
            for (String bad :
                    Arrays.asList(null, "", "   ", "team a", "team/a", "..", ".", "a".repeat(65))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repo.skillExists(bad, "any"),
                        "namespace must be rejected: " + bad);
            }
        }

        @Test
        @DisplayName("a repository constructed with a namespace binds it as the default")
        void boundNamespaceConstructor() {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_ns_bound");
            JdbcAgentSkillRepository repo =
                    new JdbcAgentSkillRepository(ds, skillDialect(ds), "team-a", true);

            assertEquals("team-a", repo.getNamespace());
            assertTrue(repo.save(List.of(sampleSkill("bound")), false));
            assertTrue(repo.skillExists("bound"));
            assertTrue(repo.skillExists("team-a", "bound"));
            assertFalse(repo.skillExists("team-b", "bound"));
            assertEquals(List.of("bound"), repo.getAllSkillNames());
        }
    }

    @Nested
    @DisplayName("Validation and read-only")
    class ValidationTests {
        @Test
        @DisplayName("getSkill rejects null, empty, over-length, and traversal names")
        void skillNameValidation() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_validate");
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill(null));
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill(""));
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill("../etc/passwd"));
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill("a".repeat(256)));
        }

        @Test
        @DisplayName(
                "save rejects null/empty lists, null elements, bad resource paths, and null"
                        + " resource values")
        void saveInputValidation() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_save_lists");
            assertFalse(repo.save(null, false));
            assertFalse(repo.save(List.of(), false));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.save(Arrays.asList(sampleSkill("a"), null), false));

            for (String badPath :
                    List.of(
                            "",
                            "   ",
                            "/etc/passwd",
                            "..\\secrets.txt",
                            "../x",
                            "a/../b",
                            "C:/temp/x")) {
                AgentSkill skill =
                        new AgentSkill(
                                Map.of("name", "paths", "description", "d"),
                                "content",
                                Map.of(badPath, "x"),
                                "test");
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repo.save(List.of(skill), false),
                        "resource path must be rejected: " + badPath);
            }
            AgentSkill tooLong =
                    new AgentSkill(
                            Map.of("name", "paths", "description", "d"),
                            "content",
                            Map.of("a".repeat(501), "x"),
                            "test");
            assertThrows(IllegalArgumentException.class, () -> repo.save(List.of(tooLong), false));

            // resource_content is NOT NULL in every vendor DDL.
            Map<String, String> nullValue = new HashMap<>();
            nullValue.put("broken.md", null);
            AgentSkill nullContent =
                    new AgentSkill(
                            Map.of("name", "paths", "description", "d"),
                            "content",
                            nullValue,
                            "test");
            IllegalArgumentException rejected =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> repo.save(List.of(nullContent), false));
            assertTrue(rejected.getMessage().contains("broken.md"), rejected.getMessage());

            assertFalse(repo.skillExists("paths"), "no rejected save may persist anything");
        }

        @Test
        @DisplayName("read-only repositories refuse save, delete, and clear; writeable toggles")
        void readOnlyAndToggle() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_readonly");
            AgentSkill skill = sampleSkill("frozen");

            assertTrue(repo.isWriteable());
            repo.setWriteable(false);
            assertFalse(repo.save(List.of(skill), false));
            assertFalse(repo.delete("frozen"));
            assertEquals(0, repo.clearAllSkills());
            assertFalse(repo.isWriteable());

            repo.setWriteable(true);
            assertTrue(repo.save(List.of(skill), false));
            assertTrue(repo.isWriteable());
        }

        @Test
        @DisplayName("unique-violation classification matches only duplicate signals")
        void narrowsToDuplicateSignals() {
            // Portable unique-violation states.
            assertTrue(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", "23505", 0)));
            // The umbrella 23500 has no named driver and covers sibling violations too.
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", "23500", 0)));
            // Generic state 23000: the vendor code decides.
            assertTrue(
                    JdbcAgentSkillRepository.isUniqueViolation(
                            new SQLException("d", "23000", 1062)));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(
                            new SQLException("d", "23000", 1048)));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(
                            new SQLIntegrityConstraintViolationException("d", "23000", 1048)),
                    "MySQL raises the JDBC 4 subtype for NOT NULL too — the exact misreport");
            // Class-23 siblings must fall through as the driver's own error.
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", "23502", 0)));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", "23503", 0)));
            assertFalse(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", "23514", 0)));
            // State-less vendor duplicate codes.
            assertTrue(
                    JdbcAgentSkillRepository.isUniqueViolation(new SQLException("d", null, -239)));
        }
    }

    @Nested
    @DisplayName("Repository info and construction")
    class InfoAndConstructionTests {

        @Test
        @DisplayName("getRepositoryInfo and getSource carry the bound namespace; close is a no-op")
        void infoAndSource() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_info");

            AgentSkillRepositoryInfo info = repo.getRepositoryInfo();
            assertEquals("jdbc", info.getType());
            assertEquals("agentscope_skills@default", info.getLocation());
            assertTrue(info.isWritable());
            assertEquals("jdbc_agentscope_skills@default", repo.getSource());
            assertDoesNotThrow(repo::close);

            // The bound namespace is part of the identity: two scopes of one table must
            // not share a source string — the skill staging cache keys on it.
            DataSource ds = H2TestSupport.createDataSource("skill_repo_info_ns");
            JdbcAgentSkillRepository teamA =
                    new JdbcAgentSkillRepository(ds, skillDialect(ds), "team-a", true);
            assertEquals("jdbc_agentscope_skills@team-a", teamA.getSource());
            assertEquals("agentscope_skills@team-a", teamA.getRepositoryInfo().getLocation());
            assertDoesNotThrow(teamA::close);
        }

        @Test
        @DisplayName(
                "path-shaped namespaces are rejected at construction — getSource() feeds a"
                        + " path segment")
        void pathShapedNamespacesRejectedAtConstruction() {
            // getSource() embeds the namespace into a string MarketplaceStager resolves as
            // a .skills-cache path segment, so the constructor's validation is what keeps
            // the identity traversal-safe; loosening it must break this test, visibly.
            DataSource ds = H2TestSupport.createDataSource("skill_repo_ns_path");
            AbstractJdbcDialect dialect = skillDialect(ds);
            for (String bad : Arrays.asList(".", "..", "team/a", "team\\a")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new JdbcAgentSkillRepository(ds, dialect, bad, true),
                        "path-shaped namespace must be rejected: " + bad);
            }
        }

        @Test
        @DisplayName("the no-arg constructor binds the default namespace constant")
        void defaultNamespaceConstant() {
            JdbcAgentSkillRepository repo = newRepository("skill_repo_default_ns");

            assertEquals("default", repo.getNamespace());
        }

        @Test
        @DisplayName("null dataSource or dialect is rejected")
        void nullArgumentsRejected() {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_null_args");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JdbcAgentSkillRepository(null, new H2Dialect(), true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JdbcAgentSkillRepository(ds, null, true));
        }

        @Test
        @DisplayName(
                "getAllSkills skips unbuildable rows, keeps JSON-fallback rows, tolerates orphans")
        void degradedRowsAreTolerated() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_degraded");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            try (Connection conn = ds.getConnection();
                    Statement stmt = conn.createStatement()) {
                // The JSON's name/description deliberately disagree with the columns: the
                // columns are authoritative.
                stmt.execute(
                        "INSERT INTO agentscope_skills (name, description, skill_content, source,"
                                + " metadata_json) VALUES ('valid', 'd', 'c', 'test',"
                                + " '{\"name\":\"json-name\",\"description\":\"json-desc\","
                                + "\"homepage\":\"h\"}')");
                // An unbuildable row: AgentSkill rejects empty content, so it must be skipped.
                stmt.execute(
                        "INSERT INTO agentscope_skills"
                                + " (name, description, skill_content, source)"
                                + " VALUES ('empty-content', 'd', '', 'test')");
                // A malformed metadata_json must fall back to the core columns, not fail.
                stmt.execute(
                        "INSERT INTO agentscope_skills"
                                + " (name, description, skill_content, source, metadata_json)"
                                + " VALUES ('badjson', 'd', 'c', 'test', 'not-json')");
                // A resource whose owning skill no longer exists must be warned about, not fail.
                // H2 enforces the foreign key (proving the DDL ported correctly), so the
                // orphan row is staged with referential integrity temporarily off.
                stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");
                stmt.execute(
                        "INSERT INTO agentscope_skill_resources (id, resource_path,"
                                + " resource_content) VALUES (99999, 'x.md', 'x')");
                stmt.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }

            List<AgentSkill> skills = repo.getAllSkills();

            assertEquals(
                    List.of("badjson", "valid"), skills.stream().map(AgentSkill::getName).toList());
            assertEquals(
                    List.of("name", "description"),
                    List.copyOf(skills.get(0).getMetadata().keySet()),
                    "malformed metadata_json must degrade to core metadata");
            AgentSkill fromJson = skills.get(1);
            assertEquals("valid", fromJson.getName(), "SQL columns must win over metadata_json");
            assertEquals("d", fromJson.getDescription());
            assertEquals("h", fromJson.getMetadataValue("homepage"));
        }

        @Test
        @DisplayName(
                "adopted legacy rows with traversal paths or separator names are refused on read")
        void legacyTraversalRowsRefusedOnRead() throws Exception {
            DataSource ds = H2TestSupport.createDataSource("skill_repo_legacy_paths");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(ds, skillDialect(ds));
            try (Connection conn = ds.getConnection();
                    Statement stmt = conn.createStatement()) {
                // Legacy repositories accepted absolute and .. paths on write.
                stmt.execute(
                        "INSERT INTO agentscope_skills (name, description, skill_content, source)"
                                + " VALUES ('legacy', 'd', 'c', 'test')");
                stmt.execute(
                        "INSERT INTO agentscope_skill_resources (id, resource_path,"
                                + " resource_content) VALUES"
                                + " ((SELECT id FROM agentscope_skills WHERE name = 'legacy'),"
                                + " '../../escape.txt', 'x')");
                stmt.execute(
                        "INSERT INTO agentscope_skills (name, description, skill_content, source)"
                                + " VALUES ('../evil', 'd', 'c', 'test')");
            }

            IllegalArgumentException rejected =
                    assertThrows(IllegalArgumentException.class, () -> repo.getSkill("legacy"));
            assertTrue(rejected.getMessage().contains("../../escape.txt"), rejected.getMessage());

            assertTrue(repo.getAllSkills().isEmpty(), "the guard must extend to reads");
            // The name list shares the guard: invalid names are omitted (never loadable or
            // deletable); valid names stay listed and deletable despite poisoned resources.
            assertTrue(repo.getAllSkillNames().contains("legacy"));
            assertFalse(repo.getAllSkillNames().contains("../evil"));
        }
    }

    /** Number of rows in a table — the raw-JDBC check for delete/cascade side effects. */
    private static int countRows(DataSource ds, String table) throws Exception {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
