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
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
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
 * system prompt via {@link #onSystemPrompt(Agent, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
 */
public class WorkspaceContextMiddleware implements MiddlewareBase {

    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## Session Context
            This is the %s. We are setting up the context for our chat.
            Today's date is %s.
            My operating system is: %s
            The workspace directory is: %s
            The project's temporary directory is: %s
            %s
            """;

    private static final String WORKSPACE_GUIDANCE_TEMPLATE =
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
            Use edit_file/write_file to append concise bullet points. \
            Do NOT duplicate existing entries. \
            Memory is also automatically extracted at conversation end.

            ## Workspace
            Your working directory is: %s
            Treat this directory as the single global workspace for file operations unless explicitly instructed otherwise.
            AGENTS.md define persona and local conventions — honor them when consistent with safety and policy.

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
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        RuntimeContext rc =
                agent instanceof AgentBase ab && ab.getRuntimeContext() != null
                        ? ab.getRuntimeContext()
                        : RuntimeContext.empty();
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

        String guidance = String.format(WORKSPACE_GUIDANCE_TEMPLATE, workspace.toAbsolutePath());
        String loadedContext =
                buildLoadedContextSection(
                        agentsContent, memoryContent, knowledgeBlock, additionalBlock, rc);
        return assembleSection(sessionContext, guidance, loadedContext);
    }

    private static String assembleSection(
            String sessionContext, String guidance, String loadedContextSection) {
        StringBuilder sb = new StringBuilder();
        if (!sessionContext.isBlank()) {
            sb.append(sessionContext).append("\n\n");
        }
        sb.append(guidance).append("\n").append(loadedContextSection);
        return sb.toString();
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
            parts.add("Session ID: " + rc.getSessionId());
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
