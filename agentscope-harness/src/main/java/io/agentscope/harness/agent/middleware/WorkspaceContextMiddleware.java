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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Appends workspace context (session info, AGENTS.md, MEMORY.md, knowledge) to the
 * system prompt via {@link #onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
 *
 * <p>Memory-related guidance and {@code <memory_context>} injection are gated by the same
 * builder flags as Harness memory tools/hooks ({@code disableMemoryTools} /
 * {@code disableMemoryHooks}) so the model is not instructed to use capabilities that are
 * turned off.
 */
public class WorkspaceContextMiddleware implements HarnessRuntimeMiddleware {

    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## AgentStateStore Context
            This is the %s. We are setting up the context for our chat.
            Today's date is %s.
            My operating system is: %s
            The workspace directory is: %s
            The project's temporary directory is: %s
            %s
            """;

    private static final String DOMAIN_KNOWLEDGE_GUIDANCE =
            """
            ## Domain Knowledge
            The workspace `knowledge/` tree holds many detailed reference documents (not only a single summary file). When the task needs specs, procedures, schemas, or domain facts, treat that directory as the source of truth.
            Below, `<domain_knowledge_context>` already includes what you need to navigate it: injected `knowledge/KNOWLEDGE.md` (if present) plus a **full list of knowledge file paths** under `knowledge/` — use that as the catalog of what exists and where.
            For content not inlined here, open only the paths you need with read_file, grep, or glob (prefer targeted reads over loading entire trees into the reply).
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

    private static final String WORKSPACE_FILES_NOTICE_WITH_MEMORY =
            """
            ## Workspace Files (Injected)
            The following <loaded_context> was loaded in from files in your workspace.
            These files (for example, `AGENTS.md`, `MEMORY.md`, and `knowledge/KNOWLEDGE.md`) contain memory, facts, preferences, guidelines, and user-specific details learned from prior interactions with user.
            """;

    private static final String WORKSPACE_FILES_NOTICE_WITHOUT_MEMORY =
            """
            ## Workspace Files (Injected)
            The following <loaded_context> was loaded in from files in your workspace.
            These files (for example, `AGENTS.md` and `knowledge/KNOWLEDGE.md`) contain guidelines and domain context for this agent.
            """;

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
     * workspace paragraph tells the model to use that tool; when {@code false}, it states that files
     * cannot leave the sandbox.
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
                            String section = buildWorkspaceSection(rc);
                            String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
                            return base + separator + section;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String buildWorkspaceSection(RuntimeContext rc) {
        String agentsContent = workspaceManager.readAgentsMd(rc).strip();
        boolean includeMemoryContext = includeMemoryContext();
        String memoryContent =
                includeMemoryContext ? workspaceManager.readMemoryMd(rc).strip() : "";
        String knowledgeContent = workspaceManager.readKnowledgeMd(rc).strip();
        Path workspace = workspaceManager.getWorkspace();
        String sessionContext = buildSessionContextSection(workspace, rc);

        String knowledgeBlock = buildKnowledgeBlock(rc, knowledgeContent, workspace);
        String additionalBlock = buildAdditionalContextBlock(rc);

        int fixedTokens =
                estimateTokens(sessionContext)
                        + estimateTokens(agentsContent)
                        + estimateTokens(knowledgeBlock)
                        + estimateTokens(additionalBlock);
        if (includeMemoryContext) {
            int memoryTokens = estimateTokens(memoryContent);
            int available = maxContextTokens - fixedTokens;
            if (available > 0 && memoryTokens > available) {
                memoryContent = truncateToTokenBudget(memoryContent, available);
            }
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(
                        workspace, workspaceManager.getFilesystem(), artifactDeliveryEnabled);
        String loadedContext =
                buildLoadedContextSection(
                        agentsContent, memoryContent, knowledgeBlock, additionalBlock);
        return assembleSection(sessionContext, buildGuidance(), workspaceParagraph, loadedContext);
    }

    /**
     * Inject {@code MEMORY.md} unless both memory tools and hooks are disabled — at that point
     * the harness memory surface is fully off and the file should not appear as model context.
     */
    private boolean includeMemoryContext() {
        return !(disableMemoryTools && disableMemoryHooks);
    }

    private String buildGuidance() {
        StringBuilder sb = new StringBuilder();
        sb.append(DOMAIN_KNOWLEDGE_GUIDANCE.strip()).append("\n\n");
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

    private static String assembleSection(
            String sessionContext,
            String guidance,
            String workspaceParagraph,
            String loadedContextSection) {
        StringBuilder sb = new StringBuilder();
        if (!sessionContext.isBlank()) {
            sb.append(sessionContext).append("\n\n");
        }
        sb.append(guidance);
        if (!workspaceParagraph.isEmpty()) {
            sb.append("\n").append(workspaceParagraph);
        }
        sb.append("\n").append(loadedContextSection);
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
     *       describes the isolated container view and how host files reach it.
     *   <li><b>Remote</b> ({@link CompositeFilesystem}) — describes the distributed store-backed
     *       workspace and the fact that there is no host filesystem to fall back to.
     *   <li><b>Other</b> — single-line legacy "working directory is X" form for plain
     *       {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} or anything we
     *       don't recognize.
     * </ul>
     */
    private static String buildWorkspaceParagraph(
            Path workspace, AbstractFilesystem fs, boolean artifactDeliveryEnabled) {
        StringBuilder sb = new StringBuilder("## Workspace\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("Project (the user's source tree you're assisting with): ")
                    .append(project.toAbsolutePath())
                    .append("\n");
            sb.append("Workspace (your home base — memory, sessions, skills, runtime data): ")
                    .append(workspace.toAbsolutePath())
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
            sb.append("Shell commands run with `pwd` set to the project directory.\n");
        } else if (fs instanceof AbstractSandboxFilesystem sandbox
                && !(fs instanceof OverlayFilesystem)) {
            sb.append("Sandbox root: /workspace (container id: ")
                    .append(sandbox.id())
                    .append(")\n");
            if (artifactDeliveryEnabled) {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " directly accessible — see the File Isolation Notice below for"
                                + " how to deliver files out of the sandbox.\n");
            } else {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " accessible and there is no mechanism for moving files across"
                                + " the boundary.\n");
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
            sb.append("Your working directory is: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Treat this directory as the single global workspace for file operations"
                            + " unless explicitly instructed otherwise.\n");
        }
        sb.append(
                "AGENTS.md defines persona and local conventions — honor them when consistent"
                        + " with safety and policy.\n");
        if (artifactDeliveryEnabled
                && fs instanceof AbstractSandboxFilesystem
                && !(fs instanceof OverlayFilesystem)) {
            sb.append(
                    "**File Isolation Notice**\n"
                            + "Files inside this container are isolated from the host filesystem"
                            + " and are not directly accessible from outside. If your work"
                            + " produces any final deliverables—such as documents, reports,"
                            + " images, spreadsheets, archives, audio/video files, code"
                            + " artifacts, or similar—you **must** call deliver_artifact"
                            + " automatically when you finish the task to export them to their"
                            + " configured external destination. Deliver it silently: do not ask"
                            + " the user whether they want it delivered — the tool call itself lets"
                            + " the user see and retrieve the artifact directly, so do not mention"
                            + " the delivery or the deliver_artifact tool in your reply.\n"
                            + "\n"
                            + "**Important Notes**:\n"
                            + "- Only deliver the final output of your task. **Do not** deliver"
                            + " temporary files, working copies, internal intermediate files, or"
                            + " any sensitive information (e.g., credentials, keys, personal"
                            + " data).\n"
                            + "- Do not simply print the file path as a reference; the user"
                            + " cannot access your container's filesystem directly.\n");
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

    private String buildSessionContextSection(Path workspace, RuntimeContext rc) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE MMM d, yyyy"));
        String platform = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String tempDir = System.getProperty("java.io.tmpdir");
        String dynamicPart = buildSessionDynamicPart(rc);

        return String.format(
                        SESSION_CONTEXT_SECTION_TEMPLATE,
                        agentName,
                        today,
                        platform,
                        workspace.toAbsolutePath(),
                        tempDir,
                        dynamicPart)
                .strip();
    }

    private String buildSessionDynamicPart(RuntimeContext rc) {
        List<String> parts = new ArrayList<>();
        if (rc != null && rc.getSessionId() != null) {
            parts.add("AgentStateStore ID: " + rc.getSessionId());
        }
        if (environmentMemory != null && !environmentMemory.isBlank()) {
            parts.add(environmentMemory);
        }
        return parts.isEmpty() ? "" : String.join("\n", parts);
    }

    private String buildLoadedContextSection(
            String agentsContent,
            String memoryContent,
            String knowledgeBlock,
            String additionalBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append(workspaceFilesNotice());
        sb.append("\n");
        sb.append("<loaded_context>\n");
        sb.append(buildXmlContext("agents_context", agentsContent));
        if (includeMemoryContext()) {
            sb.append(buildXmlContext("memory_context", memoryContent));
        }
        sb.append(buildXmlContext("domain_knowledge_context", knowledgeBlock));
        if (!additionalBlock.isBlank()) {
            sb.append(additionalBlock);
        }
        sb.append("</loaded_context>\n");
        return sb.toString();
    }

    private String workspaceFilesNotice() {
        return includeMemoryContext()
                ? WORKSPACE_FILES_NOTICE_WITH_MEMORY
                : WORKSPACE_FILES_NOTICE_WITHOUT_MEMORY;
    }

    private static String buildXmlContext(String tagName, String content) {
        if (content == null || content.isBlank()) {
            return "  <" + tagName + "></" + tagName + ">\n";
        }
        return "  <" + tagName + ">\n" + indentByTwo(content.strip()) + "\n  </" + tagName + ">\n";
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
        return text.substring(0, maxChars) + notice;
    }

    private String buildKnowledgeBlock(RuntimeContext rc, String knowledgeContent, Path workspace) {
        List<Path> knowledgeFiles = workspaceManager.listKnowledgeFiles(rc);
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
