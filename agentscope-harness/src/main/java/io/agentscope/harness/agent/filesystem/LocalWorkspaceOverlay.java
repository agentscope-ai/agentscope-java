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
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local filesystem overlay that exposes agent-level knowledge as a read-only baseline beneath
 * the isolated user/session layer.
 *
 * <p>Knowledge reads use {@code isolated workspace > agent workspace > project}. Other paths keep
 * the standard {@link OverlayFilesystem} ordering ({@code isolated workspace > project}), so a
 * caller cannot traverse another user's namespace through the agent workspace fallback. Writes
 * always target the isolated upper layer and edits of shared knowledge use copy-on-write.
 */
public class LocalWorkspaceOverlay extends OverlayFilesystem implements AbstractSandboxFilesystem {

    private static final String KNOWLEDGE = "knowledge";

    private final AbstractSandboxFilesystem shellBackend;
    private final AbstractFilesystem sharedWorkspace;

    public LocalWorkspaceOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem sharedWorkspace,
            AbstractFilesystem project) {
        super(upper, project);
        this.shellBackend = upper;
        this.sharedWorkspace = sharedWorkspace;
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

    @Override
    public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
        if (!isKnowledgePath(filePath) || upper().exists(rc, filePath)) {
            return super.read(rc, filePath, offset, limit);
        }
        if (sharedWorkspace.exists(rc, filePath)) {
            return sharedWorkspace.read(rc, filePath, offset, limit);
        }
        return lower().read(rc, filePath, offset, limit);
    }

    @Override
    public LsResult ls(RuntimeContext rc, String path) {
        if (!isKnowledgePath(path)) {
            return super.ls(rc, path);
        }
        Map<String, FileInfo> merged = new LinkedHashMap<>();
        mergeFileInfo(merged, lower().ls(rc, path));
        mergeFileInfo(merged, sharedWorkspace.ls(rc, path));
        mergeFileInfo(merged, upper().ls(rc, path));
        return LsResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GlobResult glob(RuntimeContext rc, String pattern, String path) {
        if (!isKnowledgePath(path)) {
            return super.glob(rc, pattern, path);
        }
        Map<String, FileInfo> merged = new LinkedHashMap<>();
        mergeFileInfo(merged, lower().glob(rc, pattern, path));
        mergeFileInfo(merged, sharedWorkspace.glob(rc, pattern, path));
        mergeFileInfo(merged, upper().glob(rc, pattern, path));
        return GlobResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
        if (!isKnowledgePath(path)) {
            return super.grep(rc, pattern, path, glob);
        }
        Map<String, GrepMatch> merged = new LinkedHashMap<>();
        mergeGrep(merged, lower().grep(rc, pattern, path, glob));
        mergeGrep(merged, sharedWorkspace.grep(rc, pattern, path, glob));
        mergeGrep(merged, upper().grep(rc, pattern, path, glob));
        return GrepResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public EditResult edit(
            RuntimeContext rc,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (!isKnowledgePath(filePath) || upper().exists(rc, filePath)) {
            return super.edit(rc, filePath, oldString, newString, replaceAll);
        }
        AbstractFilesystem source =
                sharedWorkspace.exists(rc, filePath) ? sharedWorkspace : lower();
        if (!source.exists(rc, filePath)) {
            return EditResult.fail("File not found: " + filePath);
        }
        ReadResult read = source.read(rc, filePath, 0, Integer.MAX_VALUE);
        if (!read.isSuccess()) {
            return EditResult.fail("Cannot read shared file for copy-on-write: " + filePath);
        }
        WriteResult write = upper().write(rc, filePath, read.fileData().content());
        if (!write.isSuccess()) {
            return EditResult.fail("Cannot copy shared file for editing: " + filePath);
        }
        return upper().edit(rc, filePath, oldString, newString, replaceAll);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        for (String path : paths) {
            if (!isKnowledgePath(path) || upper().exists(rc, path)) {
                results.addAll(super.downloadFiles(rc, List.of(path)));
            } else if (sharedWorkspace.exists(rc, path)) {
                results.addAll(sharedWorkspace.downloadFiles(rc, List.of(path)));
            } else {
                results.addAll(lower().downloadFiles(rc, List.of(path)));
            }
        }
        return results;
    }

    @Override
    public WriteResult delete(RuntimeContext rc, String path) {
        if (!isKnowledgePath(path) || upper().exists(rc, path)) {
            return super.delete(rc, path);
        }
        if (sharedWorkspace.exists(rc, path)) {
            return WriteResult.fail("Cannot delete shared file: " + path);
        }
        return super.delete(rc, path);
    }

    @Override
    public WriteResult move(RuntimeContext rc, String fromPath, String toPath) {
        if (!isKnowledgePath(fromPath) || upper().exists(rc, fromPath)) {
            return super.move(rc, fromPath, toPath);
        }
        if (!sharedWorkspace.exists(rc, fromPath)) {
            return super.move(rc, fromPath, toPath);
        }
        ReadResult read = sharedWorkspace.read(rc, fromPath, 0, Integer.MAX_VALUE);
        if (!read.isSuccess()) {
            return WriteResult.fail("Cannot read source for move: " + fromPath);
        }
        return upper().write(rc, toPath, read.fileData().content());
    }

    @Override
    public boolean exists(RuntimeContext rc, String path) {
        return super.exists(rc, path)
                || (isKnowledgePath(path) && sharedWorkspace.exists(rc, path));
    }

    private static boolean isKnowledgePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.equals(KNOWLEDGE) || normalized.startsWith(KNOWLEDGE + "/");
    }

    private static void mergeFileInfo(Map<String, FileInfo> merged, LsResult result) {
        if (result.isSuccess() && result.entries() != null) {
            for (FileInfo info : result.entries()) {
                merged.put(info.path(), info);
            }
        }
    }

    private static void mergeFileInfo(Map<String, FileInfo> merged, GlobResult result) {
        if (result.isSuccess() && result.matches() != null) {
            for (FileInfo info : result.matches()) {
                merged.put(info.path(), info);
            }
        }
    }

    private static void mergeGrep(Map<String, GrepMatch> merged, GrepResult result) {
        if (result.isSuccess() && result.matches() != null) {
            for (GrepMatch match : result.matches()) {
                merged.put(match.path() + ":" + match.line(), match);
            }
        }
    }
}
