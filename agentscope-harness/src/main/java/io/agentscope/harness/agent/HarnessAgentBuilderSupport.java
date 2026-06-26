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
package io.agentscope.harness.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private static helpers backing {@link HarnessAgent.Builder}'s orchestration path.
 *
 * <p>Each helper accepts a {@link HarnessAgent.Builder} so it can read the builder's harness
 * fields without going through public accessors.
 */
final class HarnessAgentBuilderSupport {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuilderSupport.class);

    private HarnessAgentBuilderSupport() {}

    // -----------------------------------------------------------------
    //  Subagent context section
    // -----------------------------------------------------------------

    // @formatter:off
    /**
     * Subagent context section injected into every subagent's system prompt.
     * Establishes identity, rules, output format, and prohibited behaviours for a leaf worker.
     * The task itself is delivered as the first user message, not duplicated here.
     */
    static final String SUBAGENT_CONTEXT_SECTION =
            """
            # Subagent Context

            You are a **subagent** spawned by the main agent for a specific task.

            ## Your Role
            - Complete the assigned task. That's your entire purpose.
            - You are NOT the main agent. Don't try to be.

            ## Rules
            1. **Stay focused** — Do your assigned task, nothing else
            2. **Complete the task** — Your final message will be automatically reported to the main agent
            3. **Don't initiate** — No heartbeats, no proactive actions, no side quests
            4. **Be ephemeral** — You may be terminated after task completion. That's fine.
            5. **Recover from truncated tool output** — If you see `[truncated: output exceeded context limit]`, re-read only what you need using smaller chunks (read with offset/limit, or targeted grep/head/tail) instead of full re-reads

            ## Output Format
            When complete, your final response should include:
            - What you accomplished or found
            - Any relevant details the main agent should know
            - Keep it concise but informative

            ## What You DON'T Do
            - NO user conversations (that's the main agent's job)
            - NO spawning further subagents — you are a leaf worker
            - NO pretending to be the main agent
            - Return plain text results; let the main agent deliver them to the user
            """;

    // @formatter:on

    static final String GENERAL_PURPOSE_BASE_PROMPT =
            "You are a highly capable general-purpose subagent.";

    /**
     * Builds a system prompt for a subagent by appending {@link #SUBAGENT_CONTEXT_SECTION} to the
     * given base prompt. If the base is blank, only the context section is used.
     */
    static String buildSubagentSysPrompt(String basePrompt) {
        String base =
                (basePrompt != null && !basePrompt.isBlank()) ? basePrompt.stripTrailing() : "";
        return base.isEmpty() ? SUBAGENT_CONTEXT_SECTION : base + "\n\n" + SUBAGENT_CONTEXT_SECTION;
    }

    /** Custom-supplied subagent factory entry: name + factory function from name to Agent. */
    record SubagentFactoryEntry(String name, Function<String, Agent> factory) {}

    // -----------------------------------------------------------------
    //  Filesystem
    // -----------------------------------------------------------------

    static AbstractFilesystem resolveFilesystem(
            HarnessAgent.Builder b,
            Path workspace,
            String agentId,
            WorkspaceIndex workspaceIndex,
            NamespaceFactory nsFactory) {
        if (b.abstractFilesystem != null) {
            return b.abstractFilesystem;
        }
        if (b.remoteFilesystemSpec != null) {
            if (workspaceIndex != null) {
                b.remoteFilesystemSpec.workspaceIndex(workspaceIndex);
            }
            return b.remoteFilesystemSpec.toFilesystem(workspace, agentId, nsFactory);
        }
        if (b.localFilesystemSpec != null) {
            return b.localFilesystemSpec.toFilesystem(workspace, nsFactory);
        }
        // Default: route through LocalFilesystemSpec so the default project (= ${user.dir})
        // is overlaid below the agent workspace, matching the Claude-Code-style two-layer model.
        return new LocalFilesystemSpec().toFilesystem(workspace, nsFactory);
    }

    /**
     * Builds a {@link RuntimeContext} that bakes in the supplied {@code userId} and
     * {@code sessionId} for out-of-band IO performed via
     * {@link HarnessAgent#workspaceFor(String, String)}. Used together with
     * {@code BakedContextFilesystem} so the underlying namespace factories see this identity
     * regardless of what the caller passes downstream.
     */
    static RuntimeContext buildBakedRuntimeContext(String userId, String sessionId) {
        if ((userId == null || userId.isBlank()) && (sessionId == null || sessionId.isBlank())) {
            return RuntimeContext.empty();
        }
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }

    // -----------------------------------------------------------------
    //  Subagents
    // -----------------------------------------------------------------

    /**
     * Builds the subagent entries from programmatic declarations,
     * {@code workspace/subagents/*.md}, and custom factories.
     */
    static List<SubagentEntry> buildSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        List<SubagentDeclaration> allDeclarations = new ArrayList<>(b.subagentDeclarations);

        Path subagentsDir = resolvedWorkspace.resolve("subagents");
        if (Files.isDirectory(subagentsDir)) {
            allDeclarations.addAll(
                    AgentSpecLoader.loadFromDirectory(subagentsDir, resolvedWorkspace));
        }

        List<SubagentEntry> entries = new ArrayList<>();

        entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "General-purpose subagent with same capabilities as the main agent."
                                + " Use for any isolated task that can be fully delegated.",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));

        for (SubagentDeclaration decl : allDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.name(),
                            // custom factory uses Function<String, Agent> — pre-B-0 signature
                            // doesn't accept RuntimeContext. Bridge by ignoring rc here; users
                            // that need parent-aware isolation should register a programmatic
                            // SubagentEntry directly with a B-0 SubagentFactory lambda.
                            (rc) -> custom.factory().apply(custom.name()),
                            null));
        }

        return entries;
    }

    /**
     * Like {@link #buildSubagentEntries(HarnessAgent.Builder, Path, SandboxBackedFilesystem)} but
     * omits the local-disk {@code subagents/} scan. The {@code DynamicSubagentsMiddleware} performs that
     * scan itself on every reasoning step (Layer 2), so feeding the same entries in here would
     * register them twice.
     */
    static List<SubagentEntry> buildStaticSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = new ArrayList<>();

        entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "General-purpose subagent with same capabilities as the main agent."
                                + " Use for any isolated task that can be fully delegated.",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));

        for (SubagentDeclaration decl : b.subagentDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.name(),
                            // custom factory uses Function<String, Agent> — pre-B-0 signature
                            // doesn't accept RuntimeContext. Bridge by ignoring rc here; users
                            // that need parent-aware isolation should register a programmatic
                            // SubagentEntry directly with a B-0 SubagentFactory lambda.
                            (rc) -> custom.factory().apply(custom.name()),
                            null));
        }

        return entries;
    }

    /**
     * Builds a factory for the built-in general-purpose subagent.
     */
    static SubagentFactory buildGeneralPurposeFactory(
            HarnessAgent.Builder b, Path workspace, SandboxBackedFilesystem sandboxFs) {
        final Model capturedModel = b.model;
        final Toolkit capturedParentToolkit = b.toolkit != null ? b.toolkit.copy() : new Toolkit();
        final AbstractFilesystem capturedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final int capturedMaxIters = b.maxIters;
        final ExecutionConfig capturedModelExec = b.modelExecutionConfig;
        final ExecutionConfig capturedToolExec = b.toolExecutionConfig;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        final String capturedEnvMemory = b.environmentMemory;
        final List<Hook> capturedHooks = List.copyOf(b.hooks);
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        final boolean capturedUseLegacyXmlWorkspaceContext = b.useLegacyXmlWorkspaceContext;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final boolean capturedDisableWorkspaceContext = b.disableWorkspaceContext;
        final CompactionConfig capturedCompactionConfig = b.compactionConfig;
        final boolean capturedDisableCompaction = b.disableCompaction;
        final ToolResultEvictionConfig capturedToolResultEvictionConfig =
                b.toolResultEvictionConfig;
        final boolean capturedDisableToolResultEviction = b.disableToolResultEviction;
        final boolean capturedAgentTracingLogEnabled = b.agentTracingLogEnabled;
        final List<String> capturedAdditionalContextFiles = List.copyOf(b.additionalContextFiles);
        final int capturedMaxContextTokens = b.maxContextTokens;
        // Propagate the parent's (distributed) state store so an exposed subagent can be
        // re-materialized on another node / after a restart and still load its conversation
        // history by sessionId. Null in purely local default deployments — children then keep
        // their own local store, preserving legacy behaviour.
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;

        return (RuntimeContext parentRc) -> {
            // general-purpose subagent shares the parent's workspace and is short-lived per spawn;
            // we don't need parent-aware bucketing here. parentRc is accepted for interface
            // compatibility and reserved for future use.
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name("general-purpose-subagent")
                            .description("General-purpose subagent for isolated task execution")
                            .sysPrompt(buildSubagentSysPrompt(null))
                            .model(capturedModel)
                            .toolkit(capturedParentToolkit.copy())
                            .workspace(workspace)
                            .asLeafSubagent()
                            .maxIters(capturedMaxIters)
                            .environmentMemory(capturedEnvMemory)
                            .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                            .enableAgentTracingLog(capturedAgentTracingLogEnabled)
                            .maxContextTokens(capturedMaxContextTokens);

            capturedAdditionalContextFiles.forEach(sub::additionalContextFile);

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();
            if (capturedDisableWorkspaceContext) sub.disableWorkspaceContext();

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }
            if (capturedBackend != null) sub.abstractFilesystem(capturedBackend);
            if (capturedStateStore != null) sub.stateStore(capturedStateStore);
            if (capturedModelExec != null) sub.modelExecutionConfig(capturedModelExec);
            if (capturedToolExec != null) sub.toolExecutionConfig(capturedToolExec);
            if (capturedGenOpts != null) sub.generateOptions(capturedGenOpts);
            if (capturedDisableCompaction) {
                sub.disableCompaction();
            } else if (capturedCompactionConfig != null) {
                sub.compaction(capturedCompactionConfig);
            }
            if (capturedDisableToolResultEviction) {
                sub.disableToolResultEviction();
            } else if (capturedToolResultEvictionConfig != null) {
                sub.toolResultEviction(capturedToolResultEvictionConfig);
            }

            sub.middlewares(capturedMiddlewares);
            sub.hooks(capturedHooks);

            return sub.build();
        };
    }

    /**
     * Builds a factory for a user-declared subagent from a {@link SubagentDeclaration}.
     */
    static SubagentFactory buildDeclaredFactory(
            HarnessAgent.Builder b,
            SubagentDeclaration decl,
            Path mainWorkspace,
            SandboxBackedFilesystem sandboxFs) {
        final Model capturedModel = b.model;
        final Toolkit capturedParentToolkit = b.toolkit != null ? b.toolkit.copy() : new Toolkit();
        final Function<String, Model> capturedResolver = b.modelResolver;
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final AbstractFilesystem capturedSharedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final boolean capturedUseLegacyXmlWorkspaceContext = b.useLegacyXmlWorkspaceContext;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        // Snapshot of main agent's Local filesystem configuration. ISOLATED subagents get a
        // fresh spec carrying the same project / additionalRoots / mode so PathPolicy stays in
        // sync; without this, every isolated subagent would default to project=${user.dir} and
        // lose any --add-dir style allow-list configured at the main level.
        final io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
                capturedLocalFilesystemSpec = b.localFilesystemSpec;
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        // See buildGeneralPurposeFactory: propagate the parent's (distributed) state store so the
        // subagent's conversation survives cross-node re-materialization. Null in local defaults.
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;

        return (RuntimeContext parentRc) -> {
            if (decl.isRemote()) {
                return new io.agentscope.harness.agent.subagent.RemoteSubagentStub(
                        decl.getName(), decl.getDescription());
            }
            // ---- Resolve workspace root ----
            Path runtimeWorkspace = resolveDeclaredWorkspace(decl, mainWorkspace);

            // ---- Resolve system prompt ----
            String sysPromptBase = resolveDeclaredSysPromptBase(decl);

            // ---- Resolve model ----
            Model effectiveModel =
                    resolveModel(decl.getModel(), capturedModel, capturedResolver, decl.getName());

            // ---- Derive child session ID: bucket persisted AgentState by parent identity ----
            // (Phase B-0) Without this every (user, parent-session) shares the same bucket and
            // can read each other's subagent conversations through AgentStateStore.get(...).
            String childSessionId = deriveChildSessionId(decl, parentRc);

            // ---- Build child agent ----
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name(decl.getName())
                            .description(decl.getDescription())
                            .model(effectiveModel)
                            .toolkit(
                                    allowlistedInheritedToolkit(
                                            capturedParentToolkit, decl.getTools()))
                            .workspace(runtimeWorkspace)
                            .defaultSessionId(childSessionId)
                            .maxIters(decl.getSteps())
                            .asLeafSubagent()
                            .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                            .sysPrompt(buildSubagentSysPrompt(sysPromptBase));

            // Overlay declaration-specified temperature/topP on top of the parent's
            // GenerateOptions.
            // Null fields in the overlay fall back to parent via GenerateOptions.mergeOptions.
            // Note: SubagentDeclaration.variant is parsed and retained on the declaration but is
            // NOT plumbed into GenerateOptions today because the core Model layer has no variant
            // concept; treat it as schema-forward storage until variant support lands.
            if (decl.getTemperature() != null || decl.getTopP() != null) {
                GenerateOptions overlay =
                        GenerateOptions.builder()
                                .temperature(decl.getTemperature())
                                .topP(decl.getTopP())
                                .build();
                sub.generateOptions(GenerateOptions.mergeOptions(overlay, capturedGenOpts));
            } else if (capturedGenOpts != null) {
                sub.generateOptions(capturedGenOpts);
            }

            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED && capturedSharedBackend != null) {
                sub.abstractFilesystem(capturedSharedBackend);
            } else if (decl.getWorkspaceMode() != WorkspaceMode.SHARED
                    && capturedLocalFilesystemSpec != null) {
                sub.filesystem(cloneLocalSpecForSubagent(capturedLocalFilesystemSpec));
            }

            if (capturedStateStore != null) {
                sub.stateStore(capturedStateStore);
            }

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }

            List<String> skillAllowlist = decl.getSkills();
            if (!skillAllowlist.isEmpty()) {
                sub.skillFilter(SkillFilter.only(skillAllowlist.toArray(new String[0])));
            }

            sub.middlewares(capturedMiddlewares);
            return sub.build();
        };
    }

    /**
     * Builds a fresh {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec} for
     * an ISOLATED subagent, copying the main agent's project, mode, and additionalRoots so the
     * subagent's {@code PathPolicy} stays consistent with the parent. The subagent gets its own
     * workspace (passed separately via {@code sub.workspace(...)}) so its MEMORY/sessions stay
     * isolated; only the allow-list inputs are shared.
     */
    private static io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
            cloneLocalSpecForSubagent(
                    io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec parent) {
        io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec spec =
                new io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec();
        if (parent.getProject() != null) {
            spec.project(parent.getProject());
        }
        if (parent.getMode() != null) {
            spec.mode(parent.getMode());
        }
        spec.additionalRoots(parent.getAdditionalRoots());
        return spec;
    }

    /** Returns a defensive copy of inherited parent tools filtered by the optional allowlist. */
    static Toolkit allowlistedInheritedToolkit(Toolkit parentToolkit, List<String> allowlist) {
        Toolkit toolkit = parentToolkit != null ? parentToolkit.copy() : new Toolkit();
        if (allowlist == null || allowlist.isEmpty()) {
            return toolkit;
        }
        List<String> toRemove =
                toolkit.getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .filter(name -> !allowlist.contains(name))
                        .toList();
        toRemove.forEach(toolkit::removeTool);
        return toolkit;
    }

    /**
     * Composes the child agent's persisted session ID, bucketing by declaration name and the
     * spawn-time parent identity:
     *
     * <pre>
     * {declarationName}[@{parentSessionId}][#{userId}]
     * </pre>
     *
     * <p>Empty / blank uid or sid segments are dropped — keeps single-tenant demos working with
     * the legacy single-bucket form. {@link WorkspaceMode#SHARED} subagents intentionally fall
     * back to the legacy single-bucket form: they're sharing the parent's full state tree by
     * design.
     *
     * <p>This works uniformly across {@link io.agentscope.core.state.AgentStateStore} stores —
     * Workspace, Redis, InMemory, or custom — because all of them bucket {@code save}/{@code get}
     * by session ID. (Phase B-0)
     */
    static String deriveChildSessionId(SubagentDeclaration decl, RuntimeContext parentRc) {
        String declName = decl.getName();
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED || parentRc == null) {
            return declName;
        }
        String sid = sanitizeIdentifier(parentRc.getSessionId());
        String uid = sanitizeIdentifier(parentRc.getUserId());
        if (sid == null && uid == null) {
            return declName;
        }
        StringBuilder sb = new StringBuilder(declName);
        if (sid != null) sb.append('@').append(sid);
        if (uid != null) sb.append('#').append(uid);
        return sb.toString();
    }

    /**
     * Returns {@code null} when the input is null or blank; otherwise replaces characters that
     * confuse path-based AgentStateStore stores (slashes, backslashes, whitespace, controls) with
     * underscores. Keeps Redis/InMemory/SQL keys unaffected since their stored form is opaque.
     */
    static String sanitizeIdentifier(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[/\\\\\\s\\p{Cntrl}]", "_");
    }

    /**
     * Resolves the runtime workspace root for a declared subagent. Creates the auto-generated
     * isolated directory when needed.
     */
    static Path resolveDeclaredWorkspace(SubagentDeclaration decl, Path mainWorkspace) {
        if (decl.getWorkspacePath() != null) {
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
                return mainWorkspace;
            }
            return decl.getWorkspacePath();
        }
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
            return mainWorkspace;
        }
        // ISOLATED + no path: auto-create agents/<name>/workspace/
        Path isolated =
                mainWorkspace.resolve("agents").resolve(decl.getName()).resolve("workspace");
        try {
            Files.createDirectories(isolated);
        } catch (Exception e) {
            log.warn(
                    "Failed to create isolated workspace for subagent '{}' at {}: {}",
                    decl.getName(),
                    isolated,
                    e.getMessage());
        }
        return isolated;
    }

    /** Resolves the system-prompt base for a declared subagent. */
    static String resolveDeclaredSysPromptBase(SubagentDeclaration decl) {
        if (decl.getWorkspacePath() != null) {
            Path agentsMd = decl.getWorkspacePath().resolve("AGENTS.md");
            if (Files.isRegularFile(agentsMd)) {
                try {
                    return Files.readString(agentsMd, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn(
                            "Failed to read AGENTS.md for subagent '{}' from {}: {}",
                            decl.getName(),
                            agentsMd,
                            e.getMessage());
                }
            }
            return "";
        }
        String inline = decl.getInlineAgentsBody();
        return (inline != null) ? inline : "";
    }

    /** Resolves the effective {@link Model} for a subagent, applying the optional override. */
    static Model resolveModel(
            String modelOverride,
            Model parentModel,
            Function<String, Model> resolver,
            String subagentName) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return parentModel;
        }
        Function<String, Model> effectiveResolver =
                resolver != null ? resolver : ModelRegistry::resolve;
        if (ModelRegistry.canResolve(modelOverride) || resolver != null) {
            try {
                Model resolved = effectiveResolver.apply(modelOverride);
                if (resolved != null) {
                    log.debug(
                            "Subagent '{}' using overridden model: {}",
                            subagentName,
                            modelOverride);
                    return resolved;
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to resolve model '{}' for subagent '{}', falling back to parent"
                                + " model: {}",
                        modelOverride,
                        subagentName,
                        e.getMessage());
            }
        }
        return parentModel;
    }

    // -----------------------------------------------------------------
    //  Subagents middlewares
    // -----------------------------------------------------------------

    static SubagentsMiddleware buildSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = buildSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        if (b.externalSubagentTool != null) {
            return new SubagentsMiddleware(entries, b.externalSubagentTool, repo);
        }

        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        return new SubagentsMiddleware(entries, repo, wsManager, fs, workspace, factoryFn);
    }

    static DynamicSubagentsMiddleware buildDynamicSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> staticEntries = buildStaticSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        DefaultAgentManager manager = new DefaultAgentManager(staticEntries, wsManager);
        return new DynamicSubagentsMiddleware(
                staticEntries, fs, workspace, factoryFn, manager, b.externalSubagentTool, repo);
    }

    private static TaskRepository resolveTaskRepository(
            HarnessAgent.Builder b, WorkspaceManager wsManager) {
        if (b.taskRepository != null) {
            return b.taskRepository;
        }
        Objects.requireNonNull(
                wsManager,
                "WorkspaceManager must be non-null when resolving the default TaskRepository;"
                    + " HarnessAgent.build() always constructs one. Pass an explicit"
                    + " .taskRepository(...) if you need a non-workspace-backed implementation.");
        String taskAgentId =
                b.agentId != null && !b.agentId.isBlank()
                        ? b.agentId
                        : (b.name != null && !b.name.isBlank() ? b.name : "ReActAgent");
        return new WorkspaceTaskRepository(wsManager, taskAgentId);
    }

    // -----------------------------------------------------------------
    //  Skills
    // -----------------------------------------------------------------

    /**
     * Assembles the ordered list of skill repositories used by this build (low-to-high priority).
     */
    static List<AgentSkillRepository> composeSkillRepositories(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            AbstractFilesystem filesystem,
            Supplier<RuntimeContext> currentRcSupplier) {
        List<AgentSkillRepository> ordered = new ArrayList<>();

        // Layer 1 (lowest priority): project-global skills directory.
        if (b.projectGlobalSkillsDir != null && Files.isDirectory(b.projectGlobalSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(b.projectGlobalSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to register project-global skills dir {}: {}",
                        b.projectGlobalSkillsDir,
                        e.getMessage());
            }
        }

        // Layer 2: marketplace repositories (user-supplied).
        ordered.addAll(b.skillRepositories);

        // Layer 3: workspace agent-shared directory.
        Path workspaceSkillsDir = wsManager.getSkillsDir();
        if (workspaceSkillsDir != null && Files.isDirectory(workspaceSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(workspaceSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to load workspace skills from {}: {}",
                        workspaceSkillsDir,
                        e.getMessage());
            }
        }

        // Layer 4 (highest priority): per-user namespaced filesystem view, via the new
        // WorkspaceSkillRepository (replaces legacy FilesystemBackedSkillRepository).
        // Skipped when the user opts out with disableDefaultWorkspaceSkills().
        if (filesystem != null && !b.disableDefaultWorkspaceSkills) {
            ordered.add(
                    new io.agentscope.harness.agent.skill.WorkspaceSkillRepository(
                            filesystem,
                            "skills",
                            currentRcSupplier,
                            "workspace-namespaced",
                            false));
        }

        return ordered;
    }

    /**
     * Eagerly assembles a static {@link SkillBox} from {@code repos} (low-to-high priority) so
     * callers using {@code disableDynamicSkills()} keep the legacy {@code SkillHook} path while
     * still benefiting from the additive composition.
     */
    static SkillBox staticSkillBoxFromRepos(
            List<AgentSkillRepository> repos, Toolkit agentToolkit) {
        LinkedHashMap<String, AgentSkill> merged = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repos) {
            try {
                List<AgentSkill> skills = repo.getAllSkills();
                if (skills == null) {
                    continue;
                }
                for (AgentSkill skill : skills) {
                    if (skill != null && skill.getName() != null) {
                        merged.put(skill.getName(), skill);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to load skills from {}: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        SkillBox box = new SkillBox(agentToolkit);
        for (AgentSkill skill : merged.values()) {
            box.registerSkill(skill);
        }
        log.info("Loaded {} skills from {} repositories (static)", merged.size(), repos.size());
        return box;
    }
}
