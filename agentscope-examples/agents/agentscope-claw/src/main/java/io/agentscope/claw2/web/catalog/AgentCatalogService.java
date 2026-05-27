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
package io.agentscope.claw2.web.catalog;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.web.scaffold.WorkspaceScaffolder;
import io.agentscope.claw2.web.template.TemplateRegistry;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.claw2.web.toolbus.ToolNotificationHook;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business logic for the local agent catalog. There is no notion of users or sharing — every
 * agent visible here is usable by the local user.
 *
 * <h2>Agent kinds</h2>
 *
 * <ul>
 *   <li><b>Built-in agents</b> — defined in {@code ${clawHome}/agentscope.json} and registered in
 *       the {@link HarnessGateway} at startup. Their definitions are exposed but their JSON entry
 *       is not editable through this service.
 *   <li><b>Custom agents</b> — stored in {@code ${clawHome}/agents.json}. The user can create,
 *       update, and delete them. On first conversation they are dynamically built and registered
 *       in the gateway under their {@code agentId} directly (no namespace prefix).
 * </ul>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    private final ClawBootstrap bootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final ToolEventBus toolEventBus;
    private final TemplateRegistry templateRegistry;

    /** Custom agent ids that have been built and registered in the gateway during this run. */
    private final ConcurrentHashMap<String, String> registeredCustomIds = new ConcurrentHashMap<>();

    public AgentCatalogService(
            ClawBootstrap bootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            TemplateRegistry templateRegistry) {
        this.bootstrap = bootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.templateRegistry = templateRegistry;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /** Lists every agent in the local catalog (built-ins first, then custom in insertion order). */
    public List<AgentDefinition> list() {
        List<AgentDefinition> out = new ArrayList<>(builtinDefinitions());
        for (UserAgentDefinitionStore.StoredEntry e : store.list()) {
            out.add(e.toDefinition());
        }
        return out;
    }

    /** Looks up a single agent by id (built-in or custom). */
    public Optional<AgentDefinition> find(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return list().stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /** Returns the custom-agent store entry by id, if any. */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return store.findById(agentId);
    }

    /** Returns {@code true} if the id refers to a built-in agent. */
    public boolean isBuiltin(String agentId) {
        return agentId != null && bootstrap.agents().containsKey(agentId);
    }

    // -----------------------------------------------------------------
    //  Mutations (custom agents only)
    // -----------------------------------------------------------------

    /** Creates a new custom agent definition. */
    public AgentDefinition createAgent(AgentCreateRequest req) {
        validateRequest(req);

        String id =
                sanitizeId(
                        req.id() != null && !req.id().isBlank()
                                ? req.id()
                                : UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        if (store.findById(id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent with id '" + id + "' already exists");
        }
        if (isBuiltin(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent id '" + id + "' conflicts with a built-in agent");
        }

        long now = System.currentTimeMillis();
        String workspacePath = normalizeWorkspacePathInput(req.workspacePath());

        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        req.name() != null ? req.name() : id,
                        req.description(),
                        req.sysPrompt(),
                        req.model(),
                        req.maxIters(),
                        req.toolsAllow(),
                        req.toolsDeny(),
                        req.identityName(),
                        req.identityEmoji(),
                        req.groupChatMentionPatterns(),
                        req.groupChatRequireMention(),
                        req.sandboxMode(),
                        req.sandboxScope(),
                        req.skillsAllow(),
                        req.skillsDeny(),
                        now,
                        now,
                        workspacePath);
        store.save(entry);
        log.info("Created custom agent '{}'", id);

        // Scaffold the workspace. Template wins over AI draft; otherwise default scaffold.
        Path workspace = workspacePath(entry);
        try {
            if (req.templateId() != null && !req.templateId().isBlank()) {
                boolean ok = templateRegistry.instantiate(req.templateId(), workspace);
                if (!ok) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Unknown templateId: " + req.templateId());
                }
            } else if (req.aiDraft() != null) {
                writeDraftFiles(workspace, req.aiDraft(), entry);
            } else {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn(
                    "Failed to scaffold workspace for custom agent '{}' at {}: {}",
                    id,
                    workspace,
                    e.getMessage());
        }

        return entry.toDefinition();
    }

    /** Updates an existing custom agent definition. */
    public AgentDefinition updateAgent(String agentId, AgentCreateRequest req) {
        validateRequest(req);
        if (isBuiltin(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Built-in agents cannot be edited via the catalog API");
        }
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry updated =
                new UserAgentDefinitionStore.StoredEntry(
                        agentId,
                        req.name() != null ? req.name() : existing.name(),
                        req.description() != null ? req.description() : existing.description(),
                        req.sysPrompt() != null ? req.sysPrompt() : existing.sysPrompt(),
                        req.model() != null ? req.model() : existing.model(),
                        req.maxIters() != null ? req.maxIters() : existing.maxIters(),
                        req.toolsAllow() != null ? req.toolsAllow() : existing.toolsAllow(),
                        req.toolsDeny() != null ? req.toolsDeny() : existing.toolsDeny(),
                        req.identityName() != null ? req.identityName() : existing.identityName(),
                        req.identityEmoji() != null
                                ? req.identityEmoji()
                                : existing.identityEmoji(),
                        req.groupChatMentionPatterns() != null
                                ? req.groupChatMentionPatterns()
                                : existing.groupChatMentionPatterns(),
                        req.groupChatRequireMention() != null
                                ? req.groupChatRequireMention()
                                : existing.groupChatRequireMention(),
                        req.sandboxMode() != null ? req.sandboxMode() : existing.sandboxMode(),
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope(),
                        req.skillsAllow() != null ? req.skillsAllow() : existing.skillsAllow(),
                        req.skillsDeny() != null ? req.skillsDeny() : existing.skillsDeny(),
                        existing.createdAt(),
                        now,
                        existing.workspacePath()); // workspacePath is creation-only
        store.save(updated);

        // Drop cached gateway registration so the next conversation rebuilds with new definition.
        registeredCustomIds.remove(agentId);

        log.info("Updated custom agent '{}'", agentId);
        return updated.toDefinition();
    }

    /** Deletes a custom agent definition. Built-in agents cannot be deleted. */
    public void deleteAgent(String agentId) {
        if (isBuiltin(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Built-in agents cannot be deleted");
        }
        if (!store.delete(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        registeredCustomIds.remove(agentId);
        log.info("Deleted custom agent '{}'", agentId);
    }

    // -----------------------------------------------------------------
    //  Gateway routing support
    // -----------------------------------------------------------------

    /**
     * Resolves the gateway agent id to use when routing a chat message to the given agent. Both
     * built-in and custom agents are registered under their natural id. For custom agents, this
     * call lazily builds and registers the agent on first invocation.
     *
     * @throws ResponseStatusException 404 if the agent does not exist
     */
    public String resolveGatewayAgentId(String agentId) {
        if (isBuiltin(agentId)) {
            return agentId;
        }
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        return registeredCustomIds.computeIfAbsent(agentId, k -> buildAndRegisterCustom(entry));
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private List<AgentDefinition> builtinDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = bootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : bootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            // HarnessAgent does not expose a public getToolkit(); report standard built-in tools.
            List<String> toolNames =
                    List.of(
                            "filesystem",
                            "shell_execute",
                            "memory_search",
                            "memory_get",
                            "session_search");

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SandboxConfig sc = cfg != null ? cfg.getSandbox() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // built-in sysPrompt is not exposed via the catalog
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sc != null ? sc.getMode() : null,
                            sc != null ? sc.getScope() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
                            0L,
                            0L,
                            cfg != null ? cfg.getWorkspace() : null,
                            true));
        }
        return result;
    }

    private String buildAndRegisterCustom(UserAgentDefinitionStore.StoredEntry entry) {
        Path workspace = workspacePath(entry);

        HarnessAgent.Builder b = HarnessAgent.builder();
        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);
        if (entry.description() != null) b.description(entry.description());
        if (entry.sysPrompt() != null) b.sysPrompt(entry.sysPrompt());
        if (entry.maxIters() != null) b.maxIters(entry.maxIters());
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        b.hook(new ToolNotificationHook(toolEventBus));

        HarnessAgent agent = b.build();
        HarnessGateway gateway = bootstrap.gateway();
        gateway.registerAgent(entry.id(), agent);

        log.info("Registered custom agent in gateway: agentId={}", entry.id());
        return entry.id();
    }

    /**
     * Resolves the on-disk workspace path for a custom agent. If the entry stored a
     * {@code workspacePath}, it is resolved against {@code clawHome} (absolute paths preserved).
     * Otherwise the default {@code ${clawHome}/agents/{id}/workspace} layout is used.
     */
    private Path workspacePath(UserAgentDefinitionStore.StoredEntry entry) {
        Path clawHome = bootstrap.clawHome();
        if (entry.workspacePath() != null && !entry.workspacePath().isBlank()) {
            Path p = Paths.get(entry.workspacePath());
            return p.isAbsolute() ? p.normalize() : clawHome.resolve(p).normalize();
        }
        return ClawBootstrap.defaultAgentWorkspace(clawHome, entry.id());
    }

    /**
     * Trims a user-supplied workspace path. Returns {@code null} for blank input (defaults are
     * resolved at runtime). Absolute paths are passed through. Relative paths reject any
     * {@code ..} traversal segments.
     */
    static String normalizeWorkspacePathInput(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Path p = Paths.get(trimmed);
        if (!p.isAbsolute()) {
            for (Path seg : p) {
                if ("..".equals(seg.toString())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Relative workspace path must not contain '..' segments");
                }
            }
        }
        return trimmed;
    }

    /**
     * Materializes an AI-suggested agent into the workspace folder: {@code AGENTS.md} from
     * {@code (name, description, sysPrompt)}, {@code tools.json} from {@code suggestedTools},
     * one skill file per {@code suggestedSkills} entry, one subagent file per
     * {@code suggestedSubagents} entry, and a {@code memory/.gitkeep}.
     */
    private static void writeDraftFiles(
            Path workspace, AgentDraft draft, UserAgentDefinitionStore.StoredEntry entry)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        String displayName =
                draft.name() != null && !draft.name().isBlank()
                        ? draft.name()
                        : (entry.name() != null ? entry.name() : entry.id());
        String description =
                draft.description() != null && !draft.description().isBlank()
                        ? draft.description()
                        : (entry.description() != null ? entry.description() : "");
        String sysPrompt =
                draft.sysPrompt() != null && !draft.sysPrompt().isBlank()
                        ? draft.sysPrompt()
                        : (entry.sysPrompt() != null
                                ? entry.sysPrompt()
                                : "You are a helpful assistant.");

        StringBuilder agentsMd = new StringBuilder();
        agentsMd.append("# ").append(displayName).append("\n\n");
        if (!description.isEmpty()) {
            agentsMd.append("> ").append(description.trim()).append("\n\n");
        }
        agentsMd.append(sysPrompt.trim()).append("\n");
        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd.toString());

        if (draft.suggestedTools() != null && !draft.suggestedTools().isEmpty()) {
            StringBuilder tools = new StringBuilder();
            tools.append("{\n  \"allow\": [\n");
            for (int i = 0; i < draft.suggestedTools().size(); i++) {
                String t = draft.suggestedTools().get(i);
                if (t == null) continue;
                tools.append("    \"").append(escapeJson(t)).append("\"");
                if (i < draft.suggestedTools().size() - 1) tools.append(",");
                tools.append("\n");
            }
            tools.append("  ],\n  \"deny\": []\n}\n");
            writeIfMissing(workspace.resolve("tools.json"), tools.toString());
        }

        if (draft.suggestedSkills() != null) {
            for (NamedFile sk : draft.suggestedSkills()) {
                if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
                Path skillDir = workspace.resolve("skills").resolve(sanitizeName(sk.name()));
                Files.createDirectories(skillDir);
                writeIfMissing(
                        skillDir.resolve("SKILL.md"), sk.content() != null ? sk.content() : "");
            }
        }

        if (draft.suggestedSubagents() != null) {
            for (NamedFile sa : draft.suggestedSubagents()) {
                if (sa == null || sa.name() == null || sa.name().isBlank()) continue;
                Path file = workspace.resolve("subagents").resolve(sanitizeName(sa.name()) + ".md");
                writeIfMissing(file, sa.content() != null ? sa.content() : "");
            }
        }

        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateRequest(AgentCreateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    // -----------------------------------------------------------------
    //  Request DTOs
    // -----------------------------------------------------------------

    /** Request body for creating or updating a custom agent. */
    public record AgentCreateRequest(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            String sandboxMode,
            String sandboxScope,
            List<String> skillsAllow,
            List<String> skillsDeny,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft) {}

    /** Optional AI-generated draft attached to a creation request. */
    public record AgentDraft(
            String name,
            String description,
            String sysPrompt,
            List<String> suggestedTools,
            List<NamedFile> suggestedSkills,
            List<NamedFile> suggestedSubagents) {}

    /** A named file (e.g. a markdown skill or subagent definition). */
    public record NamedFile(String name, String content) {}
}
