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
 * <p>One row per skill: the lookup columns ({@code namespace}, {@code name}), content,
 * source, and the metadata tree in {@code metadata_json}. The structure mirrors the
 * deprecated skill mysql/postgresql modules; {@code metadata_json} is required — a legacy
 * table without it fails schema validation with the reference DDL.
 *
 * <p>The namespace column partitions names: {@code UNIQUE(namespace, name)} allows the
 * same skill name once per namespace. Every statement addresses exactly one namespace;
 * the column is {@code NOT NULL DEFAULT 'default'}: every row carries a concrete
 * namespace and vendor-specific NULL comparison semantics never apply. The column is
 * write-once per row: no statement updates it, so a skill never moves namespaces through
 * SQL generated here.
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

    /** SELECT of one skill by name within a namespace. Projection includes {@code metadata_json}. */
    default BoundSql skillSelectByName(String namespace, String name) {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " WHERE namespace = ? AND name = ?",
                namespace,
                name);
    }

    /** SELECT of all skills of one namespace ordered by name. Projection includes {@code metadata_json}. */
    default BoundSql skillSelectAll(String namespace) {
        return new BoundSql(
                "SELECT id, name, description, skill_content, source, metadata_json FROM "
                        + skillTableName()
                        + " WHERE namespace = ? ORDER BY name",
                namespace);
    }

    /** SELECT of all skill names of one namespace ordered by name. Projection: (name). */
    default BoundSql skillSelectAllNames(String namespace) {
        return new BoundSql(
                "SELECT name FROM " + skillTableName() + " WHERE namespace = ? ORDER BY name",
                namespace);
    }

    /**
     * Existence probe for one skill name within a namespace; {@code (namespace, name)} is
     * UNIQUE, so no {@code LIMIT} is needed.
     */
    default BoundSql skillExists(String namespace, String skillName) {
        return new BoundSql(
                "SELECT 1 FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /** SELECT of one skill's id by name within a namespace. Projection: (id). */
    default BoundSql skillSelectIdByName(String namespace, String skillName) {
        return new BoundSql(
                "SELECT id FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /**
     * INSERT of one skill row; callers prepare it with
     * {@link java.sql.Statement#RETURN_GENERATED_KEYS} to read the auto-increment id.
     */
    default BoundSql skillInsert(
            String namespace,
            String name,
            String description,
            String skillContent,
            String source,
            String metadataJson) {
        return new BoundSql(
                "INSERT INTO "
                        + skillTableName()
                        + " (namespace, name, description, skill_content, source, metadata_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                namespace,
                name,
                description,
                skillContent,
                source,
                metadataJson);
    }

    /** DELETE of one skill by name within a namespace. */
    default BoundSql skillDeleteByName(String namespace, String skillName) {
        return new BoundSql(
                "DELETE FROM " + skillTableName() + " WHERE namespace = ? AND name = ?",
                namespace,
                skillName);
    }

    /** DELETE of every skill row of one namespace. */
    default BoundSql skillDeleteAll(String namespace) {
        return new BoundSql("DELETE FROM " + skillTableName() + " WHERE namespace = ?", namespace);
    }
}
