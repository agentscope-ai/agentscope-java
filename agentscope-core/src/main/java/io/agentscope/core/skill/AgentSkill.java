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

import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.skill.util.SkillUtil;
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
 *   <li>Skill content - the actual skill implementation or instructions; {@code null} for a
 *       descriptor-only summary (see {@link #getSkillContent()})
 *   <li>Resources - supporting files or data referenced by the skill
 *   <li>Source - the repository identity the skill was loaded from
 *   <li>Version (nullable) - the repository-resolved version reference this instance was
 *       loaded under; distinct from any author-declared {@code version} inside the metadata
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
 * String skillMd = "---\nname: my-skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Direct construction
 * AgentSkill skill2 = new AgentSkill("my-skill", "Does something", "Content here", resources);
 *
 * // Create modified version using builder
 * AgentSkill modified = skill.toBuilder()
 *     .description("Updated description")
 *     .skillContent("Modified instructions")
 *     .addResource("config.json", "{\"key\": \"value\"}")
 *     .build();
 * }</pre>
 *
 * SkillUtil and MarkdownSkillParser manage markdown skill catalogs in application code.
 *
 * @see io.agentscope.core.skill.util.SkillUtil
 * @see io.agentscope.core.skill.util.MarkdownSkillParser
 */
public class AgentSkill {
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
     * The repository-resolved version reference this instance was loaded under (see {@code
     * AgentSkillRepository}'s class-level version semantics), or {@code null} when the loading
     * repository has no version capability or resolved no version. Provenance only: it does
     * not describe the content and is unrelated to any author-declared {@code version}
     * inside the metadata map.
     */
    private final String version;

    /**
     * Creates an AgentSkill with explicit parameters.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. Uses "custom" as the default source.
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill body, or {@code null} for a descriptor-only summary whose
     *     body is read on demand from {@code originDir}; when present it must not be empty
     * @param resources Supporting resources referenced by the skill (can be null)
     * @throws IllegalArgumentException if name, description, or skillContent is present but empty
     */
    public AgentSkill(
            String name, String description, String skillContent, Map<String, String> resources) {
        this(name, description, skillContent, resources, "custom");
    }

    /**
     * Creates an AgentSkill with explicit parameters and custom source.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. The source parameter indicates where the skill originated from.
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill body, or {@code null} for a descriptor-only summary whose
     *     body is read on demand from {@code originDir}; when present it must not be empty
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @throws IllegalArgumentException if name, description, or skillContent is present but empty
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
     * @param skillContent The skill body, or {@code null} for a descriptor-only summary whose
     *     body is read on demand from {@code originDir}; when present it must not be empty
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @throws IllegalArgumentException if metadata is invalid or skillContent is present but empty
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source) {
        this(metadata, skillContent, resources, source, null);
    }

    /**
     * Creates an AgentSkill with explicit metadata and an on-disk origin directory; the
     * resolved version is left {@code null}.
     *
     * <p>The {@code name} must conform to the standard naming rule ({@link
     * SkillUtil#VALID_SKILL_NAME_PATTERN}, at most {@link SkillUtil#MAX_SKILL_NAME_LENGTH}
     * characters) because names are used verbatim as directory keys during materialization.
     *
     * @param metadata Skill metadata including required {@code name} and {@code description}
     * @param skillContent The skill body, or {@code null} for a descriptor-only summary whose
     *     body is read on demand from {@code originDir}; when present it must not be empty
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @param originDir Absolute path to the skill's source directory, or {@code null} if not
     *                  filesystem-backed (e.g. classpath / remote / synthetic). Stored as-is.
     * @throws IllegalArgumentException if metadata is invalid, skillContent is present but empty, or
     *                                  the name does not conform to the standard naming rule
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source,
            Path originDir) {
        this(metadata, skillContent, resources, source, originDir, null);
    }

    /**
     * Creates an AgentSkill with explicit metadata, an on-disk origin directory and the
     * repository-resolved version this instance was loaded under.
     *
     * @param metadata Skill metadata including required {@code name} and {@code description}
     * @param skillContent The skill body, or {@code null} for a descriptor-only summary whose
     *     body is read on demand from {@code originDir}; when present it must not be empty
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @param originDir Absolute path to the skill's source directory, or {@code null} if not
     *                  filesystem-backed (e.g. classpath / remote / synthetic). Stored as-is.
     * @param version The repository-resolved version reference this instance was loaded under,
     *                or {@code null} when no version was resolved
     * @throws IllegalArgumentException if metadata is invalid, skillContent is present but empty, or
     *                                  the name does not conform to the standard naming rule
     */
    public AgentSkill(
            Map<String, Object> metadata,
            String skillContent,
            Map<String, String> resources,
            String source,
            Path originDir,
            String version) {
        String name = getRequiredMetadataString(metadata, "name");
        String description = getRequiredMetadataString(metadata, "description");
        SkillUtil.requireValidSkillName(name);
        if (skillContent != null && skillContent.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill content must not be empty when present (null = summary)");
        }

        LinkedHashMap<String, Object> metadataCopy = new LinkedHashMap<>(metadata);
        metadataCopy.put("name", name);
        metadataCopy.put("description", description);

        this.metadata = Collections.unmodifiableMap(metadataCopy);
        this.skillContent = skillContent;
        this.resources = resources != null ? new HashMap<>(resources) : new HashMap<>();
        this.source = source != null ? source : "custom";
        this.originDir = originDir;
        this.version = version;
    }

    /**
     * Gets the skill name.
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
     * <p>This contains the actual skill implementation or instructions, or {@code null} for a
     * descriptor-only summary: repository summaries carry no body in memory — the body lives
     * on disk at {@code originDir} and is read on demand (load tool / eager load).
     *
     * @return The skill content, or {@code null} when this instance is a descriptor-only
     *     summary
     */
    public String getSkillContent() {
        return skillContent;
    }

    /**
     * Gets the skill source identifier.
     *
     * @return The source identifier (never null)
     */
    public String getSource() {
        return source;
    }

    /**
     * Gets the skill resources.
     *
     * <p>The map is a transport/write-time form: it is populated only while a skill is in
     * flight (a remote fetch, or a {@code save()} writer holding in-memory content). A served
     * skill typically has an empty map — its files live under {@link #getOriginDir()}: read
     * them through {@link #getResource(String)} and enumerate them from the directory, not
     * from this map.
     *
     * @return The resources map (never null, may be empty)
     */
    public Map<String, String> getResources() {
        return new HashMap<>(resources);
    }

    /**
     * Gets the resource content by path: the in-memory map first, then a read from {@link
     * #getOriginDir()}.
     *
     * <p>The disk fallback resolves the path under {@code originDir}, refusing absolute and
     * traversal-shaped paths, and returns the file as text (or as {@code base64:} content for
     * binary files). Returns {@code null} when neither source has the path.
     *
     * @param resourcePath The resource path
     * @return The resource content, or null if not found
     */
    public String getResource(String resourcePath) {
        String content = resources.get(resourcePath);
        if (content != null) {
            return content;
        }
        if (originDir == null) {
            return null;
        }
        return SkillFileSystemHelper.readResource(originDir, resourcePath).orElse(null);
    }

    /**
     * Gets all resource paths for this skill.
     *
     * <p>Memory only: this reflects the in-memory map (see {@link #getResources()}). A served
     * skill's on-disk file set must be enumerated from {@link #getOriginDir()} (for example
     * via {@link SkillFileSystemHelper#listResourcePaths}), never from this set.
     *
     * @return Unmodifiable set of resource paths
     */
    public Set<String> getResourcePaths() {
        return Collections.unmodifiableSet(new HashSet<>(resources.keySet()));
    }

    /**
     * Gets a unique identifier for this skill.
     *
     * <p>The ID is composed of name and source: "name_source". Because the standard naming rule
     * forbids underscores in names, the ID splits deterministically at the first underscore, so
     * distinct {@code (name, source)} pairs always produce distinct IDs. {@code SkillRegistry}
     * additionally warns if a same-ID registration ever overwrites a different {@code (name,
     * source)} pair — with the current charset this is unreachable and serves purely as a
     * tripwire against future rule relaxations.
     *
     * @return Unique skill identifier (never null)
     */
    public String getSkillId() {
        return getName() + "_" + source;
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
     * Gets the repository-resolved version reference this instance was loaded under.
     * Version-capable repositories stamp every skill they return, binding one loaded
     * instance to one version; {@code null} means the loading repository has no version
     * capability or resolved no version. Provenance only, and unrelated to any
     * author-declared {@code version} in the metadata.
     *
     * @return the resolved version reference, or {@code null}
     */
    public String getVersion() {
        return version;
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
                + (version != null ? "', version='" + version : "")
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
     *     .name("my-skill")
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
        private String version;

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
            this.version = baseSkill.version;
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
         * Sets the repository-resolved version reference this instance was loaded under
         * (see {@link #getVersion()}).
         *
         * @param version the resolved version reference, or {@code null} for none
         * @return This builder
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Builds the AgentSkill instance.
         *
         * @return A new AgentSkill instance
         * @throws IllegalArgumentException if required fields are missing
         */
        public AgentSkill build() {
            return new AgentSkill(metadata, skillContent, resources, source, originDir, version);
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
