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
package io.agentscope.harness.claw.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Read-only admin view of agent workspace files, sourced directly from the shared
 * {@code .agentscope/workspace} directory.
 *
 * <ul>
 *   <li>{@code GET /api/admin/agents/{id}/workspace} — workspace summary
 *   <li>{@code GET /api/admin/agents/{id}/workspace/agents-md} — read AGENTS.md
 *   <li>{@code GET /api/admin/agents/{id}/workspace/memory} — memory overview
 *   <li>{@code GET /api/admin/agents/{id}/workspace/skills} — list skills
 *   <li>{@code GET /api/admin/agents/{id}/workspace/skills/{name}} — read a skill
 * </ul>
 *
 * <p>Write operations are not available in the admin console. Edit workspace files directly and
 * restart agentscope-claw to apply changes.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sharedWorkspaceRoot;
    private final Path configFile;

    public AgentWorkspaceController(@Value("${claw-web.workspace:}") String workspaceDir) {
        Path cwd =
                workspaceDir != null && !workspaceDir.isBlank()
                        ? Path.of(workspaceDir)
                        : Path.of(System.getProperty("user.dir"));
        Path agentscopeDir = cwd.resolve(".agentscope").normalize();
        this.sharedWorkspaceRoot = agentscopeDir.resolve("workspace");
        this.configFile = agentscopeDir.resolve("agentscope.json");
    }

    @GetMapping
    public Mono<WorkspaceSummary> summary(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    boolean agentsMdExists = Files.exists(ws.resolve("AGENTS.md"));
                    boolean memoryExists = Files.exists(ws.resolve("MEMORY.md"));
                    int skillCount = countFiles(ws.resolve("skills"), "*.md");
                    int subagentCount = countFiles(ws.resolve("subagents"), "*.md");
                    return new WorkspaceSummary(
                            agentId,
                            ws.toString(),
                            agentsMdExists,
                            memoryExists,
                            skillCount,
                            subagentCount);
                });
    }

    @GetMapping("/agents-md")
    public Mono<FileContent> agentsMd(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    Path f = ws.resolve("AGENTS.md");
                    if (!Files.exists(f)) return new FileContent("AGENTS.md", "");
                    return new FileContent("AGENTS.md", readSafe(f));
                });
    }

    @GetMapping("/memory")
    public Mono<MemoryOverview> memory(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    Path memFile = ws.resolve("MEMORY.md");
                    String content = Files.exists(memFile) ? readSafe(memFile) : "";
                    List<String> dailyFiles = new ArrayList<>();
                    Path daily = ws.resolve("daily");
                    if (Files.isDirectory(daily)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(daily, "*.md")) {
                            for (Path p : ds) dailyFiles.add(p.getFileName().toString());
                        }
                    }
                    return new MemoryOverview(content, dailyFiles);
                });
    }

    @GetMapping("/skills")
    public Mono<List<SkillEntry>> skills(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path skillsDir = resolveWorkspace(agentId).resolve("skills");
                    if (!Files.isDirectory(skillsDir)) return List.of();
                    List<SkillEntry> list = new ArrayList<>();
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(skillsDir, "*.md")) {
                        for (Path f : ds) {
                            String name = f.getFileName().toString().replace(".md", "");
                            list.add(new SkillEntry(name, Files.size(f)));
                        }
                    }
                    return list;
                });
    }

    @GetMapping("/skills/{name}")
    public Mono<FileContent> readSkill(@PathVariable String agentId, @PathVariable String name) {
        return Mono.fromCallable(
                () -> {
                    if (!name.matches("[a-zA-Z0-9_\\-]+")) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Invalid skill name");
                    }
                    Path f = resolveWorkspace(agentId).resolve("skills").resolve(name + ".md");
                    if (!Files.exists(f)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    return new FileContent(name + ".md", readSafe(f));
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private Path resolveWorkspace(String agentId) throws IOException {
        // Agent workspaces are under {sharedWorkspaceRoot}/{agentId}/
        Path ws = sharedWorkspaceRoot.resolve(agentId).normalize();
        if (!ws.startsWith(sharedWorkspaceRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid agent id");
        }
        // Also accept the flat workspace root for the default single-agent setup
        if (!Files.isDirectory(ws)) {
            if (Files.isDirectory(sharedWorkspaceRoot)) return sharedWorkspaceRoot;
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Workspace not found for agent: " + agentId);
        }
        return ws;
    }

    private String readSafe(Path f) throws IOException {
        long size = Files.size(f);
        if (size > MAX_FILE_SIZE) {
            byte[] bytes = Files.readAllBytes(f);
            return new String(bytes, 0, MAX_FILE_SIZE, StandardCharsets.UTF_8)
                    + "\n\n[truncated at 512 KB]";
        }
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    private int countFiles(Path dir, String glob) {
        if (!Files.isDirectory(dir)) return 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            int n = 0;
            for (Path ignored : ds) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record WorkspaceSummary(
            String agentId,
            String workspacePath,
            boolean hasAgentsMd,
            boolean hasMemory,
            int skillCount,
            int subagentCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileContent(String filename, String content) {}

    public record MemoryOverview(String content, List<String> dailyFiles) {}

    public record SkillEntry(String name, long sizeBytes) {}
}
