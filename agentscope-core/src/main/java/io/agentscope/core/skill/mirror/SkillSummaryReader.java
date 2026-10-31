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
package io.agentscope.core.skill.mirror;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache-first reader for one skill's summary — the name, description and metadata that
 * prompt lists and discovery surfaces render.
 *
 * <p>When {@link SkillMirrorStore} holds the skill's version directory, only its {@code
 * SKILL.md} is read and parsed, with zero repository contact; on a cache miss — or an
 * unreadable or invalid cached {@code SKILL.md} — the reader falls back to the owning
 * repository's {@link AgentSkillRepository#getSkill(String, String)}.
 *
 * <p>The returned instance is a descriptor-only summary: metadata, an empty resource map,
 * {@code originDir} pointing at the version directory and the version stamped. Neither the
 * SKILL.md body nor resources are held in memory; both are read on demand from {@code
 * originDir}.
 *
 * @author shanhongyu
 */
public final class SkillSummaryReader {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryReader.class);

    private SkillSummaryReader() {}

    /**
     * Reads one skill's summary: the mirrored {@code SKILL.md} frontmatter when cached, the
     * repository's {@code getSkill} otherwise.
     *
     * @param mirror the mirror store consulted first (never null)
     * @param repo the owning repository, consulted on cache miss (never null)
     * @param repoIdentity the repository's identity key in the mirror store (never null)
     * @param name the skill name (never null)
     * @param version the bound version reference (never null)
     * @return the summary skill — descriptor-only: metadata, {@code originDir} pointing at
     *     the version directory and the version stamped, no body — the repository's fetched
     *     skill on cache miss, or {@code null} when neither source resolves the skill
     */
    public static AgentSkill readSummary(
            SkillMirrorStore mirror,
            AgentSkillRepository repo,
            String repoIdentity,
            String name,
            String version) {
        AgentSkill cached = readFromMirror(mirror, repoIdentity, name, version, repo.getSource());
        return cached != null ? cached : repo.getSkill(name, version);
    }

    /**
     * Mirror-only variant of {@link #readSummary}: parses the cached {@code SKILL.md} when
     * the version directory exists, without any repository contact. Returns {@code null} on
     * cache miss or when the cached file lacks a parseable frontmatter; callers that
     * already hold an in-memory skill fall back to it instead of re-fetching.
     *
     * @param mirror the mirror store consulted (never null)
     * @param repoIdentity the repository's identity key in the mirror store (never null)
     * @param name the skill name; an invalid name is a miss (never null)
     * @param version the bound version reference (never null)
     * @param source the source identifier stamped on the parsed summary (never null)
     * @return the summary skill, or {@code null} when the mirror cannot serve it
     */
    public static AgentSkill readFromMirror(
            SkillMirrorStore mirror,
            String repoIdentity,
            String name,
            String version,
            String source) {
        Optional<Path> dir = mirror.lookup(repoIdentity, name, version);
        return dir.isPresent() ? parseSummary(dir.get(), name, version, source) : null;
    }

    /**
     * Parses the summary directly from a known materialized version directory — the tail of
     * {@link SkillMirrorStore#serve}, called after a successful install. Returns {@code
     * null} when the directory lost its manifest or the file is unreadable or invalid
     * (out-of-band eviction racing the parse); callers treat that as a miss and re-pull.
     *
     * @param skillDir the materialized version directory (never null)
     * @param expectedName the skill name the directory is keyed by; a frontmatter declaring a
     *     different name is treated as an invalid entry (never null)
     * @param version the version reference stamped on the summary (never null)
     * @param source the source identifier stamped on the summary (never null)
     * @return the summary skill, or {@code null} when the directory cannot serve it
     */
    public static AgentSkill readFromDirectory(
            Path skillDir, String expectedName, String version, String source) {
        return parseSummary(skillDir, expectedName, version, source);
    }

    /**
     * Parses the mirrored {@code SKILL.md} into a summary-only skill. Returns {@code null}
     * when the file is missing, unreadable, has an empty body, or lacks the frontmatter the
     * {@link AgentSkill} contract requires — the caller then falls back to the repository.
     * The parsed frontmatter name must match the directory key: the store is a pure cache
     * keyed by the requested name, so a diverging declaration means a corrupted or foreign
     * entry, never a rename.
     */
    private static AgentSkill parseSummary(
            Path skillDir, String expectedName, String version, String source) {
        Path skillMd = skillDir.resolve(SkillMirrorStore.SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            return null;
        }
        try {
            ParsedMarkdown parsed =
                    MarkdownSkillParser.parse(Files.readString(skillMd, StandardCharsets.UTF_8));
            String content = parsed.getContent();
            if (content.isEmpty()) {
                // A valid write never stores an empty body (write paths require content), so
                // an empty-body cache entry is corruption — treat it as invalid.
                log.warn("Cached {} has an empty body; treating the entry as invalid", skillMd);
                return null;
            }
            AgentSkill summary =
                    new AgentSkill(parsed.getMetadata(), null, Map.of(), source, skillDir, version);
            if (!summary.getName().equals(expectedName)) {
                log.warn(
                        "Cached {} declares name '{}' but is keyed as '{}'; treating the entry"
                                + " as invalid",
                        skillMd,
                        summary.getName(),
                        expectedName);
                return null;
            }
            return summary;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Cached {} is unreadable or invalid for summary use", skillMd, e);
            return null;
        }
    }
}
