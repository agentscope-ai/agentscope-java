/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Represents an agent skill that can be loaded and used by agents.
 *
 * <p>A skill consists of:
 * <ul>
 *   <li>Name and description - identifying the skill
 *   <li>Skill content - the actual skill implementation or instructions
 *   <li>Resources - supporting files or data referenced by the skill
 *   <li>Version and source - tracking skill origin and versioning
 * </ul>
 *
 * <p><b>Creation options:</b>
 * <ul>
 *   <li>From markdown with YAML frontmatter - metadata extracted automatically
 *   <li>From explicit parameters - direct construction with all fields
 *   <li>From builder - for creating modified versions of existing skills
 * </ul>
 *
 * <p><b>Usage examples:</b>
 * <pre>{@code
 * // From markdown with frontmatter (use SkillUtil)
 * String skillMd = "---\nname: my_skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Direct construction
 * AgentSkill skill2 = new AgentSkill("my_skill", "Does something", "Content here", resources);
 *
 * // Create modified version using builder
 * AgentSkill modified = skill.toBuilder()
 *     .description("Updated description")
 *     .skillContent("Modified instructions")
 *     .addResource("config.json", "{\"key\": \"value\"}")
 *     .build();
 * }</pre>
 *
 * @see io.agentscope.core.skill.util.SkillUtil
 * @see io.agentscope.core.skill.util.MarkdownSkillParser
 * Manage markdown skill catalogs in application code.
 */
public class AgentSkill {

    /**
     * Single source of the skillId composition separator: {@code skillId = name + "__s__" +
     * source}. Neither the name nor the source may contain this separator (enforced at
     * construction), so the separator occurs in a well-formed skillId if and only if it is the
     * delimiter — grant entries can therefore distinguish "bare name" (source-unrestricted)
     * from "name__s__source" (repository-qualified) by simply looking for it.
     */
    public static final String SKILL_ID_SEPARATOR = "__s__";

    private final Map<String, Object> metadata;
    private final String skillContent;
    private final Map<String, String> resources;
    private final String source;

    /**
     * Optional absolute path to the skill's on-disk source directory. When present, the prompt
     * provider can emit a {@code <files-root>} per skill so the LLM can shell-execute scripts
     * directly without going through {@code uploadSkillFiles}; the load tool can also use it as
     * a disk-fallback when an in-memory resource is missing.
     */
    private final Path originDir;

    /**
     * Whether this is a system capability exempt from grant-layer (per-user capability)
     * trimming. Settable only via the Builder; public constructors default to {@code false}.
     */
    private final boolean system;

    /**
     * Creates an AgentSkill with explicit parameters.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. No source is set (see {@link #getSource()}).
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @throws IllegalArgumentException if name, description, or skillContent is null or empty
     */
    public AgentSkill(
            String name, String description, String skillContent, Map<String, String> resources) {
        this(name, description, skillContent, resources, null);
    }

    /**
     * Creates an AgentSkill with explicit parameters and custom source.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. The source parameter indicates where the skill originated from.
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill ({@code null} = no provenance)
     * @throws IllegalArgumentException if name, description, or skillContent is null or empty
     */
    public AgentSkill(
            String name,
            String description,
            String skillContent,
            Map<String, String> resources,
            String source) {
        this(createMetadata(name, description), skillContent, resources, source);
    }

