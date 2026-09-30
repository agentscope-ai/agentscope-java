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
 * Table-domain dialect interface for the skill table.
 *
 * <p>One row per skill: the lookup columns ({@code name}, {@code description}), content,
 * source, and the metadata tree in {@code metadata_json}. The structure mirrors the
 * deprecated skill mysql/postgresql modules; {@code metadata_json} is required — a legacy
 * table without it fails schema validation with the reference DDL.
 *
 * <p>Method names are prefixed with {@code skill}; all business SQL is ANSI-standard, so
 * vendors override only the create-table DDL.
 *
 * @author shanhongyu
 */
public interface SkillDialect {

    /** Base table name (without prefix). */
    default String skillTableName() {
        return "skills";
    }

    /** DDL statements (one or more) to create the skill table. Must be idempotent. */
    List<String> skillCreateTableDdls();

    /** SELECT of one skill by name. Projection includes {@code metadata_json}. */
    default BoundSql skillSelectByName(String name) {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " WHERE name = ?",
                name);
    }

    /** SELECT of all skills ordered by name. Projection includes {@code metadata_json}. */
    default BoundSql skillSelectAll() {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " ORDER BY name");
    }

    /** SELECT of all skill names ordered by name. Projection: (name). */
    default BoundSql skillSelectAllNames() {
        return new BoundSql("SELECT name FROM " + skillTableName() + " ORDER BY name");
    }

    /** Existence probe for one skill name; {@code name} is UNIQUE, so no {@code LIMIT} is needed. */
    default BoundSql skillExists(String skillName) {
        return new BoundSql("SELECT 1 FROM " + skillTableName() + " WHERE name = ?", skillName);
    }

    /** SELECT of one skill's id by name. Projection: (id). */
    default BoundSql skillSelectIdByName(String skillName) {
        return new BoundSql("SELECT id FROM " + skillTableName() + " WHERE name = ?", skillName);
    }

    /**
     * INSERT of one skill row; callers prepare it with
     * {@link java.sql.Statement#RETURN_GENERATED_KEYS} to read the auto-increment id.
     */
    default BoundSql skillInsert(
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson) {
        return new BoundSql(
                "INSERT INTO "
                        + skillTableName()
                        + " (name, description, skill_content, source, metadata_json)"
                        + " VALUES (?, ?, ?, ?, ?)",
                name,
                description,
                skillContent,
                source,
                metadataJson);
    }

    /** DELETE of one skill by name. */
    default BoundSql skillDeleteByName(String skillName) {
        return new BoundSql("DELETE FROM " + skillTableName() + " WHERE name = ?", skillName);
    }

    /** DELETE of every skill row. */
    default BoundSql skillDeleteAll() {
        return new BoundSql("DELETE FROM " + skillTableName());
    }
}
