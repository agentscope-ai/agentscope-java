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
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prefix-routed filesystem that preserves shell execution on the primary sandbox backend.
 *
 * <p>File operations are delegated to an internal {@link CompositeFilesystem} (primary as default
 * backend + optional prefix routes). {@link #execute} and {@link #id} always go to {@code
 * primary}, so Managed Agents can mount JPA-backed {@code memory-stores/} routes alongside a
 * {@code SandboxBackedFilesystem} without losing {@code shell_execute}.
 */
public final class RoutedSandboxFilesystem implements AbstractSandboxFilesystem {

    private final AbstractSandboxFilesystem primary;
    private final CompositeFilesystem composite;

    public RoutedSandboxFilesystem(
            AbstractSandboxFilesystem primary, Map<String, AbstractFilesystem> routes) {
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        this.composite = new CompositeFilesystem(primary, routes);
    }

    /** Returns the primary sandbox filesystem (lifecycle middleware injects into this). */
    public AbstractSandboxFilesystem primary() {
        return primary;
    }

    /**
     * Returns the backend that would serve {@code path}: the longest matching prefix route, or
     * the primary sandbox filesystem when no route matches.
     */
    public AbstractFilesystem backendFor(String path) {
        return composite.filesystemFor(path);
    }

    @Override
    public String id() {
        return primary.id();
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        return primary.execute(runtimeContext, command, timeoutSeconds);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return composite.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return composite.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return composite.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        return composite.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return composite.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return composite.glob(runtimeContext, pattern, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return composite.uploadFiles(runtimeContext, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        return composite.downloadFiles(runtimeContext, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return composite.delete(runtimeContext, path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return composite.move(runtimeContext, fromPath, toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return composite.exists(runtimeContext, path);
    }
}
