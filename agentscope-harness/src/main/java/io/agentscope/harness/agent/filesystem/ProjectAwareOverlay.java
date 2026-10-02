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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overlay variant that routes writes to the <em>project</em> directory for non-workspace paths,
 * while keeping workspace metadata (memory, sessions, skills, etc.) in the upper (workspace) layer.
 *
 * <p>Produced exclusively by {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec}
 * when {@code projectWritable(true)} is set. Other filesystem specs ({@code RemoteFilesystemSpec},
 * {@code SandboxFilesystemSpec}) are not affected.
 *
 * <p>Read operations retain standard overlay semantics: check upper (workspace) first, fall back
 * to lower (project). Shell {@code execute()} delegates to the upper layer as before.
 */
public class ProjectAwareOverlay extends OverlayFilesystem implements AbstractSandboxFilesystem {

    private static final Set<String> WORKSPACE_PREFIXES =
            Set.of(
                    "MEMORY.md",
                    "memory",
                    "AGENTS.md",
                    "agents",
                    "skills",
                    "knowledge",
                    "rules",
                    "tools.json",
                    "subagents",
                    "plans",
                    ".index",
                    ".skills-cache",
                    "large_tool_results");

    private final AbstractSandboxFilesystem shellBackend;
    private final LocalFilesystem projectFs;
    private final Path workspaceRoot;

    /**
     * @param upper shell-capable workspace filesystem (read-write, workspace root)
     * @param lower read-only project filesystem (overlay fallback)
     * @param projectFs writable project filesystem for non-workspace writes
     * @param workspaceRoot absolute path of the workspace, used to classify absolute paths
     */
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot) {
        super(upper, lower);
        this.shellBackend = upper;
        this.projectFs = projectFs;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return shellBackend.id();
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        return shellBackend.execute(runtimeContext, command, timeoutSeconds);
    }

    // ==================== Write routing ====================

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (isWorkspacePath(filePath)) {
            return upper().write(runtimeContext, filePath, content);
        }
        return projectFs.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (isWorkspacePath(filePath)) {
            return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        if (projectFs.exists(runtimeContext, filePath)) {
            return projectFs.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        // Fallback: file may exist only in upper (written before projectWritable was enabled)
        return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        if (isWorkspacePath(path)) {
            return super.delete(runtimeContext, path);
        }
        if (projectFs.exists(runtimeContext, path)) {
            return projectFs.delete(runtimeContext, path);
        }
        return super.delete(runtimeContext, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> workspaceFiles = new ArrayList<>();
        List<Map.Entry<String, byte[]>> projectFiles = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            if (isWorkspacePath(entry.getKey())) {
                workspaceFiles.add(entry);
            } else {
                projectFiles.add(entry);
            }
        }
        List<FileUploadResponse> results = new ArrayList<>();
        if (!workspaceFiles.isEmpty()) {
            results.addAll(upper().uploadFiles(runtimeContext, workspaceFiles));
        }
        if (!projectFiles.isEmpty()) {
            results.addAll(projectFs.uploadFiles(runtimeContext, projectFiles));
        }
        return results;
    }

    // ==================== Path classification ====================

    boolean isWorkspacePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return true;
        }
        String normalized = filePath.replace('\\', '/').strip();

        if (Path.of(normalized).isAbsolute()) {
            return Path.of(normalized).normalize().startsWith(workspaceRoot);
        }

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        for (String prefix : WORKSPACE_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
