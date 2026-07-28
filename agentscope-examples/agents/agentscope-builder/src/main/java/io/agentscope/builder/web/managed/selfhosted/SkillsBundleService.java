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
package io.agentscope.builder.web.managed.selfhosted;

import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.runtime.config.SkillRepositorySupport;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedSessionService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Packs agent skills into a Worker-downloadable manifest (Claude-style skills sync for
 * self-hosted).
 */
@Service
public class SkillsBundleService {

    private final ManagedSessionService sessionService;
    private final AgentCatalogService catalogService;

    public SkillsBundleService(
            ManagedSessionService sessionService, AgentCatalogService catalogService) {
        this.sessionService = sessionService;
        this.catalogService = catalogService;
    }

    /** Builds a JSON-friendly skills bundle for the session's agent. */
    public Map<String, Object> bundleForSession(String sessionId) {
        ManagedSessionDto session = sessionService.requireById(sessionId);
        Path workspace = catalogService.resolveAgentWorkspace(session.ownerId(), session.agentId());
        List<SkillRepositoryConfigEntry> entries = List.of();
        Optional<UserAgentDefinitionStore.StoredEntry> stored =
                catalogService.findStoredEntry(session.agentId());
        if (stored.isPresent() && stored.get().skillRepositories() != null) {
            entries = stored.get().skillRepositories();
        }

        List<AgentSkillRepository> repos =
                new ArrayList<>(SkillRepositorySupport.createAll(workspace, entries));
        Path skillsDir = workspace.resolve("skills");
        if (Files.isDirectory(skillsDir)) {
            repos.add(new FileSystemSkillRepository(skillsDir));
        }

        List<Map<String, Object>> skills = new ArrayList<>();
        for (AgentSkillRepository repo : repos) {
            for (AgentSkill skill : repo.getAllSkills()) {
                skills.add(toSkillEntry(skill));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("agentId", session.agentId());
        out.put("skills", skills);
        return out;
    }

    private static Map<String, Object> toSkillEntry(AgentSkill skill) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", skill.getName());
        entry.put("description", skill.getDescription());
        entry.put("source", skill.getSource());
        if (skill.getSkillContent() != null) {
            entry.put("skillContent", skill.getSkillContent());
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        if (skill.getResources() != null) {
            for (Map.Entry<String, String> e : skill.getResources().entrySet()) {
                Map<String, Object> file = new LinkedHashMap<>();
                String content = e.getValue() == null ? "" : e.getValue();
                boolean executable =
                        e.getKey().contains("/scripts/")
                                || e.getKey().endsWith(".sh")
                                || e.getKey().endsWith(".py");
                if (content.startsWith("base64:")) {
                    file.put("encoding", "base64");
                    file.put("content", content.substring("base64:".length()));
                } else {
                    file.put("encoding", "utf8");
                    file.put(
                            "contentBase64",
                            Base64.getEncoder()
                                    .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
                }
                file.put("executable", executable);
                resources.put(e.getKey(), file);
            }
        }
        entry.put("resources", resources);
        return entry;
    }
}
