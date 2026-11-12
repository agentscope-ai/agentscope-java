/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.util.List;
import java.util.Map;

/** Fileless Harness workspace: finalization reads and mutations replay through Session Store. */
public final class ManagedPrivateFilesystem extends RemoteFilesystem {
    private final BaseStore store;
    private final List<String> namespace;

    public ManagedPrivateFilesystem(BaseStore store, List<String> namespace) {
        super(store, namespace);
        this.store = store;
        this.namespace = List.copyOf(namespace);
    }

    private RemoteFilesystem filesystem(RuntimeContext context) {
        return new RemoteFilesystem(ManagedFinalization.workspace(context, store), namespace);
    }

    @Override
    public LsResult ls(RuntimeContext context, String path) {
        return filesystem(context).ls(context, path);
    }

    @Override
    public ReadResult read(RuntimeContext context, String path, int offset, int limit) {
        return filesystem(context).read(context, path, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext context, String path, String content) {
        return filesystem(context).write(context, path, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext context, String path, String oldText, String newText, boolean all) {
        return filesystem(context).edit(context, path, oldText, newText, all);
    }

    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
        return filesystem(context).grep(context, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String path) {
        return filesystem(context).glob(context, pattern, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
        return filesystem(context).uploadFiles(context, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        return filesystem(context).downloadFiles(context, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext context, String path) {
        return filesystem(context).delete(context, path);
    }

    @Override
    public WriteResult move(RuntimeContext context, String from, String to) {
        return filesystem(context).move(context, from, to);
    }

    @Override
    public boolean exists(RuntimeContext context, String path) {
        return filesystem(context).exists(context, path);
    }
}
