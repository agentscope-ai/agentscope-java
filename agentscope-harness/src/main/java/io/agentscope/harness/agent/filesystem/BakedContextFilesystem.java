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
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Filesystem wrapper that substitutes a fixed {@link RuntimeContext} on every delegated call,
 * ignoring whatever RC the caller supplies.
 *
 * <p>Used by {@code HarnessAgent.workspaceFor(userId, sessionId)} to construct an out-of-band
 * {@link io.agentscope.harness.agent.workspace.WorkspaceManager} view bound to an explicit user
 * identity. Controllers acting on another user's namespace can pass {@link RuntimeContext#empty()}
 * downstream; this wrapper ensures the namespace factory underneath still receives the baked-in
 * identity rather than the (empty) caller context.
 */
public final class BakedContextFilesystem implements AbstractFilesystem {

    private final AbstractFilesystem delegate;
    private final RuntimeContext bakedRc;

    public BakedContextFilesystem(AbstractFilesystem delegate, RuntimeContext bakedRc) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bakedRc = bakedRc != null ? bakedRc : RuntimeContext.empty();
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return delegate.ls(bakedRc, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return delegate.read(bakedRc, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return delegate.write(bakedRc, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        return delegate.edit(bakedRc, filePath, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return delegate.grep(bakedRc, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return delegate.glob(bakedRc, pattern, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return delegate.uploadFiles(bakedRc, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        return delegate.downloadFiles(bakedRc, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return delegate.delete(bakedRc, path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return delegate.move(bakedRc, fromPath, toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return delegate.exists(bakedRc, path);
    }
}
