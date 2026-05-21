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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.app.session.UserWorkspaceProvisioner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for skill (markdown tool definition) management.
 *
 * <p>Skills are stored as {@code .md} files inside each user's workspace under
 * {@code .agentscope/users/{userId}/workspace/skills/}. This is the same directory the user's
 * {@link io.agentscope.harness.agent.HarnessAgent} reads from at runtime, so a skill the user
 * creates here is immediately visible to that user's next chat turn.
 *
 * <p>There is no "global skills" concept anymore — the previous notion of a shared
 * {@code {cwd}/skills/} directory was a dead path the agent never read from. System-seeded skills
 * arrive through the bundled workspace template (materialised on first chat by
 * {@link UserWorkspaceProvisioner}); users can edit their own copies freely.
 *
 * <ul>
 *   <li>{@code GET /api/skills} — list this user's skills
 *   <li>{@code GET /api/skills/{name}} — read a skill's content
 *   <li>{@code POST /api/skills} — create a new skill
 *   <li>{@code PUT /api/skills/{name}} — update an existing skill
 *   <li>{@code DELETE /api/skills/{name}} — delete a skill
 * </ul>
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]+");
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    private final UserWorkspaceProvisioner provisioner;

    public SkillController(UserWorkspaceProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /** Lists this user's skills. */
    @GetMapping
    public Mono<List<SkillView>> listSkills(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    List<SkillView> result = new ArrayList<>();
                    listSkillsInDir(userSkillsDir(userId), userId, result);
                    return result;
                });
    }

    /** Returns the raw markdown content of a skill. */
    @GetMapping("/{name}")
    public Mono<SkillContentView> getSkill(@PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    Path file = resolveSkillFile(userId, name);
                    if (!Files.isRegularFile(file)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    return new SkillContentView(name, extractDescription(content), content);
                });
    }

    /** Creates a new skill in the user's workspace. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SkillView> createSkill(@RequestBody SkillWriteRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    validateName(req.name());
                    Path file = resolveSkillFile(userId, req.name());
                    if (Files.exists(file)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Skill already exists: " + req.name());
                    }
                    String content =
                            req.content() != null && !req.content().isBlank()
                                    ? req.content()
                                    : defaultSkillTemplate(req.name(), req.description());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    log.info("User {} created skill: {}", userId, req.name());
                    return new SkillView(
                            req.name(),
                            userId,
                            req.description() != null ? req.description() : "",
                            file.toString());
                });
    }

    /** Updates an existing skill. */
    @PutMapping("/{name}")
    public Mono<SkillView> updateSkill(
            @PathVariable String name, @RequestBody SkillWriteRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    Path file = resolveSkillFile(userId, name);
                    if (!Files.isRegularFile(file)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    String content = req.content() != null ? req.content() : "";
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    log.info("User {} updated skill: {}", userId, name);
                    return new SkillView(
                            name, userId, extractDescription(content), file.toString());
                });
    }

    /** Deletes a skill. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSkill(@PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    Path file = resolveSkillFile(userId, name);
                    if (!Files.isRegularFile(file)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    try {
                        Files.delete(file);
                        log.info("User {} deleted skill: {}", userId, name);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to delete skill: " + e.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private Path userSkillsDir(String userId) {
        return provisioner.resolveUserWorkspace(userId).resolve("skills");
    }

    private Path resolveSkillFile(String userId, String name) {
        validateName(name);
        return userSkillsDir(userId).resolve(name + ".md");
    }

    private void listSkillsInDir(Path dir, String userId, List<SkillView> result) {
        if (!Files.isDirectory(dir)) return;
        try {
            Files.list(dir)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(
                            p -> {
                                String name = p.getFileName().toString().replace(".md", "");
                                String description = "(no description)";
                                try {
                                    description =
                                            extractDescription(
                                                    Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException ignored) {
                                    /* best-effort */
                                }
                                result.add(new SkillView(name, userId, description, p.toString()));
                            });
        } catch (IOException e) {
            log.warn("Failed to list skills in {}: {}", dir, e.getMessage());
        }
    }

    private static String extractDescription(String content) {
        if (content == null) return "";
        Matcher m = FRONTMATTER.matcher(content);
        if (m.find()) {
            String fm = m.group(1);
            for (String line : fm.split("\n")) {
                if (line.startsWith("description:")) {
                    return line.substring("description:".length()).trim().replace("\"", "");
                }
            }
        }
        String body = m.matches() ? content.substring(m.end()).trim() : content.trim();
        String[] lines = body.split("\n");
        for (String l : lines) {
            String t = l.replaceAll("^#+\\s*", "").trim();
            if (!t.isEmpty()) return t;
        }
        return "(no description)";
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || !NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Skill name must match [a-zA-Z0-9_\\-]+, got: " + name);
        }
    }

    private static String defaultSkillTemplate(String name, String description) {
        String desc =
                description != null && !description.isBlank() ? description : "A custom skill.";
        return "---\n"
                + "name: "
                + name
                + "\n"
                + "description: \""
                + desc
                + "\"\n"
                + "---\n\n"
                + "# "
                + name
                + "\n\n"
                + desc
                + "\n\n"
                + "## Instructions\n\n"
                + "Describe what this skill does and how the agent should use it.\n";
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record SkillView(String name, String ownerId, String description, String filePath) {}

    public record SkillContentView(String name, String description, String content) {}

    public record SkillWriteRequest(String name, String description, String content) {}
}
