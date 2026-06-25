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

/**
 * Appends workspace context (session info, AGENTS.md, MEMORY.md, knowledge) to the
 * system prompt via {@link #onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
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

    private static final String GUIDANCE_TEMPLATE =
            """
            ## Domain Knowledge
            The workspace `knowledge/` tree holds many detailed reference documents (not only a single summary file). When the task needs specs, procedures, schemas, or domain facts, treat that directory as the source of truth.
            Below, `<domain_knowledge_context>` already includes what you need to navigate it: injected `knowledge/KNOWLEDGE.md` (if present) plus a **full list of knowledge file paths** under `knowledge/` — use that as the catalog of what exists and where.
            For content not inlined here, open only the paths you need with read_file, grep, or glob (prefer targeted reads over loading entire trees into the reply).

            ## Memory Recall
            Before answering questions about prior work, decisions, dates, people, or preferences: \
            run memory_search on MEMORY.md + memory/*.md, then memory_get for needed lines. \
            Include Source: <path#line> citations when helpful.

            ## Memory Persistence
            You have a persistent MEMORY.md. Update it proactively when:
            - User shares preferences, project context, or decisions
            - Important outcomes or action items are established
            Use the **memory_save** tool to persist memories — it atomically updates \
            both MEMORY.md and the daily ledger. Do NOT use write_file or edit_file on \
            MEMORY.md or any path under memory/ — always use memory_save instead. \
            Memory is also automatically extracted at conversation end.
            """;

    private static final String WORKSPACE_FILES_NOTICE =
            """
            ## Workspace Files (Injected)
            The following <loaded_context> was loaded in from files in your workspace.
            These files (for example, `AGENTS.md`, `MEMORY.md`, and `knowledge/KNOWLEDGE.md`) contain memory, facts, preferences, guidelines, and user-specific details learned from prior interactions with user.
            """;

    private static final String TRUNCATION_NOTICE =
            "\n\n... (memory truncated — use memory_search for older entries) ...\n";

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final WorkspaceManager workspaceManager;
    private final String agentName;
    private final String environmentMemory;
    private final int maxContextTokens;
    private List<String> additionalContextFiles = List.of();

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, "HarnessAgent", null, DEFAULT_MAX_CONTEXT_TOKENS);
    }

    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager, int maxContextTokens) {
        this(workspaceManager, "HarnessAgent", null, maxContextTokens);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens) {
        this.workspaceManager = workspaceManager;
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "HarnessAgent";
        this.environmentMemory = environmentMemory;
        this.maxContextTokens = maxContextTokens;
    }

    public void setAdditionalContextFiles(List<String> files) {
        this.additionalContextFiles = files != null ? files : List.of();
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        String section = buildWorkspaceSection(rc);
        if (section.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(base + separator + section);
    }

    private String buildWorkspaceSection(RuntimeContext rc) {
        String agentsContent = workspaceManager.readAgentsMd(rc).strip();
        String memoryContent = workspaceManager.readMemoryMd(rc).strip();
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
        int memoryTokens = estimateTokens(memoryContent);
        int available = maxContextTokens - fixedTokens;
        if (available > 0 && memoryTokens > available) {
            memoryContent = truncateToTokenBudget(memoryContent, available);
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(workspace, workspaceManager.getFilesystem());
        String loadedContext =
                buildLoadedContextSection(
                        agentsContent, memoryContent, knowledgeBlock, additionalBlock, rc);
        return assembleSection(
                sessionContext, GUIDANCE_TEMPLATE, workspaceParagraph, loadedContext);
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
        sb.append("\n").append(WORKSPACE_FILES_NOTICE).append("\n").append(loadedContextSection);
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
    private static String buildWorkspaceParagraph(Path workspace, AbstractFilesystem fs) {
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
            sb.append(
                    "Files are isolated inside this container. The host filesystem is not"
                            + " directly accessible — use upload/download tools when you need to"
                            + " move bytes across the boundary.\n");
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
            String additionalBlock,
            RuntimeContext rc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<loaded_context>\n");
        sb.append(buildXmlContext("agents_context", agentsContent));
        sb.append(buildXmlContext("memory_context", memoryContent));
        sb.append(buildXmlContext("domain_knowledge_context", knowledgeBlock));
        if (!additionalBlock.isBlank()) {
            sb.append(additionalBlock);
        }
        sb.append("</loaded_context>\n");
        return sb.toString();
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

    private static String truncateToTokenBudget(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + TRUNCATION_NOTICE;
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
