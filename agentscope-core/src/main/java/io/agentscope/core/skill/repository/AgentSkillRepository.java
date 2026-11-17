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
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository interface for AgentSkill persistence operations.
 *
 * <p>This interface follows the Repository Pattern and Dependency Inversion Principle,
 * allowing different storage stores (filesystem, database, remote APIs, etc.) to
 * be used interchangeably.
 *
 * <h2>Scope isolation — required of every implementation</h2>
 *
 * <p>A developer must be able to hand different agents or different users their own
 * isolated skill repository, easily and by construction. Skill names are human-chosen;
 * the same name from two scopes is the normal case, not a collision. Each repository
 * instance addresses exactly one scope, and every implementation must guarantee:
 *
 * <ul>
 *   <li>the same skill name in two scopes coexists — never a global unique constraint;
 *   <li>reads, overwrites (including {@code force}), and deletes stay inside the
 *       instance's scope, never touching another scope's same-name skill;
 *   <li>a scope the instance cannot address is rejected explicitly, never silently
 *       redirected to another scope.
 * </ul>
 *
 * <p>How a scope is expressed is backend-native and deliberately not standardized here.
 * Most storages already offer a natural handle, and constructing one repository per
 * handle value is the intended usage:
 *
 * <ul>
 *   <li>file-backed repositories (FileSystem, Classpath): the configured base directory
 *       or resource path — one path per scope;
 *   <li>Workspace: the per-user directory chosen by the runtime context;
 *   <li>Nacos: the native namespace id;
 *   <li>Git: the repository, branch, or in-repo skills root;
 *   <li>relational databases: {@code JdbcAgentSkillRepository} binds one namespace per
 *       instance at construction, backed by an explicit {@code namespace} column.
 * </ul>
 *
 * <p>An application needing several scopes at once registers one repository per scope
 * and composes them (for example by chaining {@code skillRepository(...)} on the agent
 * builder) instead of querying one repository across scopes.
 *
 * <h2>Version semantics</h2>
 *
 * <p>A version token identifies the skill's complete file set: <b>the same token always
 * means the same bytes</b>. Publishing adds a new version and moves the latest pointer; a
 * published version's content is never rewritten. The token itself is backend-native — a
 * declared label ({@code 1.0.0}), a write-time column (an {@code updated_at} timestamp),
 * or a hash of the complete file set — any form is valid as long as an unchanged skill
 * keeps stamping the same token. Read-time file metadata (mtime, size) does not qualify.
 *
 * <p>A version-capable repository stamps every {@link AgentSkill} it returns from {@link
 * #getSkill(String, String)} — including latest pulls — with the resolved
 * version, binding one loaded instance to one version. A latest pull should stay cheap:
 * caching backends resolve a change signal (a HEAD sha, an {@code updated_at}) and serve
 * an unchanged skill from a version-keyed local materialization tree as a pure local
 * read. That tree is shared, operator-managed state: it may be deleted out of band at any
 * time (a miss is simply re-pulled, never an error), and {@link #close()} must not delete
 * it — reclamation belongs to the operator.
 *
 * <p>Example usage:
 * <pre>{@code
 * AgentSkillRepository repo = new GitSkillRepository("https://github.com/org/skills.git");
 * AgentSkill skill = repo.getSkill("calculate", null); // latest, stamped + materialized
 * }</pre>
 *
 */
public interface AgentSkillRepository extends AutoCloseable {

    /**
     * Lists the names of all skills in the repository — a bulk inspection primitive, not a
     * serving primitive.
     *
     * <p>Implementations must answer from a name-only read (SQL or directory walk) and
     * must not fetch skill content or resources.
     *
     * @return List of all skill names (never null, may be empty)
     * @deprecated since 2.1.0 — enumeration is not part of the serving contract. No agent
     *     requirement justifies implicitly owning a whole repository, so serving paths must
     *     bound their work by explicit selection (the agent's {@code SkillFilter}) or
     *     on-demand lookup by name — never by enumerating the repository, whose size is
     *     unknown to the caller. Deliberate bulk consumers (export, bundling, management
     *     UIs) may keep using this while it exists; a management-facing replacement lands
     *     before removal.
     */
    @Deprecated(since = "2.1.0", forRemoval = true)
    List<String> getAllSkillNames();

    /**
     * Gets all skills from the repository: every name from {@link #getAllSkillNames()},
     * each served by a latest pull. Implementations may override this while a native bulk
     * read is cheaper. Returned instances are summaries and carry neither the SKILL.md body
     * nor resource content in memory — descriptor form, see {@link
     * #getSkill(String, String)}.
     *
     * @return List of all skills (never null, may be empty)
     * @deprecated since 2.1.0 — a bulk content pull with an N+1 shape by construction and
     *     no version coherence as a set; serving paths must not enumerate repositories (see
     *     {@link #getAllSkillNames()}). Deliberate bulk consumers may keep using this while
     *     it exists.
     */
    @Deprecated(since = "2.1.0", forRemoval = true)
    default List<AgentSkill> getAllSkills() {
        List<AgentSkill> skills = new ArrayList<>(0);
        for (String name : getAllSkillNames()) {
            AgentSkill skill = getSkill(name, null);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    // ==================== Skill-level version contract ====================

    /**
     * Returns the named skill at the given version reference — the serving primitive.
     * {@code null} or blank {@code version} means latest; any other value means exactly
     * that version.
     *
     * <p>A version-capable implementation stamps the returned instance with the resolved
     * version — for a latest pull, the current version — via {@link
     * AgentSkill#getVersion()}; an unstamped return declares that the backend has no
     * version capability.
     *
     * <p>The returned instance is a summary: the values in its resources map are empty
     * (path keys may be listed) and {@link AgentSkill#getOriginDir()} points at the
     * skill's materialized directory, where SKILL.md and all resources already live.
     * The SKILL.md body is likewise not carried — {@link AgentSkill#getSkillContent()}
     * is {@code null}; body and resource bytes are both read on demand, the body through
     * the load tool and resources through {@code AgentSkill#getResource(path)}, each
     * resolving against {@code originDir}.
     *
     * <p>With a non-null {@code version}, the returned content must be exactly what that
     * version addresses, carrying the requested version as its stamp — never the latest
     * served silently in its place. When the reference cannot be resolved (unknown,
     * deleted, or a content-hash version whose content has changed), return {@code null};
     * falling back to the latest is a caller-side policy decision.
     *
     * @param name the skill name to read
     * @param version the version reference ({@code null}/blank = latest), in the backend's
     *     native form
     * @return the skill summary at that version reference, or {@code null} when the skill
     *     or the reference cannot be resolved
     */
    AgentSkill getSkill(String name, String version);

    /**
     * Gets a skill by its name — the latest-pull convenience for {@link
     * #getSkill(String, String)} with a {@code null} version.
     *
     * @param name The skill name
     * @return The skill matching the name at its current version, or {@code null} when no
     *     such skill exists
     */
    default AgentSkill getSkill(String name) {
        return getSkill(name, null);
    }

    /**
     * Saves or updates a skill in the repository.
     *
     * <p>If a skill with the same name exists, it will be updated.
     * Otherwise, a new skill will be created.
     * <p>If the skills list is empty, return false.
     *
     * @param skills The skills to save
     * @param force Whether to force save even if the skill already exists
     * @return {@code true} if save succeeded, {@code false} otherwise
     */
    boolean save(List<AgentSkill> skills, boolean force);

    /**
     * Deletes a skill by its skill name.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if deletion succeeded, {@code false} if skill not found
     */
    boolean delete(String skillName);

    /**
     * Checks if a skill exists in the repository.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if the skill exists, {@code false} otherwise
     */
    boolean skillExists(String skillName);

    /**
     * Gets metadata about this repository.
     *
     * <p>The information includes repository type, location, and other metadata.
     *
     * @return Repository information (never null)
     */
    AgentSkillRepositoryInfo getRepositoryInfo();

    /**
     * Gets the source identifier of this repository.
     *
     * <p>The source follows the format {@code repositoryType_location}.
     *
     * @return The source identifier (never null)
     */
    String getSource();

    /**
     * Sets the writeable flag for this repository.
     *
     * @param writeable Whether the repository supports write operations
     */
    void setWriteable(boolean writeable);

    /**
     * Checks if the repository supports write operations.
     *
     * @return {@code true} if writable, {@code false} otherwise
     */
    boolean isWriteable();

    /**
     * Cleans up any resources used by this repository.
     *
     * <p>Implementations should override this method if they need to release resources
     * such as network connections, file handles, or caches.
     *
     * <p>Closing a repository is the responsibility of the code that constructed it.
     * Repositories are routinely shared across agents and sessions, so middleware and
     * other framework code must not close a repository it did not construct.
     *
     * <p>A caching backend's materialization tree is shared state: {@code close()}
     * releases only the repository's own resources and must not delete that tree (see
     * the class-level version semantics).
     */
    @Override
    default void close() {
        // Default implementation does nothing
    }
}