    /**
     * Creates an AgentSkill with explicit metadata.
     *
     * <p>The metadata must include non-empty string values for {@code name} and
     * {@code description}. The metadata map is copied and stored as immutable.
     *
     * @param metadata Skill metadata including required {@code name} and {@code description}
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill ({@code null} = no provenance)
     * @throws IllegalArgumentException if metadata is invalid or skillContent is null or empty
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source) {
        this(metadata, skillContent, resources, source, null);
    }

    /**
     * Creates an AgentSkill with explicit metadata and an on-disk origin directory.
     *
     * @param metadata Skill metadata including required {@code name} and {@code description}
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill ({@code null} = no provenance)
     * @param originDir Absolute path to the skill's source directory, or {@code null} if not
     *                  filesystem-backed (e.g. classpath / remote / synthetic). Stored as-is.
     * @throws IllegalArgumentException if metadata is invalid or skillContent is null or empty
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source,
            Path originDir) {
        this(metadata, skillContent, resources, source, originDir, false);
    }

    /**
     * Full constructor including the system-capability flag (Builder path only).
     *
     * @param metadata Skill metadata including required {@code name} and {@code description}
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources (can be null)
     * @param source Source identifier for the skill ({@code null} = no provenance)
     * @param originDir Absolute path to the skill's source directory, or {@code null}
     * @param system Whether this skill is exempt from grant-layer trimming
     * @throws IllegalArgumentException if metadata is invalid or skillContent is null or empty
     */
    private AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source,
            Path originDir,
            boolean system) {
        String name = getRequiredMetadataString(metadata, "name");
        String description = getRequiredMetadataString(metadata, "description");
        if (skillContent == null || skillContent.isEmpty()) {
            throw new IllegalArgumentException("The skill must have content");
        }
        if (name.contains(SKILL_ID_SEPARATOR)) {
            throw new IllegalArgumentException(
                    "The skill name must not contain '"
                            + SKILL_ID_SEPARATOR
                            + "' (reserved as the skillId separator)");
        }
        String resolvedSource = source != null && !source.isBlank() ? source : null;
        if (resolvedSource != null && resolvedSource.contains(SKILL_ID_SEPARATOR)) {
            throw new IllegalArgumentException(
                    "The skill source must not contain '"
                            + SKILL_ID_SEPARATOR
                            + "' (reserved as the skillId separator)");
        }

        LinkedHashMap<String, Object> metadataCopy = new LinkedHashMap<>(metadata);
        metadataCopy.put("name", name);
        metadataCopy.put("description", description);

        this.metadata = Collections.unmodifiableMap(metadataCopy);
        this.skillContent = skillContent;
        this.resources = resources != null ? new HashMap<>(resources) : new HashMap<>();
        this.source = resolvedSource;
        this.originDir = originDir;
        this.system = system;
    }

    /**
     * Gets the skill name.
     *
     * <p>The name is the skill's business identity and follows the industry convention of unique
     * skill naming: it is the single identifier used across the grant surface, registry keying,
     * loading, and skill tool-group naming. It must be unique within an agent's catalog; the
     * {@link #getSource() source} is provenance metadata only, not part of the identity.
     *
     * @return The skill name (never null)
     */
    public String getName() {
        return (String) metadata.get("name");
    }

    /**
     * Gets the skill description.
     *
     * @return The skill description (never null)
     */
    public String getDescription() {
        return (String) metadata.get("description");
    }

    /**
     * Gets the skill metadata.
     *
     * @return The immutable metadata map (never null, may be empty except required fields)
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets a metadata value by key.
     *
     * @param key The metadata key
     * @return The metadata value, or null if not found
     */
    public Object getMetadataValue(String key) {
        return metadata.get(key);
    }

    /**
     * Gets the skill content.
     *
     * <p>This contains the actual skill implementation or instructions.
     *
     * @return The skill content (never null)
     */
    public String getSkillContent() {
        return skillContent;
    }

    /**
     * Gets the skill source identifier (provenance only, not part of the identity).
     *
     * @return The source identifier, or {@code null} when the skill carries no provenance
     */
    public String getSource() {
        return source;
    }

    /**
     * Gets the skill resources.
     *
     * @return The resources map (never null, may be empty)
     */
    public Map<String, String> getResources() {
        return new HashMap<>(resources);
    }

    /**
     * Gets the resource content by path.
     *
     * @param resourcePath The resource path
     * @return The resource content, or null if not found
     */
    public String getResource(String resourcePath) {
        return resources.get(resourcePath);
    }

    /**
     * Gets all resource paths for this skill.
     *
     * @return Unmodifiable set of resource paths
     */
    public Set<String> getResourcePaths() {
        return Collections.unmodifiableSet(new HashSet<>(resources.keySet()));
    }

    /**
     * Gets a unique identifier for this skill. With no source set (the common case — skills
     * are not differentiated by repository), the id is simply the name; with a source set it
     * extends to {@code name + "__s__" + source} (see {@link #SKILL_ID_SEPARATOR}). Neither
     * part may contain the separator, so a qualified id parses back unambiguously; grant
     * entries use either the bare name (source-unrestricted) or the qualified form
     * (repository-specific).
     *
     * @return the skill identifier (never null)
     */
    public String getSkillId() {
        return source == null || source.isBlank()
                ? getName()
                : getName() + SKILL_ID_SEPARATOR + source;
    }

    /**
     * Parses the name part out of a skill identifier.
     *
     * @param skillIdOrName a bare skill name or a full skillId ({@code name__s__source})
     * @return the name part (the whole input when it contains no separator)
     */
    public static String parseSkillName(String skillIdOrName) {
        int idx = skillIdOrName.indexOf(SKILL_ID_SEPARATOR);
        return idx < 0 ? skillIdOrName : skillIdOrName.substring(0, idx);
    }

    /**
     * Returns the absolute path to this skill's on-disk source directory, when available. Used
     * by the prompt provider to emit per-skill {@code <files-root>} entries and by the load tool
     * as a disk-fallback when an in-memory resource is missing.
     *
     * @return the origin directory, empty when the skill is not filesystem-backed
     */
    public Optional<Path> getOriginDir() {
        return Optional.ofNullable(originDir);
    }

    /**
     * Whether this skill is a system capability exempt from grant-layer (per-user capability)
     * trimming. The bypass takes effect only when a surface is present; a missing surface fails
     * closed.
     *
     * @return {@code true} if this skill bypasses grant-layer trimming; {@code false} by default
     */
    public boolean isSystem() {
        return system;
    }

    /**
     * Creates a builder initialized with this skill's values.
     *
     * <p>This is useful for creating modified versions of existing skills.
     *
     * @return A new builder instance
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Creates a new builder for creating a skill from scratch.
     *
     * @return A new builder instance with empty fields
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a string representation of this skill.
     *
     * @return String representation including name, description, and source
     */
    @Override
    public String toString() {
        return "AgentSkill{name='"
                + getName()
                + "', description='"
                + getDescription()
                + "', source='"
                + source
                + "'}";
    }

    /**
     * Builder for creating AgentSkill instances.
     *
     * <p>This builder allows selective modification of skill fields to create
     * new skill instances based on existing ones, or to create new skills from scratch.
     *
     * <p><b>Usage examples:</b>
     * <pre>{@code
     * // Create from scratch
     * AgentSkill skill = AgentSkill.builder()
     *     .name("my_skill")
     *     .description("Does something")
     *     .skillContent("Instructions here")
     *     .addResource("file.txt", "content")
     *     .build();
     *
     * // Modify existing skill
     * AgentSkill modified = existingSkill.toBuilder()
     *     .description("Updated description")
     *     .addResource("new_file.txt", "new content")
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private Map<String, Object> metadata;
        private String skillContent;
        private Map<String, String> resources;
        private String source;
        private Path originDir;
        private boolean system = false;

        /**
         * Creates an empty builder.
         */
        private Builder() {
            this.metadata = new LinkedHashMap<>();
            this.resources = new HashMap<>();
        }

        /**
         * Creates a builder initialized with values from an existing skill.
         *
         * @param baseSkill The skill to copy values from
         */
        private Builder(AgentSkill baseSkill) {
            this.metadata = new LinkedHashMap<>(baseSkill.metadata);
            this.skillContent = baseSkill.skillContent;
            this.resources = new HashMap<>(baseSkill.resources);
            this.source = baseSkill.source;
            this.originDir = baseSkill.originDir;
            this.system = baseSkill.system;
        }

        /**
         * Sets the skill name.
         *
         * @param name The skill name
         * @return This builder
         */
        public Builder name(String name) {
            this.metadata.put("name", name);
            return this;
        }

        /**
         * Sets the skill description.
         *
         * @param description The skill description
         * @return This builder
         */
        public Builder description(String description) {
            this.metadata.put("description", description);
            return this;
        }

        /**
         * Replaces all metadata with a new map.
         *
         * @param metadata The new metadata map
         * @return This builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata =
                    metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            return this;
        }

        /**
         * Adds or updates a single metadata entry.
         *
         * @param key The metadata key
         * @param value The metadata value
         * @return This builder
         */
        public Builder putMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Removes a metadata entry.
         *
         * @param key The metadata key to remove
         * @return This builder
         */
        public Builder removeMetadata(String key) {
            this.metadata.remove(key);
            return this;
        }

        /**
         * Sets the skill content.
         *
         * @param skillContent The skill content/instructions
         * @return This builder
         */
        public Builder skillContent(String skillContent) {
            this.skillContent = skillContent;
            return this;
        }

        /**
         * Replaces all resources with a new map.
         *
         * @param resources The new resources map
         * @return This builder
         */
        public Builder resources(Map<String, String> resources) {
            this.resources = new HashMap<>(resources);
            return this;
        }

        /**
         * Adds or updates a single resource.
         *
         * @param path The resource path
         * @param content The resource content
         * @return This builder
         */
        public Builder addResource(String path, String content) {
            this.resources.put(path, content);
            return this;
        }

        /**
         * Removes a resource.
         *
         * @param path The resource path to remove
         * @return This builder
         */
        public Builder removeResource(String path) {
            this.resources.remove(path);
            return this;
        }

        /**
         * Clears all resources.
         *
         * @return This builder
         */
        public Builder clearResources() {
            this.resources.clear();
            return this;
        }

        /**
         * Sets the source identifier.
         *
         * @param source The source identifier
         * @return This builder
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the on-disk origin directory for this skill. Repositories that materialise a
         * skill from a local directory should call this so the prompt provider can emit a
         * {@code <files-root>} and the load tool can fall back to disk reads.
         *
         * @param originDir absolute path to the skill's source directory, or {@code null}
         * @return This builder
         */
        public Builder originDir(Path originDir) {
            this.originDir = originDir;
            return this;
        }

        /**
         * Marks the skill as a system capability exempt from grant-layer (per-user capability)
         * trimming. Only mark framework/infrastructure skills; business skills marked system
         * bypass per-user capability trimming entirely.
         *
         * @param system true to exempt this skill from grant-layer trimming
         * @return This builder
         */
        public Builder system(boolean system) {
            this.system = system;
            return this;
        }

        /**
         * Builds the AgentSkill instance.
         *
         * @return A new AgentSkill instance
         * @throws IllegalArgumentException if required fields are missing
         */
        public AgentSkill build() {
            return new AgentSkill(metadata, skillContent, resources, source, originDir, system);
        }
    }

    private static Map<String, Object> createMetadata(String name, String description) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("description", description);
        return metadata;
    }

    private static String getRequiredMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "The skill must have `name` and `description` fields.");
        }

        Object value = metadata.get(key);
        if (!(value instanceof String stringValue) || stringValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill must have `name` and `description` fields.");
        }
        return stringValue;
    }
}
