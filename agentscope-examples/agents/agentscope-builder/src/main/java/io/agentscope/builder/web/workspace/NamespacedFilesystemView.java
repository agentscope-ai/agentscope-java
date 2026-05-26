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
package io.agentscope.builder.web.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps any {@link AbstractFilesystem} and transparently prefixes every path argument with the
 * segments produced by a {@link NamespaceFactory}, scoping all operations to a logical subtree.
 *
 * <p>This is the single seam used by {@code agentscope-builder} to multi-tenant its workspace
 * storage. One shared backend (e.g. {@code LocalFilesystem} rooted at
 * {@code .agentscope/workspaces}) is wrapped per agent with a factory that emits
 * {@code [users, ownerId, agents, agentId]}, yielding per-(user, agent) isolation without
 * touching call sites.
 *
 * <p>The wrapper composes over any backend that implements {@link AbstractFilesystem} —
 * {@code LocalFilesystem}, {@code SandboxBackedFilesystem}, {@code RemoteFilesystem} — so the
 * builder can change deployment topology by swapping the underlying singleton.
 *
 * <p>Path-traversal is rejected by {@link AbstractFilesystem#validatePath(String)} before
 * prefixing, so untrusted inputs cannot escape the namespace via {@code ..}.
 */
public final class NamespacedFilesystemView implements AbstractFilesystem {

    private final AbstractFilesystem delegate;
    private final NamespaceFactory namespaceFactory;

    public NamespacedFilesystemView(
            AbstractFilesystem delegate, NamespaceFactory namespaceFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.namespaceFactory = Objects.requireNonNull(namespaceFactory, "namespaceFactory");
    }

    /** Returns the underlying filesystem (unscoped). Useful for cross-namespace operations. */
    public AbstractFilesystem delegate() {
        return delegate;
    }

    /** Returns the namespace factory used to scope paths. */
    public NamespaceFactory namespaceFactory() {
        return namespaceFactory;
    }

    // ==================== Filesystem operations ====================

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return delegate.ls(runtimeContext, scoped(runtimeContext, path));
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return delegate.read(runtimeContext, scoped(runtimeContext, filePath), offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return delegate.write(runtimeContext, scoped(runtimeContext, filePath), content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        return delegate.edit(
                runtimeContext, scoped(runtimeContext, filePath), oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        // path is optional — null means "search current working directory"; preserve that signal.
        String scopedPath =
                path == null ? scopedRoot(runtimeContext) : scoped(runtimeContext, path);
        return delegate.grep(runtimeContext, pattern, scopedPath, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String scopedPath =
                (path == null || path.isBlank())
                        ? scopedRoot(runtimeContext)
                        : scoped(runtimeContext, path);
        return delegate.glob(runtimeContext, pattern, scopedPath);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        if (files == null || files.isEmpty()) {
            return delegate.uploadFiles(runtimeContext, files);
        }
        List<Map.Entry<String, byte[]>> rewritten = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> e : files) {
            rewritten.add(
                    new AbstractMap.SimpleEntry<>(
                            scoped(runtimeContext, e.getKey()), e.getValue()));
        }
        return delegate.uploadFiles(runtimeContext, rewritten);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return delegate.downloadFiles(runtimeContext, paths);
        }
        List<String> rewritten = new ArrayList<>(paths.size());
        for (String p : paths) {
            rewritten.add(scoped(runtimeContext, p));
        }
        return delegate.downloadFiles(runtimeContext, rewritten);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return delegate.delete(runtimeContext, scoped(runtimeContext, path));
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return delegate.move(
                runtimeContext, scoped(runtimeContext, fromPath), scoped(runtimeContext, toPath));
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return delegate.exists(runtimeContext, scoped(runtimeContext, path));
    }

    // ==================== Path scoping ====================

    /**
     * Prefixes {@code path} with the current namespace and returns an absolute (leading-slash)
     * path suitable for the delegate. Validates against {@code ..} traversal first.
     */
    private String scoped(RuntimeContext runtimeContext, String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        AbstractFilesystem.validatePath(path);
        String prefix = currentPrefix(runtimeContext);
        String key = path.startsWith("/") ? path.substring(1) : path;
        if (prefix.isEmpty()) {
            return "/" + key;
        }
        if (key.isEmpty()) {
            return "/" + prefix;
        }
        return "/" + prefix + "/" + key;
    }

    /** Returns the namespace root as an absolute path (used when caller passes {@code null}). */
    private String scopedRoot(RuntimeContext runtimeContext) {
        String prefix = currentPrefix(runtimeContext);
        return prefix.isEmpty() ? "/" : "/" + prefix;
    }

    private String currentPrefix(RuntimeContext runtimeContext) {
        List<String> ns = namespaceFactory.getNamespace(runtimeContext);
        if (ns == null || ns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ns.size(); i++) {
            String seg = ns.get(i);
            if (seg == null || seg.isBlank()) {
                throw new IllegalStateException(
                        "NamespaceFactory returned a null or blank segment at index " + i);
            }
            if (seg.contains("/") || seg.contains("\\") || seg.contains("..")) {
                throw new IllegalStateException(
                        "NamespaceFactory segment must not contain path separators or '..': "
                                + seg);
            }
            if (i > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }
}
