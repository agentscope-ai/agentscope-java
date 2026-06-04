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
package io.agentscope.dataagent.runtime.marketplace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed {@link DataAgentMarketplace} reading skills from a per-agent slice on disk,
 * by convention {@code ${dataagentHome}/shared/agents/<agentId>/skills/} (the caller of this
 * class picks the exact root — see {@link io.agentscope.dataagent.web.config.DataAgentConfig}).
 *
 * <p>This is the in-process implementation that backs the admin-approval contribution flow:
 * approved contributions are dropped into the same per-agent slice and become visible to every
 * tenant of that agent because each per-{@code (userId, agentId)} sandbox projects that slice
 * into the container as its lower layer.
 *
 * <p>Read-only from the marketplace API's perspective — writes happen out-of-band through
 * {@code MarketContributionService} after admin approval.
 *
 * <p>Layout (example for {@code data-agent}):
 * <pre>
 * ${dataagentHome}/shared/agents/data-agent/skills/
 *   sql-analysis/
 *     SKILL.md
 *     templates/intro.md     ← side resources flat-keyed by their relative path
 *   chart-rendering/
 *     SKILL.md
 * </pre>
 */
public final class LocalApprovalMarketplace implements DataAgentMarketplace {

    private static final Logger log = LoggerFactory.getLogger(LocalApprovalMarketplace.class);
    public static final String TYPE = "local";

    private final String id;
    private final Path skillsRoot;

    public LocalApprovalMarketplace(String id, Path skillsRoot) {
        this.id = Objects.requireNonNull(id, "id");
        this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return skillsRoot.toAbsolutePath().toString();
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public List<MarketSkillSummary> list() {
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        List<MarketSkillSummary> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsRoot)) {
            entries.filter(Files::isDirectory)
                    .sorted()
                    .forEach(
                            dir -> {
                                Path skillMd = dir.resolve("SKILL.md");
                                if (!Files.isRegularFile(skillMd)) return;
                                String name = dir.getFileName().toString();
                                String description = firstNonBlankLine(skillMd);
                                out.add(new MarketSkillSummary(name, description, null));
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' failed to list skills under {}: {}",
                    id,
                    skillsRoot,
                    e.getMessage());
        }
        return out;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) return null;
        Path dir = skillsRoot.resolve(name);
        if (!Files.isDirectory(dir) || !dir.normalize().startsWith(skillsRoot.normalize())) {
            return null;
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;

        String markdown;
        try {
            markdown = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> resources = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillMd))
                    .sorted()
                    .forEach(
                            p -> {
                                String rel = dir.relativize(p).toString().replace('\\', '/');
                                try {
                                    resources.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException ignored) {
                                    // Best-effort: skip unreadable side files (binary, etc.)
                                }
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' partial fetch of '{}': {}",
                    id,
                    name,
                    e.getMessage());
        }
        return new MarketSkillContent(
                name, firstNonBlankLine(skillMd), markdown, Collections.unmodifiableMap(resources));
    }

    @Override
    public void close() {
        // No persistent resources to release.
    }

    private static String firstNonBlankLine(Path skillMd) {
        try {
            for (String line : Files.readAllLines(skillMd, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("---")) continue;
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        } catch (IOException e) {
            // fall through
        }
        return "";
    }
}
