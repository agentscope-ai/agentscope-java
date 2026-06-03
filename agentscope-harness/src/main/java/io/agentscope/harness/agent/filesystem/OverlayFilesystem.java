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
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
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
 * A layered filesystem that overlays a user-specific "upper" layer on top of a shared "lower"
 * layer, providing copy-on-write semantics.
 *
 * <p>Read operations check the upper layer first and fall back to the lower layer. Write operations
 * always target the upper layer. This enables per-user customization of shared content (e.g.
 * skills, subagents) without modifying the shared originals.
 *
 * <p>Merge semantics:
 *
 * <ul>
 *   <li>{@code ls} — union of both layers; upper entries take precedence on path collision
 *   <li>{@code read} — upper first, then lower
 *   <li>{@code write}/{@code edit} — always to upper (copy-on-write)
 *   <li>{@code delete} — removes from upper only; shared-layer files cannot be deleted
 *   <li>{@code grep}/{@code glob} — searches both layers; upper results override lower on path
 *       collision
 *   <li>{@code exists} — true if present in either layer
 * </ul>
 */
public class OverlayFilesystem implements AbstractFilesystem {

    private final AbstractFilesystem upper;
    private final AbstractFilesystem lower;

    /**
     * Creates an overlay filesystem.
     *
     * @param upper the user-specific layer (read/write); takes precedence on conflicts
     * @param lower the shared layer (read-only from the overlay's perspective)
     */
    public OverlayFilesystem(AbstractFilesystem upper, AbstractFilesystem lower) {
        if (upper == null) {
            throw new IllegalArgumentException("upper layer must not be null");
        }
        if (lower == null) {
            throw new IllegalArgumentException("lower layer must not be null");
        }
        this.upper = upper;
        this.lower = lower;
    }

    /** Returns the upper (writable) layer. */
    public AbstractFilesystem upper() {
        return upper;
    }

    /** Returns the lower (shared) layer. */
    public AbstractFilesystem lower() {
        return lower;
    }

    /**
     * Builds an overlay that automatically exposes shell execution when {@code upper} supports
     * it. Returns a {@link AbstractSandboxFilesystem}-implementing subclass when
     * {@code upper instanceof AbstractSandboxFilesystem}, otherwise a plain
     * {@link OverlayFilesystem}.
     *
     * <p>Use this factory in preference to the constructor when {@code upper} may carry shell
     * capability (e.g. {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell})
     * — it lets callers like
     * {@code ReActAgent.Builder} keep their {@code instanceof AbstractSandboxFilesystem} check
     * working through the overlay.
     *
     * @param upper user-specific layer (read/write); takes precedence on conflicts
     * @param lower shared layer (read-only from the overlay's perspective)
     * @return overlay; either plain or shell-aware depending on {@code upper}
     */
    public static AbstractFilesystem of(AbstractFilesystem upper, AbstractFilesystem lower) {
        if (upper instanceof AbstractSandboxFilesystem shellUpper) {
            return new ShellAwareOverlay(shellUpper, lower);
        }
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * Overlay subtype that delegates shell {@link #execute} and {@link #id} to a shell-capable
     * upper backend. Filesystem operations inherit the standard overlay semantics from
     * {@link OverlayFilesystem}.
     */
    private static final class ShellAwareOverlay extends OverlayFilesystem
            implements AbstractSandboxFilesystem {

        private final AbstractSandboxFilesystem shellBackend;

        ShellAwareOverlay(AbstractSandboxFilesystem upper, AbstractFilesystem lower) {
            super(upper, lower);
            this.shellBackend = upper;
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
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        LsResult upperResult = upper.ls(runtimeContext, path);
        LsResult lowerResult = lower.ls(runtimeContext, path);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, FileInfo> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.entries() != null) {
            for (FileInfo fi : lowerResult.entries()) {
                merged.put(fi.path(), fi);
            }
        }
        if (upperResult.isSuccess() && upperResult.entries() != null) {
            for (FileInfo fi : upperResult.entries()) {
                merged.put(fi.path(), fi);
            }
        }
        return LsResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        if (upper.exists(runtimeContext, filePath)) {
            return upper.read(runtimeContext, filePath, offset, limit);
        }
        return lower.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return upper.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (upper.exists(runtimeContext, filePath)) {
            return upper.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        if (lower.exists(runtimeContext, filePath)) {
            ReadResult src = lower.read(runtimeContext, filePath, 0, Integer.MAX_VALUE);
            if (!src.isSuccess()) {
                return EditResult.fail("Cannot read shared file for copy-on-write: " + filePath);
            }
            upper.write(runtimeContext, filePath, src.fileData().content());
            return upper.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        return EditResult.fail("File not found: " + filePath);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        GrepResult upperResult = upper.grep(runtimeContext, pattern, path, glob);
        GrepResult lowerResult = lower.grep(runtimeContext, pattern, path, glob);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, GrepMatch> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.matches() != null) {
            for (GrepMatch m : lowerResult.matches()) {
                merged.put(m.path() + ":" + m.line(), m);
            }
        }
        if (upperResult.isSuccess() && upperResult.matches() != null) {
            for (GrepMatch m : upperResult.matches()) {
                merged.put(m.path() + ":" + m.line(), m);
            }
        }
        return GrepResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        GlobResult upperResult = upper.glob(runtimeContext, pattern, path);
        GlobResult lowerResult = lower.glob(runtimeContext, pattern, path);

        if (!upperResult.isSuccess() && !lowerResult.isSuccess()) {
            return lowerResult;
        }

        Map<String, FileInfo> merged = new LinkedHashMap<>();
        if (lowerResult.isSuccess() && lowerResult.matches() != null) {
            for (FileInfo fi : lowerResult.matches()) {
                merged.put(fi.path(), fi);
            }
        }
        if (upperResult.isSuccess() && upperResult.matches() != null) {
            for (FileInfo fi : upperResult.matches()) {
                merged.put(fi.path(), fi);
            }
        }
        return GlobResult.success(new ArrayList<>(merged.values()));
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return upper.uploadFiles(runtimeContext, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        for (String path : paths) {
            if (upper.exists(runtimeContext, path)) {
                results.addAll(upper.downloadFiles(runtimeContext, List.of(path)));
            } else {
                results.addAll(lower.downloadFiles(runtimeContext, List.of(path)));
            }
        }
        return results;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        if (upper.exists(runtimeContext, path)) {
            return upper.delete(runtimeContext, path);
        }
        if (lower.exists(runtimeContext, path)) {
            return WriteResult.fail("Cannot delete shared file: " + path);
        }
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        if (upper.exists(runtimeContext, fromPath)) {
            return upper.move(runtimeContext, fromPath, toPath);
        }
        if (lower.exists(runtimeContext, fromPath)) {
            ReadResult src = lower.read(runtimeContext, fromPath, 0, Integer.MAX_VALUE);
            if (!src.isSuccess()) {
                return WriteResult.fail("Cannot read source for move: " + fromPath);
            }
            return upper.write(runtimeContext, toPath, src.fileData().content());
        }
        return WriteResult.fail("Source not found: " + fromPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return upper.exists(runtimeContext, path) || lower.exists(runtimeContext, path);
    }

    /** Returns the user-specific (read/write) layer. */
    public AbstractFilesystem getUpper() {
        return upper;
    }

    /** Returns the shared (read-only-through-this-overlay) layer. */
    public AbstractFilesystem getLower() {
        return lower;
    }
}
