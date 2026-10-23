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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent-facing tool for nominating workspace files for promotion to the shared workspace. The
 * tool records a {@code PENDING} {@link ContributionEntity}; an admin must approve before the
 * payload is materialised under {@code shared/agents/<targetAgentId>/<type>/<path>}.
 *
 * <p>Unlike the original version, this tool does <em>not</em> require the LLM to inline the file
 * content — instead, the LLM lists workspace-relative source paths in {@code source_paths} and the
 * tool reads the bytes from the caller's sandbox via {@link WorkspaceManagerFactory}. This avoids
 * payload truncation in the LLM's tool-call serialization, keeps large bundles cheap to nominate,
 * and ensures the admin sees exactly what the contributor has on disk.
 *
 * <p>For multi-file skill bundles ({@code target_type=skill}), pass several comma-separated
 * {@code source_paths} — each lands as a file inside the bundle under its file name (basename).
 * Sub-directory layouts inside a skill bundle are best driven from the web UI's file-tree picker.
 *
 * <p>Registered as a singleton onto the built-in {@code data-agent} main agent at startup by
 * {@link ContributionToolRegistrar}.
 */
public final class ContributeWorkspaceTool {

    private final MarketContributionService service;
    private final WorkspaceManagerFactory workspaceFactory;

    public ContributeWorkspaceTool(
            MarketContributionService service, WorkspaceManagerFactory workspaceFactory) {
        this.service = Objects.requireNonNull(service, "service");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
    }

    @Tool(
            name = "contribute_to_workspace",
            description =
                    """
                    Nominate one or more workspace files (skill, sub-agent, memory snippet, \
                    AGENTS.md, or knowledge document) for promotion to the agent's shared \
                    workspace, so other users of the same agent can benefit from them. The tool \
                    reads the file content from the caller's sandbox; do NOT inline content into \
                    arguments. Submits a PENDING contribution that an admin must approve before \
                    it is materialised. Ask the user before calling this tool. Returns \
                    "ok: contribution #N submitted, awaiting admin approval" on success, or an \
                    error string starting with "error:".\
                    """)
    public String contribute(
            @ToolParam(
                            name = "source_user_id",
                            description =
                                    "Identity of the user whose workspace this artifact came from."
                                            + " Take this from the active session context.")
                    String sourceUserId,
            @ToolParam(
                            name = "source_agent_id",
                            description =
                                    "Agent id the source files live under. The tool reads from"
                                            + " this user's sandbox of this agent.")
                    String sourceAgentId,
            @ToolParam(
                            name = "target_type",
                            description =
                                    "One of: skill | subagent | memory | agents_md | knowledge")
                    String targetType,
            @ToolParam(
                            name = "target_path",
                            description =
                                    "Where the artifact should land under"
                                        + " shared/agents/<targetAgentId>/<targetType>/. For a"
                                        + " skill, the bundle directory name. For subagent / memory"
                                        + " / knowledge, the file path. For agents_md, the literal"
                                        + " 'AGENTS.md'.")
                    String targetPath,
            @ToolParam(
                            name = "source_paths",
                            description =
                                    "Comma-separated list of workspace-relative source paths to"
                                            + " harvest from the caller's sandbox. For single-file"
                                            + " target types pass exactly one path; for skill"
                                            + " bundles pass one path per file.")
                    String sourcePaths,
            @ToolParam(
                            name = "target_agent_id",
                            description = "Agent id to promote into; defaults to source_agent_id.",
                            required = false)
                    String targetAgentId,
            @ToolParam(
                            name = "rationale",
                            description =
                                    "One- or two-sentence explanation for the reviewing admin.",
                            required = false)
                    String rationale) {
        try {
            List<FileEntry> payload =
                    harvestFiles(sourceUserId, sourceAgentId, targetType, sourcePaths);
            ContributionEntity saved =
                    service.submit(
                            sourceUserId,
                            sourceAgentId,
                            targetAgentId,
                            targetType,
                            targetPath,
                            rationale,
                            payload);
            return "ok: contribution #" + saved.getId() + " submitted, awaiting admin approval";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Reads each comma-separated source path from the caller's sandbox and builds the FileEntry
     * list. For multi-file skill bundles the FileEntry's relPath is set to the file's basename;
     * for single-file target types the relPath is empty (the service routes it to the appropriate
     * default).
     */
    private List<FileEntry> harvestFiles(
            String sourceUserId, String sourceAgentId, String targetType, String sourcePaths) {
        if (sourcePaths == null || sourcePaths.isBlank()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        String[] rawPaths = sourcePaths.split(",");
        List<String> cleaned = new ArrayList<>(rawPaths.length);
        for (String raw : rawPaths) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cleaned.add(trimmed);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        boolean isSkillBundle = ContributionEntity.TARGET_SKILL.equals(targetType);
        if (!isSkillBundle && cleaned.size() != 1) {
            throw new IllegalArgumentException(
                    "target_type "
                            + targetType
                            + " accepts exactly one source path (got "
                            + cleaned.size()
                            + ")");
        }

        WorkspaceManager wm = workspaceFactory.forAgent(sourceUserId, sourceAgentId);
        RuntimeContext rc = RuntimeContext.builder().userId(sourceUserId).build();
        List<FileEntry> out = new ArrayList<>(cleaned.size());
        for (String path : cleaned) {
            String content = wm.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("source file is empty or unreadable: " + path);
            }
            String relPath = isSkillBundle ? Paths.get(path).getFileName().toString() : "";
            out.add(new FileEntry(relPath, content));
        }
        return out;
    }
}
