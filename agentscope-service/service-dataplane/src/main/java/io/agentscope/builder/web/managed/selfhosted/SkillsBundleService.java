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

import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.core.skill.AgentSkill;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Packs agent skills into a Worker-downloadable manifest (Claude-style skills sync for
 * self-hosted).
 */
@Service
public class SkillsBundleService {

    private final DataSessionService sessionService;
    private final HarnessAgentBuildService agentBuildService;

    public SkillsBundleService(
            DataSessionService sessionService,
            HarnessAgentBuildService agentBuildService,
            UserAgentDefinitionStore store) {
        this.sessionService = sessionService;
        this.agentBuildService = agentBuildService;
    }

    /** Builds a JSON-friendly skills bundle for the session's agent. */
    public Map<String, Object> bundleForSession(String sessionId) {
        var resolved = sessionService.resolve(sessionId);
        ManagedSessionDto session = resolved.session();
        var snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(
                                resolved.agentSnapshot(),
                                io.agentscope.builder.web.managed.AgentVersionSnapshot.class);
        var enabled =
                io.agentscope.builder.web.catalog.spec.AgentSpecCodec.workspaceSkillNames(
                        snapshot.skills());
        Map<String, String> files =
                resolved.definitionFiles() == null ? Map.of() : resolved.definitionFiles();
        List<Map<String, Object>> skills = new ArrayList<>();
        for (String name : enabled) {
            String prefix = "skills/" + name + "/";
            String content = files.get(prefix + "SKILL.md");
            if (content == null) continue;
            Map<String, Object> resources = new LinkedHashMap<>();
            for (var file : files.entrySet()) {
                if (!file.getKey().startsWith(prefix) || file.getKey().equals(prefix + "SKILL.md"))
                    continue;
                String path = file.getKey().substring(prefix.length());
                resources.put(
                        path,
                        Map.of(
                                "encoding",
                                "utf8",
                                "contentBase64",
                                Base64.getEncoder()
                                        .encodeToString(
                                                file.getValue().getBytes(StandardCharsets.UTF_8)),
                                "executable",
                                path.startsWith("scripts/")
                                        || path.endsWith(".sh")
                                        || path.endsWith(".py")));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("description", "");
            entry.put("source", "managed-definition");
            entry.put("skillContent", content);
            entry.put("resources", resources);
            skills.add(entry);
        }
        // Keep declared external repositories available, under the same filter and precedence
        // as the Brain. Workspace skills above win on duplicate names.
        var workspace = agentBuildService.resolveSessionWorkspace(session, resolved);
        io.agentscope.builder.web.catalog.ManagedDefinitionMaterializer.materialize(
                workspace, files);
        var included = new java.util.HashSet<String>();
        for (var skill : skills) included.add((String) skill.get("name"));
        for (var repository :
                io.agentscope.builder.runtime.config.SkillRepositorySupport.createAll(
                        workspace, snapshot.skillRepositories())) {
            try (repository) {
                for (AgentSkill skill : repository.getAllSkills()) {
                    if (enabled.contains(skill.getName()) && included.add(skill.getName())) {
                        skills.add(toSkillEntry(skill));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot package declared skill repository", e);
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
