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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SkillDialect;
import io.agentscope.extensions.jdbc.dialect.table.SkillResourcesDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database-agnostic skill repository backed by {@link AbstractJdbcDialect}.
 *
 * <p>Implements {@link AgentSkillRepository} with no inline business SQL — statements come
 * from the dialect's skill table domains via {@link BoundSql}. Behavior is ported from the
 * deprecated skill
 * mysql/postgresql repositories. Schema is the builder's unified validation: a table
 * missing a declared column such as {@code metadata_json} fails at the check phase with the
 * reference DDL.
 *
 * <p>Example:
 * <pre>{@code
 * AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
 *         .enableSkillTables(true)
 *         .build();
 * AgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, dialect);
 * repo.save(List.of(skill), false);
 * }</pre>
 *
 * @author shanhongyu
 */
public class JdbcAgentSkillRepository implements AgentSkillRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentSkillRepository.class);

    /** Repository type reported by {@link #getRepositoryInfo()}. */
    private static final String REPOSITORY_TYPE = "jdbc";

    /** Maximum length for a skill name, matching the legacy repositories. */
    private static final int MAX_SKILL_NAME_LENGTH = 255;

    /** Maximum length for a resource path, matching the legacy repositories. */
    private static final int MAX_RESOURCE_PATH_LENGTH = 500;

    /**
     * Windows drive prefix, e.g. {@code C:}. Deliberately also matches drive-relative forms
     * like {@code C:foo}, which resolve outside the skill directory on Windows; the cost is
     * rejecting a POSIX filename such as {@code a:b.txt}, which is not expected in skills.
     */
    private static final Pattern DRIVE_LETTER_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    /** SQLite's {@code SQLITE_CONSTRAINT}; xerial reports it with a null SQLState. */
    private static final int SQLITE_CONSTRAINT = 19;

    /**
     * Duplicate-key codes for drivers reporting no SQLState at all and not covered by {@link
     * #SQLITE_CONSTRAINT}: Informix and GBase 8s — {@code -239} (duplicate value in a unique
     * index) and {@code -268} (unique constraint violation). Values from the vendor
     * documentation; those drivers were not available to verify against, unlike every other
     * entry in {@link #isUniqueViolation}.
     */
    private static final Set<Integer> VENDOR_UNIQUE_VIOLATION_CODES = Set.of(-239, -268);

    /**
     * SQLStates that positively report a unique-constraint violation: {@code 23505} (the
     * SQL:2003 code — PostgreSQL and its forks, H2, HSQLDB, Derby, DB2). The umbrella {@code
     * 23500} is deliberately absent — no driver reporting it for duplicates could be named,
     * and umbrella states cover sibling violations; those stacks' duplicates reach {@link
     * #DUPLICATE_KEY_VENDOR_CODES} under {@code 23000} instead. Should such a driver ever
     * surface, the remedy is widening that gate to its class-23 state, not reinstating the
     * umbrella.
     */
    private static final Set<String> UNIQUE_SQL_STATES = Set.of("23505");

    /**
     * Duplicate-key vendor codes under the generic integrity SQLState {@code 23000}, where
     * the state alone cannot tell a duplicate from sibling violations: MySQL / MariaDB /
     * TiDB / OceanBase {@code 1062}, MySQL 5.5-era {@code 1022}, Oracle {@code ORA-00001}
     * = 1, SQL Server {@code 2601} / {@code 2627}.
     */
    private static final Set<Integer> DUPLICATE_KEY_VENDOR_CODES =
            Set.of(1062, 1022, 1, 2601, 2627);

    /** The data source holding the skill tables; never closed by this repository. */
    private final DataSource dataSource;

    /** The skill table domain supplying the skills table SQL. */
    private final SkillDialect skillDialect;

    /** The skill-resources table domain supplying the resources table SQL. */
    private final SkillResourcesDialect skillResourcesDialect;

    /** Whether write operations are allowed; toggled via {@link #setWriteable(boolean)}. */
    private volatile boolean writeable;

    /**
     * Creates a repository over tables already created and validated by
     * {@code AbstractJdbcDialect.from(dataSource).enableSkillTables(true).build()} — like
     * every other component in this module, the constructor never touches the schema.
     *
     * @param dataSource the JDBC data source
     * @param dialect the assembled dialect providing the skill table dialects
     */
    public JdbcAgentSkillRepository(DataSource dataSource, AbstractJdbcDialect dialect) {
        this(dataSource, dialect, true);
    }

    /**
     * Creates a repository with the writeable flag.
     *
     * @param dataSource the JDBC data source
     * @param dialect the assembled dialect providing the skill table dialects
     * @param writeable whether write operations are allowed
     * @throws IllegalArgumentException when {@code dataSource} or {@code dialect} is null
     * @throws IllegalStateException when the skill table group was not enabled at build
     */
    public JdbcAgentSkillRepository(
            DataSource dataSource, AbstractJdbcDialect dialect, boolean writeable) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
        requireNonNull(dialect, "dialect");
        // The aggregate is needed only for the group-enabled check below; CRUD runs on the
        // two table domains, like JdbcStore on StoreDialect.
        this.skillDialect = dialect;
        this.skillResourcesDialect = dialect;
        this.writeable = writeable;
        requireSkillTablesEnabled(dialect);
        LOG.info(
                "JdbcAgentSkillRepository initialized: skills table '{}', resources table '{}'",
                skillDialect.skillTableName(),
                skillResourcesDialect.skillResourcesTableName());
    }

    @Override
    public AgentSkill getSkill(String name) {
        validateSkillName(name);
        try (Connection conn = dataSource.getConnection()) {
            LoadedSkillRecord row =
                    query(
                            conn,
                            skillDialect.skillSelectByName(name),
                            rs -> {
                                if (!rs.next()) {
                                    throw new IllegalArgumentException("Skill not found: " + name);
                                }
                                return LoadedSkillRecord.fromResultSet(rs);
                            });
            Map<String, String> resources = loadResourcesBySkillId(conn, row.id);
            return buildSkill(
                    name,
                    row.description,
                    row.skillContent,
                    row.source,
                    row.metadataJson,
                    resources);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load skill: " + name, e);
        }
    }

    /**
     * Lists the stored names, skipping (with a warning) rows whose name fails the same
     * read-side validation as {@link #buildSkill} — such a name could never be loaded or
     * deleted, because {@code getSkill} and {@code delete} validate first.
     */
    @Override
    public List<String> getAllSkillNames() {
        try (Connection conn = dataSource.getConnection()) {
            List<String> stored = new ArrayList<>();
            forEachRow(
                    conn,
                    skillDialect.skillSelectAllNames(),
                    rs -> stored.add(rs.getString("name")));
            return filterUnloadableNames(stored);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list skill names", e);
        }
    }

    /**
     * Drops names failing the read-side validation, warning per name and once in aggregate —
     * a partial listing must stay visible as such.
     */
    private static List<String> filterUnloadableNames(List<String> stored) {
        List<String> names = new ArrayList<>(stored.size());
        for (String name : stored) {
            try {
                validateSkillName(name);
            } catch (IllegalArgumentException e) {
                LOG.warn("Skipping unloadable skill name '{}': {}", name, e.getMessage());
                continue;
            }
            names.add(name);
        }
        if (names.size() < stored.size()) {
            LOG.warn(
                    "Skill name listing is partial: {} names omitted",
                    stored.size() - names.size());
        }
        return names;
    }

    /**
     * Loads the whole catalog with every skill's full resources — peak memory is O(total
     * resource bytes), so large catalogs belong behind {@link #getSkill(String)} until a
     * streaming variant exists (tracked separately).
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        try (Connection conn = dataSource.getConnection()) {
            Map<Long, LoadedSkillRecord> records = loadSkillRecords(conn);
            stitchResources(conn, records);
            return buildAll(records);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load all skills", e);
        }
    }

    /**
     * Loads all skill rows ordered by name. A {@link LinkedHashMap} keeps that order while
     * resources are stitched on separately.
     */
    private Map<Long, LoadedSkillRecord> loadSkillRecords(Connection conn) throws SQLException {
        Map<Long, LoadedSkillRecord> records = new LinkedHashMap<>();
        forEachRow(
                conn,
                skillDialect.skillSelectAll(),
                rs -> {
                    LoadedSkillRecord record = LoadedSkillRecord.fromResultSet(rs);
                    records.put(record.id, record);
                });
        return records;
    }

    /** Attaches every resource row to its skill; orphaned rows are logged and skipped. */
    private void stitchResources(Connection conn, Map<Long, LoadedSkillRecord> records)
            throws SQLException {
        forEachRow(
                conn,
                skillResourcesDialect.skillResourcesSelectAll(),
                rs -> {
                    long skillId = rs.getLong("id");
                    LoadedSkillRecord record = records.get(skillId);
                    if (record != null) {
                        record.resources.put(
                                rs.getString("resource_path"), rs.getString("resource_content"));
                    } else {
                        LOG.warn("Found orphaned resource for non-existent id: {}", skillId);
                    }
                });
    }

    /** Builds the skills, skipping (with a warning) any row the model rejects. */
    private List<AgentSkill> buildAll(Map<Long, LoadedSkillRecord> records) {
        List<AgentSkill> skills = new ArrayList<>(records.size());
        int omitted = 0;
        for (LoadedSkillRecord record : records.values()) {
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
                omitted++;
                LOG.warn("Failed to build skill '{}': {}", record.name, e.getMessage(), e);
            }
        }
        if (omitted > 0) {
            LOG.warn(
                    "Skill catalog listing is partial: {} of {} skills omitted",
                    omitted,
                    records.size());
        }
        return skills;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        if (!writeable) {
            LOG.warn("Cannot save skills: repository is read-only");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            validateForSave(skills);
            if (!force) {
                requireNoConflicts(conn, skills);
            }

            return runInTransaction(
                    conn,
                    c -> {
                        for (AgentSkill skill : skills) {
                            // With force=false the preceding delete is skipped on purpose: a
                            // concurrent save landing the same name between the pre-check and
                            // this insert must fail on the UNIQUE constraint, not be
                            // silently overwritten.
                            if (force && skillExistsInternal(c, skill.getName())) {
                                deleteSkillInternal(c, skill.getName());
                                LOG.debug(
                                        "Deleted existing skill for overwrite: {}",
                                        skill.getName());
                            }
                            long skillId = insertSkill(c, skill, force);
                            insertResources(c, skillId, skill.getResources());
                            LOG.info(
                                    "Successfully saved skill: {} (id={})",
                                    skill.getName(),
                                    skillId);
                        }
                        return true;
                    });
        } catch (SQLException e) {
            LOG.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            LOG.warn("Cannot delete skill: repository is read-only");
            return false;
        }
        validateSkillName(skillName);

        try (Connection conn = dataSource.getConnection()) {
            if (!skillExistsInternal(conn, skillName)) {
                LOG.warn("Skill does not exist: {}", skillName);
                return false;
            }
            runInTransaction(
                    conn,
                    c -> {
                        deleteSkillInternal(c, skillName);
                        return true;
                    });
            LOG.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (SQLException e) {
            LOG.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
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

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                REPOSITORY_TYPE, skillDialect.skillTableName(), writeable);
    }

    @Override
    public String getSource() {
        return REPOSITORY_TYPE + "_" + skillDialect.skillTableName();
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
        // The DataSource is managed externally, nothing to release here.
        LOG.debug("JdbcAgentSkillRepository closed");
    }

    /**
     * Deletes every skill and its resources.
     *
     * @return the number of deleted skill rows
     */
    public int clearAllSkills() {
        if (!writeable) {
            LOG.warn("Cannot clear skills: repository is read-only");
            return 0;
        }
        try (Connection conn = dataSource.getConnection()) {
            int deleted =
                    runInTransaction(
                            conn,
                            c -> {
                                executeUpdate(c, skillResourcesDialect.skillResourcesDeleteAll());
                                return executeUpdate(c, skillDialect.skillDeleteAll());
                            });
            LOG.info("Cleared all skills, {} skills deleted", deleted);
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear skills", e);
        }
    }

    // ------------------------------------------------------------------
    //  Configuration guard
    // ------------------------------------------------------------------

    /**
     * Fails fast on the base-without-skill misconfiguration: a dialect whose skill group is
     * disabled did no skill schema work, so the skill channel would fail at first use.
     *
     * @throws IllegalStateException when the skill table group was not enabled at build
     */
    private static void requireSkillTablesEnabled(AbstractJdbcDialect dialect) {
        if (!dialect.isSkillTablesEnabled()) {
            throw new IllegalStateException(
                    "The skill tables group ("
                            + dialect.skillTableName()
                            + " / "
                            + dialect.skillResourcesTableName()
                            + ") is not enabled on this dialect. Enable it with"
                            + " enableSkillTables(true) on AbstractJdbcDialect.from(dataSource)"
                            + " before building the dialect.");
        }
    }

    // ------------------------------------------------------------------
    //  Save-path helpers
    // ------------------------------------------------------------------

    /**
     * Validates every skill name, resource path, and resource content before any
     * transaction starts. The other NOT NULL columns need no check: the {@code AgentSkill}
     * constructor guarantees them non-null — resource values are the only nullable field
     * that can reach the driver.
     *
     * @throws IllegalArgumentException on a null skill, an invalid name/resource path, or a
     *     null resource content (the column is NOT NULL in every vendor DDL)
     */
    private static void validateForSave(List<AgentSkill> skills) {
        for (AgentSkill skill : skills) {
            if (skill == null) {
                throw new IllegalArgumentException("Skills to save must not contain null elements");
            }
            validateSkillName(skill.getName());
            Map<String, String> resources = skill.getResources();
            if (resources != null) {
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    validateResourcePath(entry.getKey());
                    if (entry.getValue() == null) {
                        throw new IllegalArgumentException(
                                "Resource content must not be null: " + entry.getKey());
                    }
                }
            }
        }
    }

    /**
     * Fast-fail pre-check when {@code force=false}, listing every conflicting name before
     * any write starts. Advisory only: the authoritative guarantee is the {@code name}
     * UNIQUE constraint on the in-transaction insert (see {@link #insertSkill}), which
     * closes the window a concurrent writer opens between this check and the save.
     *
     * @throws IllegalStateException listing all conflicting skill names
     */
    private void requireNoConflicts(Connection conn, List<AgentSkill> skills) throws SQLException {
        List<String> existing = new ArrayList<>();
        for (AgentSkill skill : skills) {
            if (skillExistsInternal(conn, skill.getName())) {
                existing.add(skill.getName());
            }
        }
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot save skills: the following skills already exist and force=false: "
                            + String.join(", ", existing)
                            + ". Use force=true to overwrite existing skills.");
        }
    }

    /**
     * Inserts one skill row and returns its generated id. With {@code force=false} the row
     * is inserted without a preceding delete, so the {@code name} UNIQUE constraint is the
     * authoritative conflict check: a name landed between the pre-check and this insert
     * surfaces as the same {@link IllegalStateException} the pre-check raises, instead of
     * being silently overwritten.
     *
     * @throws SQLException when the insert or key retrieval fails
     * @throws IllegalStateException when {@code force=false} and the name already exists
     */
    private long insertSkill(Connection conn, AgentSkill skill, boolean force) throws SQLException {
        BoundSql bound =
                skillDialect.skillInsert(
                        skill.getName(),
                        skill.getDescription(),
                        skill.getSkillContent(),
                        skill.getSource(),
                        serializeMetadata(skill.getMetadata()));
        try (PreparedStatement stmt =
                conn.prepareStatement(bound.sql(), Statement.RETURN_GENERATED_KEYS)) {
            bindParams(stmt, bound.params());
            stmt.executeUpdate();
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
                throw new SQLException("Failed to get generated id for skill: " + skill.getName());
            }
        } catch (SQLException e) {
            if (!force && isUniqueViolation(e)) {
                throw new IllegalStateException(
                        "Cannot save skills: the following skills already exist and"
                                + " force=false: "
                                + skill.getName()
                                + ". Use force=true to overwrite existing skills.",
                        e);
            }
            throw e;
        }
    }

    /**
     * Batch-inserts a skill's resources in one round-trip. Each row comes from the dialect
     * as a {@link BoundSql}, so the statement and its params travel together — no
     * positional placeholder contract lives in this class; all rows share one statement
     * shape, prepared from the first row.
     *
     * @throws SQLException when any row fails to insert
     */
    private void insertResources(Connection conn, long skillId, Map<String, String> resources)
            throws SQLException {
        if (resources == null || resources.isEmpty()) {
            LOG.debug("No resources to insert for id: {}", skillId);
            return;
        }
        List<String> paths = new ArrayList<>(resources.keySet());
        String firstPath = paths.get(0);
        String insertSql =
                skillResourcesDialect
                        .skillResourcesInsert(skillId, firstPath, resources.get(firstPath))
                        .sql();
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (String path : paths) {
                BoundSql row =
                        skillResourcesDialect.skillResourcesInsert(
                                skillId, path, resources.get(path));
                bindParams(stmt, row.params());
                stmt.addBatch();
            }
            int[] results = stmt.executeBatch();
            int insertedCount = 0;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > 0 || results[i] == Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                } else {
                    // A conforming driver returns one result per command; the guard keeps a
                    // misbehaving one from turning the log line into an IndexOutOfBounds.
                    String path = i < paths.size() ? paths.get(i) : "<unknown>";
                    LOG.error(
                            "Failed to insert resource '{}' for id '{}': batch result {}",
                            path,
                            skillId,
                            results[i]);
                }
            }
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

    // ------------------------------------------------------------------
    //  Delete-path helpers
    // ------------------------------------------------------------------

    /**
     * Deletes a skill and its resources. Resources are deleted explicitly for portability —
     * SQLite only enforces the cascading FK with {@code PRAGMA foreign_keys} on — so the
     * cascade stays a second line of defense.
     */
    private void deleteSkillInternal(Connection conn, String skillName) throws SQLException {
        Long skillId =
                query(
                        conn,
                        skillDialect.skillSelectIdByName(skillName),
                        rs -> rs.next() ? rs.getLong("id") : null);
        if (skillId != null) {
            executeUpdate(conn, skillResourcesDialect.skillResourcesDeleteBySkillId(skillId));
        }
        executeUpdate(conn, skillDialect.skillDeleteByName(skillName));
    }

    /** Runs an update-type {@link BoundSql}, returning the affected-row count. */
    private int executeUpdate(Connection conn, BoundSql bound) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(bound.sql())) {
            bindParams(ps, bound.params());
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Read-path helpers
    // ------------------------------------------------------------------

    /** Loads one skill's resources keyed by path. */
    private Map<String, String> loadResourcesBySkillId(Connection conn, long skillId)
            throws SQLException {
        Map<String, String> resources = new HashMap<>();
        forEachRow(
                conn,
                skillResourcesDialect.skillResourcesSelectBySkillId(skillId),
                rs ->
                        resources.put(
                                rs.getString("resource_path"), rs.getString("resource_content")));
        return resources;
    }

    /** Existence probe on an open connection. */
    private boolean skillExistsInternal(Connection conn, String skillName) throws SQLException {
        return query(conn, skillDialect.skillExists(skillName), ResultSet::next);
    }

    /**
     * Builds an {@link AgentSkill} from row data, restoring full metadata when available and
     * otherwise falling back to the legacy core columns.
     *
     * <p>Rows are re-validated on read — legacy tables were never path-checked on write, and
     * consumers resolve these values onto disk. {@code getSkill} surfaces the rejection;
     * {@code getAllSkills} skips the row and {@code getAllSkillNames} omits the name, both
     * with a warning, as they do for unbuildable rows.
     *
     * @throws IllegalArgumentException when the row's name or a resource path fails the same
     *     validation as the save path
     */
    private AgentSkill buildSkill(
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson,
            Map<String, String> resources) {
        validateSkillName(name);
        if (resources != null) {
            for (String path : resources.keySet()) {
                validateResourcePath(path);
            }
        }
        return new AgentSkill(
                deserializeMetadata(metadataJson, name, description),
                skillContent,
                resources,
                source);
    }

    /**
     * Deserializes {@code metadata_json} when present, then overlays the authoritative SQL
     * columns for name and description.
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
                LOG.warn(
                        "Failed to deserialize metadata_json for skill '{}', falling back to"
                                + " core metadata",
                        name,
                        e);
            }
        }
        metadata.put("name", name);
        metadata.put("description", description);
        return metadata;
    }

    /** Serializes the complete skill metadata tree for {@code metadata_json}. */
    private String serializeMetadata(Map<String, Object> metadata) {
        return JsonUtils.getJsonCodec().toJson(metadata);
    }

    // ------------------------------------------------------------------
    //  Validation and JDBC utilities
    // ------------------------------------------------------------------

    /**
     * Validates a skill name.
     *
     * @throws IllegalArgumentException when null/empty, over-length, or containing path
     *     separators or traversal sequences
     */
    private static void validateSkillName(String skillName) {
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        if (skillName.length() > MAX_SKILL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill name cannot exceed " + MAX_SKILL_NAME_LENGTH + " characters");
        }
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) {
            throw new IllegalArgumentException("Skill name cannot contain path separators or '..'");
        }
    }

    /**
     * Validates a resource path. Beyond the legacy null/empty/length checks, absolute paths
     * and {@code ..} segments are rejected: skill content can come from untrusted sources and
     * the path is later resolved onto disk, where it would escape the skill directory.
     * Ordinary relative sub-paths such as {@code docs/readme.md} stay valid.
     *
     * @throws IllegalArgumentException when null/empty, over-length, absolute, or traversing
     */
    private static void validateResourcePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path cannot be null or empty");
        }
        if (path.length() > MAX_RESOURCE_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "Resource path cannot exceed " + MAX_RESOURCE_PATH_LENGTH + " characters");
        }
        if (isAbsoluteOrTraversing(path)) {
            throw new IllegalArgumentException(
                    "Resource path must be relative and must not contain '..': " + path);
        }
    }

    /**
     * Whether the path is absolute (POSIX, Windows drive, or UNC) or contains a {@code ..}
     * segment.
     */
    private static boolean isAbsoluteOrTraversing(String path) {
        if (path.startsWith("/")
                || path.startsWith("\\")
                || DRIVE_LETTER_PREFIX.matcher(path).matches()) {
            return true;
        }
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a select through the shared prepare/bind/execute boilerplate; the processor owns
     * the cursor, so single-row reads and full scans share one code path.
     */
    private static <T> T query(Connection conn, BoundSql bound, ResultSetProcessor<T> processor)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(bound.sql())) {
            bindParams(ps, bound.params());
            try (ResultSet rs = ps.executeQuery()) {
                return processor.process(rs);
            }
        }
    }

    /** Feeds every row of a select to {@code handler} via {@link #query}. */
    private static void forEachRow(Connection conn, BoundSql bound, RowHandler handler)
            throws SQLException {
        query(
                conn,
                bound,
                rs -> {
                    while (rs.next()) {
                        handler.handle(rs);
                    }
                    return null;
                });
    }

    /**
     * Runs {@code work} in one transaction: auto-commit is saved and restored, success
     * commits, and any failure rolls back before the original exception is rethrown.
     */
    private static <T> T runInTransaction(Connection conn, TransactionalWork<T> work)
            throws SQLException {
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = work.execute(conn);
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(conn, e);
            throw e;
        } finally {
            restoreAutoCommit(conn, originalAutoCommit);
        }
    }

    /** Binds {@link BoundSql} parameters in placeholder order. */
    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * Whether the exception reports a duplicate-key / unique-constraint violation — the
     * authoritative conflict signal for the {@code force=false} insert. Only positive
     * duplicate signals match, so any other constraint failure surfaces as the driver's own
     * {@link SQLException} instead of the destructive "use force=true" advice.
     *
     * <p>Signals, in order:
     *
     * <ol>
     *   <li>SQLState in {@link #UNIQUE_SQL_STATES} — the portable unique-violation codes.
     *       pgjdbc and its forks never throw the JDBC 4 subtypes (issue #963 is still open),
     *       nor do Firebird, SQL Server, 达梦, or H2 1.x; all report 23505, as do H2 2.x,
     *       HSQLDB, Derby, and DB2 through the subtype. pgjdbc returns error code 0, so the
     *       code cannot stand in for the state.
     *   <li>SQLState {@code 23000} plus a code from {@link #DUPLICATE_KEY_VENDOR_CODES} —
     *       that state is the whole class-23 umbrella for the MySQL family, Oracle, and SQL
     *       Server, so the vendor code decides.
     *   <li>SQLite (xerial): error 19 with a null state is every {@code
     *       SQLITE_CONSTRAINT_*} collapsed; {@link #isSqliteUniqueViolation} narrows it.
     *   <li>Vendor codes for drivers reporting no SQLState: {@link
     *       #VENDOR_UNIQUE_VIOLATION_CODES}.
     * </ol>
     *
     * <p>Deliberately not matched: the {@link SQLIntegrityConstraintViolationException}
     * subtype on its own — MySQL Connector/J also raises it for NOT NULL (1048) and foreign
     * key (1451/1452) violations, which is exactly the misreport this method must avoid —
     * and the class-23 siblings (23502, 23503, 23514). An unmatched duplicate fails the
     * save as a plain {@code SQLException} rather than a false conflict.
     *
     * <p>Also not matched: MySQL {@code ER_DUP_ENTRY_AUTOINCREMENT_CASE} (1569) reports
     * SQLState {@code HY000} — outside class 23 — but it requires an explicit auto-increment
     * value, which the skill insert never supplies. ClickHouse has no duplicate-key error to
     * match: it does not enforce primary-key uniqueness.
     *
     * @param e the exception thrown by the skill insert
     * @return true when the failure is a duplicate-key conflict rather than any other error
     */
    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        // Null SQLState is real (SQLite reports one) and Set.of rejects null lookups.
        if (state != null && UNIQUE_SQL_STATES.contains(state)) {
            return true;
        }
        if ("23000".equals(state) && DUPLICATE_KEY_VENDOR_CODES.contains(e.getErrorCode())) {
            return true;
        }
        if (e.getErrorCode() == SQLITE_CONSTRAINT
                && e.getClass().getName().startsWith("org.sqlite.")) {
            return isSqliteUniqueViolation(e);
        }
        return VENDOR_UNIQUE_VIOLATION_CODES.contains(e.getErrorCode());
    }

    /**
     * Narrows SQLite's umbrella error 19 to unique violations via the driver's {@code
     * getResultCode()}, reached reflectively to keep the driver off the compile classpath.
     * Compares the enum's {@code name()}, not {@code toString()} — the latter renders the
     * full message and never equals the bare constant (sqlite-jdbc 3.47.1.0). Falls back to
     * the umbrella signal if reflection fails.
     */
    private static boolean isSqliteUniqueViolation(SQLException e) {
        try {
            Object resultCode = e.getClass().getMethod("getResultCode").invoke(e);
            return resultCode instanceof Enum<?>
                    && "SQLITE_CONSTRAINT_UNIQUE".equals(((Enum<?>) resultCode).name());
        } catch (ReflectiveOperationException reflectionFailure) {
            return true;
        }
    }

    /**
     * Rolls back, attaching a rollback failure as suppressed so it cannot mask the original
     * error that caused the rollback.
     */
    private static void rollbackQuietly(Connection conn, Exception failure) {
        try {
            conn.rollback();
        } catch (SQLException rollbackException) {
            failure.addSuppressed(rollbackException);
        }
    }

    /**
     * Restores the connection's auto-commit mode to what it was before the transaction,
     * swallowing failures so they cannot mask the original error.
     */
    private static void restoreAutoCommit(Connection conn, boolean originalAutoCommit) {
        try {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            LOG.warn("Failed to restore auto-commit mode on connection", e);
        }
    }

    /** Rejects a null argument with the parameter name in the message. */
    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    /** Result-set callback owning the cursor for {@code query}'s single-row or full reads. */
    @FunctionalInterface
    private interface ResultSetProcessor<T> {
        T process(ResultSet rs) throws SQLException;
    }

    /** Per-row callback for {@code forEachRow}. */
    @FunctionalInterface
    private interface RowHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    /** Transactional unit of work for {@code runInTransaction}. */
    @FunctionalInterface
    private interface TransactionalWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    /** Mutable holder stitching skills and resources read from separate result sets. */
    private static final class LoadedSkillRecord {

        private final long id;
        private final String name;
        private final String description;
        private final String skillContent;
        private final String source;
        private final String metadataJson;
        private final Map<String, String> resources = new HashMap<>();

        /** Holds one skill row until its resources are stitched on. */
        private LoadedSkillRecord(
                long id,
                String name,
                String description,
                String skillContent,
                String source,
                String metadataJson) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.skillContent = skillContent;
            this.source = source;
            this.metadataJson = metadataJson;
        }

        /** Maps the shared skill projection (id through metadata_json) onto one record. */
        private static LoadedSkillRecord fromResultSet(ResultSet rs) throws SQLException {
            return new LoadedSkillRecord(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("skill_content"),
                    rs.getString("source"),
                    rs.getString("metadata_json"));
        }
    }
}
