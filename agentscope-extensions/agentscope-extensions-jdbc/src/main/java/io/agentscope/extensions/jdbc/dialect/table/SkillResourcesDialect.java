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
package io.agentscope.extensions.jdbc.dialect.table;

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import java.util.List;

/**
 * Table-domain dialect interface for the skill-resources table.
 *
 * <p>One row per skill resource, keyed by {@code (id, resource_path)} with a foreign key to
 * the skill table and {@code ON DELETE CASCADE}. The row carries the owning skill's
 * {@code namespace}, written in the same transaction, so bulk reads and deletes scope by
 * namespace without joining the skill table. Like the skill row's, that column is
 * write-once: no statement updates it, the two rows' values are always written together,
 * and the pair never diverges through this interface. This table must be created after
 * the skill table it references.
 *
 * <p>Method names are prefixed with {@code skillResources}; all business SQL is
 * ANSI-standard, so vendors override only the create-table DDL.
 *
 * @author shanhongyu
 */
public interface SkillResourcesDialect {

    /** Base table name (without prefix). */
    default String skillResourcesTableName() {
        return "skill_resources";
    }

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /** DDL statements (one or more) to create the skill-resources table. Must be idempotent. */
    List<String> skillResourcesCreateTableDdls();

    // ------------------------------------------------------------------
    //  Default — ANSI baseline
    // ------------------------------------------------------------------

    /**
     * INSERT of one resource row as a {@link BoundSql} — statement and params are assembled
     * together, so the column/placeholder order stays the dialect's single concern and no
     * caller binds positionally against a bare template. Callers batch many rows onto one
     * prepared statement, so a skill's resources insert in a single round-trip; every row of
     * one skill must share the same statement shape.
     */
    default BoundSql skillResourcesInsert(
            String namespace, long skillId, String resourcePath, String resourceContent) {
        return new BoundSql(
                "INSERT INTO "
                        + skillResourcesTableName()
                        + " (namespace, id, resource_path, resource_content)"
                        + " VALUES (?, ?, ?, ?)",
                namespace,
                skillId,
                resourcePath,
                resourceContent);
    }

    /** SELECT of every resource row of one namespace. Projection: (id, resource_path, resource_content). */
    default BoundSql skillResourcesSelectAll(String namespace) {
        return new BoundSql(
                "SELECT id, resource_path, resource_content FROM "
                        + skillResourcesTableName()
                        + " WHERE namespace = ?",
                namespace);
    }

    /** DELETE of every resource row of one namespace; pairs with {@link SkillDialect#skillDeleteAll(String)}. */
    default BoundSql skillResourcesDeleteAll(String namespace) {
        return new BoundSql(
                "DELETE FROM " + skillResourcesTableName() + " WHERE namespace = ?", namespace);
    }

    /** SELECT of one skill's resources. Projection: (resource_path, resource_content). */
    default BoundSql skillResourcesSelectBySkillId(long skillId) {
        return new BoundSql(
                "SELECT resource_path, resource_content FROM "
                        + skillResourcesTableName()
                        + " WHERE id = ?",
                skillId);
    }

    /**
     * DELETE of one skill's resource rows.
     *
     * <p>Rows also disappear through the foreign key's {@code ON DELETE CASCADE}, but SQLite
     * only enforces foreign keys when the caller enables {@code PRAGMA foreign_keys} per
     * connection, so repositories delete explicitly before deleting the owning skill row —
     * the cascade stays as a second line of defense.
     */
    default BoundSql skillResourcesDeleteBySkillId(long skillId) {
        return new BoundSql("DELETE FROM " + skillResourcesTableName() + " WHERE id = ?", skillId);
    }
}
