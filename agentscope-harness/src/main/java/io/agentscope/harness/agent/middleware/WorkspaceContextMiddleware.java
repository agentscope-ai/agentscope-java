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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Collects workspace instructions and reference materials for final request rendering via
 * {@link #onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
 *
 * <p>Memory-related guidance and reference material loading are gated by the same
 * builder flags as Harness memory tools/hooks ({@code disableMemoryTools} /
 * {@code disableMemoryHooks}) so the model is not instructed to use capabilities that are
 * turned off.
 */
public class WorkspaceContextMiddleware implements HarnessRuntimeMiddleware {

    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## Runtime Environment
            You are the %s agent.
            The host operating system is: %s
            The host workspace directory is: %s
            The host JVM temporary directory is: %s
            %s
            """;

    private static final String DOMAIN_KNOWLEDGE_GUIDANCE =
            """
            ## Domain Knowledge
            Use the available knowledge reference material in HARNESS_CONTEXT for specs, procedures, schemas, and domain facts relevant to the task.
            """;

    private static final String KNOWLEDGE_FILES_GUIDANCE =
            """
            The knowledge file catalog lists reference paths under `knowledge/`. Use the catalog in HARNESS_CONTEXT when present; if omitted for budget, inspect `knowledge/` with targeted tools. Locate needed paths with grep_files or glob_files, then read_file for relevant content.
            """;

    private static final String MEMORY_RECALL_GUIDANCE =
            """
            ## Memory Recall
            Before answering questions about prior work, decisions, dates, people, or preferences: \
            run memory_search on MEMORY.md + memory/*.md, then memory_get for needed lines. \
            Include Source: <path#line> citations when helpful.
            """;

    private static final String MEMORY_PERSISTENCE_HEADER =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md. Update it proactively when:
            - User shares preferences, project context, or decisions
            - Important outcomes or action items are established
            """;

    private static final String MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md that the harness maintains automatically.
            """;

    private static final String MEMORY_SAVE_TOOL_GUIDANCE =
            """
            Use the **memory_save** tool to persist memories — it atomically updates \
            both MEMORY.md and the daily ledger. Do NOT use write_file or edit_file on \
            MEMORY.md or any path under memory/ — always use memory_save instead.
            """;

    private static final String MEMORY_WRITE_FILE_GUARD =
            """
            Do NOT use write_file or edit_file on MEMORY.md or any path under memory/ — \
            the harness owns those files.
            """;

    private static final String MEMORY_AUTO_EXTRACT_GUIDANCE =
            "Memory is also automatically extracted at conversation end.\n";

    private static final String TRUNCATION_NOTICE_WITH_SEARCH =
            "\n\n... (memory truncated — use memory_search for older entries) ...\n";

    private static final String TRUNCATION_NOTICE_PLAIN = "\n\n... (memory truncated) ...\n";

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final WorkspaceManager workspaceManager;
    private final String agentName;
    private final String environmentMemory;
    private final int maxContextTokens;
    private final boolean disableMemoryTools;
    private final boolean disableMemoryHooks;
    private List<String> additionalContextFiles = List.of();
    private boolean artifactDeliveryEnabled = false;

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, "HarnessAgent", null, DEFAULT_MAX_CONTEXT_TOKENS, false, false);
    }

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager, int maxContextTokens) {
        this(workspaceManager, "HarnessAgent", null, maxContextTokens, false, false);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens) {
        this(workspaceManager, agentName, environmentMemory, maxContextTokens, false, false);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens,
            boolean disableMemoryTools,
            boolean disableMemoryHooks) {
        this.workspaceManager = workspaceManager;
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "HarnessAgent";
        this.environmentMemory = environmentMemory;
        this.maxContextTokens = maxContextTokens;
        this.disableMemoryTools = disableMemoryTools;
        this.disableMemoryHooks = disableMemoryHooks;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    public void setAdditionalContextFiles(List<String> files) {
        this.additionalContextFiles = files != null ? files : List.of();
    }

    /**
     * Whether memory tools are disabled for this middleware (affects prompt guidance).
     */
    public boolean isDisableMemoryTools() {
        return disableMemoryTools;
    }

    /**
     * Whether memory hooks are disabled for this middleware (affects prompt guidance).
     */
    public boolean isDisableMemoryHooks() {
        return disableMemoryHooks;
    }

    /**
     * Whether an {@link io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget} is configured
     * and the {@code deliver_artifact} tool is exposed. When {@code true}, the sandbox branch of the
     * workspace paragraph tells the model to use that tool; when {@code false}, it does not promise
     * an exported artifact.
     */
    public void setArtifactDeliveryEnabled(boolean artifactDeliveryEnabled) {
        this.artifactDeliveryEnabled = artifactDeliveryEnabled;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
                            String base = currentPrompt != null ? currentPrompt : "";
                            collectWorkspaceMaterials(rc);
                            return base;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void collectWorkspaceMaterials(RuntimeContext rc) {
        String agentsContent = composeAgentsContent(workspaceManager.readAgentsMd(rc));
        boolean includeMemoryContext = includeMemoryContext();
        String memoryContent =
                includeMemoryContext ? workspaceManager.readMemoryMd(rc).strip() : "";
        String knowledgeContent = workspaceManager.readKnowledgeMd(rc).strip();
        List<Path> knowledgeFiles = workspaceManager.listKnowledgeFiles(rc);
        Path workspace = workspaceManager.getWorkspace();
        AbstractFilesystem filesystem = workspaceManager.getFilesystem();
        Path effectiveWorkspace =
                detectLocalUpper(filesystem) != null
                        ? workspaceManager.resolveRuntimeDataPath(rc, "")
                        : workspace;
        String sessionContext = buildSessionContextSection(effectiveWorkspace);

        String knowledgeBlock = buildKnowledgeBlock(knowledgeContent, knowledgeFiles, workspace);
        String additionalBlock = buildAdditionalContextBlock(rc);

        int fixedTokens =
                estimateTokens(sessionContext)
                        + estimateTokens(agentsContent)
                        + estimateTokens(knowledgeBlock)
                        + estimateTokens(additionalBlock);
        if (includeMemoryContext) {
            int memoryTokens = estimateTokens(memoryContent);
            int available = maxContextTokens - fixedTokens;
            if (available <= 0) {
                memoryContent = "";
            } else if (memoryTokens > available) {
                memoryContent = truncateToTokenBudget(memoryContent, available);
            }
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(
                        rc, workspace, effectiveWorkspace, filesystem, artifactDeliveryEnabled);
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        ContextItem.instruction(
                                "project_rules", "workspace:AGENTS.md", agentsContent),
                        ContextItem.instruction(
                                "working_principles",
                                "harness:workspace-guidance",
                                buildGuidance(knowledgeContent, knowledgeFiles)),
                        ContextItem.instruction(
                                "environment",
                                "harness:workspace-environment",
                                sessionContext + "\n" + workspaceParagraph),
                        ContextItem.runtimeState(
                                "session_state",
                                "harness:session-state",
                                false,
                                buildSessionStateSection(rc)),
                        new ContextItem("memory", "workspace:MEMORY.md", memoryContent),
                        new ContextItem("knowledge", "workspace:knowledge", knowledgeBlock),
                        new ContextItem(
                                "additional", "workspace:additional-files", additionalBlock)));
    }

    /** Head-truncation cap for AGENTS.md content, in UTF-8 bytes. */
    private static final int MAX_AGENTS_MD_BYTES = 100 * 1024;

    /**
     * Caps the rule body at {@link #MAX_AGENTS_MD_BYTES} UTF-8 bytes and prefixes the
     * applicability line so the note travels with the rules instead of a distant block.
     */
    private static String composeAgentsContent(String raw) {
        String content = truncateUtf8(raw.strip(), MAX_AGENTS_MD_BYTES);
        if (content.isEmpty()) {
            return "";
        }
        return "Apply the AGENTS.md persona and project conventions relevant to this task."
                + " They do not override system, developer, or direct"
                + " user instructions.\n\n"
                + content;
    }

    /**
     * Head-truncates to {@code maxBytes} UTF-8 bytes and appends a visible truncation marker.
     * Encodes into a fixed-size buffer; the encoder stops before a code point that no longer
     * fits, so the head always decodes back without splitting a character.
     */
    private static String truncateUtf8(String content, int maxBytes) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        ByteBuffer out = ByteBuffer.allocate(maxBytes);
        if (encoder.encode(CharBuffer.wrap(content), out, true).isUnderflow()) {
            return content;
        }
        return new String(out.array(), 0, out.position(), StandardCharsets.UTF_8)
                + "\n\n[File truncated: AGENTS.md]";
    }

    /**
     * Inject {@code MEMORY.md} unless both memory tools and hooks are disabled — at that point
     * the harness memory surface is fully off and the file should not appear as model context.
     */
    private boolean includeMemoryContext() {
        return !(disableMemoryTools && disableMemoryHooks);
    }

    private String buildGuidance(String knowledgeContent, List<Path> knowledgeFiles) {
        StringBuilder sb = new StringBuilder();
        if (!knowledgeContent.isBlank() || !knowledgeFiles.isEmpty()) {
            sb.append(DOMAIN_KNOWLEDGE_GUIDANCE.strip()).append("\n\n");
            if (!knowledgeFiles.isEmpty()) {
                sb.append(KNOWLEDGE_FILES_GUIDANCE.strip()).append("\n\n");
            }
        }
        if (!disableMemoryTools) {
            sb.append(MEMORY_RECALL_GUIDANCE.strip()).append("\n\n");
        }
        String persistence = buildMemoryPersistenceGuidance();
        if (!persistence.isBlank()) {
            sb.append(persistence.strip()).append("\n\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private String buildMemoryPersistenceGuidance() {
        if (disableMemoryTools && disableMemoryHooks) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!disableMemoryTools) {
            sb.append(MEMORY_PERSISTENCE_HEADER.strip()).append("\n");
            sb.append(MEMORY_SAVE_TOOL_GUIDANCE.strip()).append("\n");
        } else {
            sb.append(MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY.strip()).append("\n");
            sb.append(MEMORY_WRITE_FILE_GUARD.strip()).append("\n");
        }
        if (!disableMemoryHooks) {
            sb.append(MEMORY_AUTO_EXTRACT_GUIDANCE.strip()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Builds the {@code ## Workspace} paragraph, branching by the active filesystem type so the
     * LLM sees a description that matches its real deployment surface.
     *
     * <ul>
     *   <li><b>Local overlay</b> ({@link OverlayFilesystem} wrapping
     *       {@link LocalFilesystemWithShell}) — renders Project + Workspace as two lines plus
     *       overlay/shell semantics.
     *   <li><b>Sandbox</b> ({@link AbstractSandboxFilesystem} not wrapped in an overlay) —
     *       describes the configured execution backend and its verified workspace root.
     *   <li><b>Remote</b> ({@link CompositeFilesystem}) — describes the distributed store-backed
     *       workspace and the fact that there is no host filesystem to fall back to.
     *   <li><b>Other</b> — workspace root for file operations on plain
     *       {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} or anything we
     *       don't recognize.
     * </ul>
     */
    private static String buildWorkspaceParagraph(
            RuntimeContext rc,
            Path workspace,
            Path effectiveWorkspace,
            AbstractFilesystem fs,
            boolean artifactDeliveryEnabled) {
        StringBuilder sb = new StringBuilder("## Workspace\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("Project (the user's source tree you're assisting with): ")
                    .append(project.toAbsolutePath())
                    .append("\n");
            sb.append("Workspace (your home base — memory, sessions, skills, runtime data): ")
                    .append(effectiveWorkspace.toAbsolutePath())
                    .append("\n");
            List<Path> extraRoots = extraRootsOf(localUpper, project, workspace);
            if (!extraRoots.isEmpty()) {
                sb.append("Additional roots: ");
                for (int i = 0; i < extraRoots.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(extraRoots.get(i).toAbsolutePath());
                }
                sb.append("\n");
            }
            LocalFsMode mode = localUpper.getMode();
            sb.append("Path access policy: ")
                    .append(describeMode(mode))
                    .append(". File tools reject absolute paths outside the roots above")
                    .append(mode == LocalFsMode.ROOTED ? " with a security error" : "")
                    .append(".\n");
            if (fs instanceof ProjectAwareOverlay) {
                sb.append(
                        "File tools write project files (code, configs, etc.) to the project"
                                + " directory. Workspace metadata paths (memory, sessions, skills,"
                                + " agents, knowledge) are written to the workspace.\n");
            } else {
                sb.append(
                        "Relative paths resolve under the workspace and read-fall-back to"
                                + " the project (overlay copy-on-write).\n");
            }
            sb.append("Shell commands start in the project directory.\n");
        } else if (fs instanceof LocalFilesystemWithShell local) {
            if (local.getShellCwd() != null) {
                sb.append("Shell working directory on the host: ")
                        .append(local.getShellCwd().toAbsolutePath())
                        .append('\n');
            } else {
                sb.append("Local filesystem root on the host: ")
                        .append(local.getCwd().toAbsolutePath())
                        .append(
                                "\nShell commands run on the host in the filesystem's per-call"
                                        + " working directory.\n");
            }
        } else if (fs instanceof AbstractSandboxFilesystem && !(fs instanceof OverlayFilesystem)) {
            sb.append(
                    "File and shell paths follow the configured execution backend; host paths"
                            + " above do not establish its working directory. Use that backend's"
                            + " shell syntax, installed runtimes, and temporary paths.\n");
            SandboxAcquireResult acquired = rc.get(SandboxAcquireResult.class);
            if (acquired != null && !acquired.isReleased()) {
                SandboxState state = acquired.getSandbox().getState();
                WorkspaceSpec spec = state != null ? state.getWorkspaceSpec() : null;
                if (spec != null && spec.getRoot() != null && !spec.getRoot().isBlank()) {
                    sb.append("Sandbox root: ").append(spec.getRoot()).append('\n');
                }
            }
            if (fs instanceof RoutedSandboxFilesystem) {
                sb.append(
                        "Shell commands use the primary backend; mounted file paths may use"
                                + " other backends.\n");
            }
            if (!artifactDeliveryEnabled) {
                sb.append(
                        "No artifact delivery tool is configured. Files remain in the configured"
                                + " execution backend; describe their location and any retrieval"
                                + " work still needed.\n");
            }
        } else if (fs instanceof CompositeFilesystem) {
            sb.append("Distributed workspace template root: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Runtime data (MEMORY.md, sessions, tasks, skills) lives in a shared remote"
                            + " store, not on the local host. Reads of project-authored template"
                            + " files fall back to the workspace template root above.\n");
        } else {
            sb.append("Workspace root for file operations: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append("Use workspace-relative paths for file operations.\n");
        }
        if (localUpper != null || fs instanceof LocalFilesystemWithShell) {
            sb.append("Local shell: ")
                    .append(
                            System.getProperty("os.name").toLowerCase().contains("win")
                                    ? "cmd.exe /c"
                                    : "sh -c")
                    .append(" on the host.\n");
        }
        if (artifactDeliveryEnabled
                && fs instanceof AbstractSandboxFilesystem
                && !(fs instanceof OverlayFilesystem)) {
            sb.append(
                    "**Artifact Delivery**\n"
                            + "Automatically call deliver_artifact for final deliverables to export"
                            + " them to the configured external destination; do not ask whether to"
                            + " deliver. Keep temporary working"
                            + " files and sensitive material internal. Successful deliveries are"
                            + " shown by the tool; keep your reply focused on task results. Report"
                            + " any failed delivery as incomplete. An internal path alone does not"
                            + " make a file available to the user.\n");
        }
        return sb.toString();
    }

    /**
     * Best-effort: returns the upper {@link LocalFilesystemWithShell} when {@code fs} is an
     * overlay constructed by {@code LocalFilesystemSpec}, otherwise {@code null}. Used to pull
     * project / mode / policy metadata for the prompt without leaking those into other
     * filesystem types.
     */
    private static LocalFilesystemWithShell detectLocalUpper(AbstractFilesystem fs) {
        if (fs instanceof OverlayFilesystem ov
                && ov.getUpper() instanceof LocalFilesystemWithShell lfs) {
            return lfs;
        }
        return null;
    }

    /**
     * Extra allow-list roots beyond the project and workspace (which the LLM already sees).
     * Filters out exact matches and ancestors to keep the prompt focused on truly additional
     * locations.
     */
    private static List<Path> extraRootsOf(
            LocalFilesystemWithShell upper, Path project, Path workspace) {
        PathPolicy policy = upper.getPathPolicy();
        if (policy == null || policy.isEmpty()) {
            return List.of();
        }
        Path projectAbs = project.toAbsolutePath().normalize();
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        List<Path> extras = new java.util.ArrayList<>();
        for (Path root : policy.roots()) {
            if (root.equals(projectAbs) || root.equals(workspaceAbs)) {
                continue;
            }
            extras.add(root);
        }
        return extras;
    }

    private static String describeMode(LocalFsMode mode) {
        if (mode == null) {
            return "ROOTED (default)";
        }
        return switch (mode) {
            case SANDBOXED -> "SANDBOXED (all paths anchored to the workspace; `..` blocked)";
            case ROOTED -> "ROOTED (absolute paths accepted only inside the roots above)";
            case UNRESTRICTED -> "UNRESTRICTED (absolute paths pass through unchanged)";
        };
    }

    private String buildSessionContextSection(Path workspace) {
        String platform = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String tempDir = System.getProperty("java.io.tmpdir");
        String environmentMemoryPart =
                environmentMemory != null && !environmentMemory.isBlank() ? environmentMemory : "";

        return String.format(
                        SESSION_CONTEXT_SECTION_TEMPLATE,
                        agentName,
                        platform,
                        workspace.toAbsolutePath(),
                        tempDir,
                        environmentMemoryPart)
                .strip();
    }

    /**
     * Per-call volatile facts for the tail runtime channel: the session id and the resolved date.
     * Both change independently of the agent's identity and instructions (a long session crosses
     * midnight), so they must not sit in the byte-stable system head.
     */
    private String buildSessionStateSection(RuntimeContext rc) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE MMM d, yyyy"));
        StringBuilder sb = new StringBuilder();
        if (rc != null && rc.getSessionId() != null) {
            sb.append("AgentStateStore session id: ").append(rc.getSessionId()).append('\n');
        }
        sb.append("Today's date: ")
                .append(today)
                .append(" (resolved at the start of this conversation turn)\n");
        return sb.toString();
    }

    private static String indentByTwo(String text) {
        return text.lines().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }

    private String buildAdditionalContextBlock(RuntimeContext rc) {
        if (additionalContextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String relPath : additionalContextFiles) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relPath);
            if (content != null && !content.isBlank()) {
                String tag = relPath.replace("/", "_").replace(".", "_").toLowerCase();
                sb.append("  <").append(tag).append(">\n");
                sb.append(indentByTwo(content.strip())).append("\n");
                sb.append("  </").append(tag).append(">\n");
            }
        }
        return sb.toString();
    }

    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : text.length() / 4;
    }

    private String truncateToTokenBudget(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        String notice =
                disableMemoryTools ? TRUNCATION_NOTICE_PLAIN : TRUNCATION_NOTICE_WITH_SEARCH;
        if (notice.length() >= maxChars) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - notice.length()) + notice;
    }

    private String buildKnowledgeBlock(
            String knowledgeContent, List<Path> knowledgeFiles, Path workspace) {
        StringBuilder sb = new StringBuilder();

        if (!knowledgeContent.isBlank()) {
            sb.append(knowledgeContent.strip()).append("\n");
        }

        if (!knowledgeFiles.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("Knowledge files:\n");
            sb.append(
                    knowledgeFiles.stream()
                            .map(f -> "- " + workspace.relativize(f))
                            .collect(Collectors.joining("\n")));
            sb.append("\n");
        }

        return sb.toString();
    }
}
