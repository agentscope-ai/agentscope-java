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
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
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
 * <p>Every routed path is first normalized to its agent-visible relative form: a workspace-rooted
 * absolute path (e.g. {@code <workspace>/src/Foo.java}) has the workspace root and, when namespace
 * scoping is configured, the caller's namespace prefix stripped, so the absolute and the relative
 * spelling of the same path behave identically in every operation. The workspace root and the
 * caller's namespace root both map to that root, not to the project view. An absolute path outside
 * the workspace root normally passes through unchanged and is never classified as workspace
 * metadata, unless it reaches the workspace through a link: the project view answers that one,
 * because it hides the subtree, so a sibling spelling reads nothing and no namespace error escapes.
 *
 * <p>Read operations check the workspace first, then the project <em>write</em> layer, then the
 * shared project, so a non-workspace write is visible at the key that produced it while shared
 * project content stays readable. Shell {@code execute()} delegates to the upper layer as before.
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

    /** The writable project layer (where this overlay writes) over the shared project. */
    private final AbstractFilesystem projectView;

    /** Full read view: workspace over project writes over the shared project. */
    private final AbstractFilesystem view;

    private final Path workspaceRoot;
    private final Path workspaceReal;
    private final NamespaceFactory namespaceFactory;

    /**
     * @param upper shell-capable workspace filesystem (read-write, workspace root)
     * @param lower read-only project filesystem (overlay fallback)
     * @param projectFs writable project filesystem for non-workspace writes
     * @param workspaceRoot absolute path of the workspace, used to classify absolute paths
     * @param namespaceFactory namespace factory scoping the workspace and project filesystems;
     *     used to strip the caller's namespace prefix from workspace-rooted absolute paths;
     *     {@code null} when no namespace scoping is configured
     */
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot,
            NamespaceFactory namespaceFactory) {
        super(upper, lower);
        this.shellBackend = upper;
        this.projectFs = projectFs;
        this.projectView = new OverlayFilesystem(projectFs, lower);
        this.view = new OverlayFilesystem(upper, projectView);
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspaceReal = FilesystemUtils.physicalPath(this.workspaceRoot);
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * Namespace-unaware variant: workspace-rooted absolute paths keep their namespace segment and
     * may misroute under namespace scoping.
     *
     * @deprecated use {@link #ProjectAwareOverlay(AbstractSandboxFilesystem, AbstractFilesystem,
     *     LocalFilesystem, Path, NamespaceFactory)} so workspace-rooted absolute paths resolve
     *     identically to their relative spelling under namespace scoping
     * @param upper shell-capable workspace filesystem (read-write, workspace root)
     * @param lower read-only project filesystem (overlay fallback)
     * @param projectFs writable project filesystem for non-workspace writes
     * @param workspaceRoot absolute path of the workspace, used to classify absolute paths
     */
    @Deprecated
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot) {
        this(upper, lower, projectFs, workspaceRoot, null);
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
        String target = toWorkspaceRelativePath(runtimeContext, filePath);
        if (isWorkspacePath(runtimeContext, filePath)) {
            return upper().write(runtimeContext, target, content);
        }
        WriteResult shadowed = shadowedByWorkspaceCopy(runtimeContext, target);
        if (shadowed != null) {
            return shadowed;
        }
        return projectFs.write(runtimeContext, target, content);
    }

    /**
     * The refusal for a project write under a key the workspace already holds: reads ask the
     * workspace layer first, so the write would succeed and leave the caller reading the
     * workspace file.
     */
    private WriteResult shadowedByWorkspaceCopy(RuntimeContext runtimeContext, String key) {
        // Absolute keys name project content; the workspace layer would answer for them only
        // because the shared path policy lets it see project paths, and refusing on that answer
        // would block every project edit and move spelled with an absolute path.
        if (Path.of(normalizeSeparators(key)).isAbsolute()
                || !upper().exists(runtimeContext, key)) {
            return null;
        }
        String details =
                upper().stat(runtimeContext, key)
                        .map(info -> " (" + info.modifiedAt() + ", " + info.size() + " bytes)")
                        .orElse("");
        return WriteResult.fail(
                "A workspace copy of '"
                        + key
                        + "'"
                        + details
                        + " is read before the project layer, so this change would not be visible."
                        + " Delete the workspace copy first.");
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        String target = toWorkspaceRelativePath(runtimeContext, filePath);
        if (isWorkspacePath(runtimeContext, filePath)) {
            return super.edit(runtimeContext, target, oldString, newString, replaceAll);
        }
        String linked = linkedWorkspaceTarget(runtimeContext, filePath);
        if (linked != null) {
            return projectFs.edit(runtimeContext, linked, oldString, newString, replaceAll);
        }
        if (projectFs.exists(runtimeContext, target)) {
            WriteResult shadowed = shadowedByWorkspaceCopy(runtimeContext, target);
            if (shadowed != null) {
                return EditResult.fail(shadowed.error());
            }
            return projectFs.edit(runtimeContext, target, oldString, newString, replaceAll);
        }
        // Fallback: file may exist only in upper (written before projectWritable was enabled)
        return super.edit(runtimeContext, target, oldString, newString, replaceAll);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        String target = toWorkspaceRelativePath(runtimeContext, path);
        if (isWorkspacePath(runtimeContext, path)) {
            return super.delete(runtimeContext, target);
        }
        String linked = linkedWorkspaceTarget(runtimeContext, path);
        if (linked != null) {
            return projectFs.delete(runtimeContext, linked);
        }
        // A workspace copy is what reads return, so delete it first: removing the project copy
        // underneath would leave the caller still reading the old file, and the refusal's
        // "delete the workspace copy first" would never converge.
        if (Path.of(normalizeSeparators(target)).isAbsolute()) {
            return projectFs.exists(runtimeContext, target)
                    ? projectFs.delete(runtimeContext, target)
                    : super.delete(runtimeContext, target);
        }
        if (upper().exists(runtimeContext, target) || !projectFs.exists(runtimeContext, target)) {
            return super.delete(runtimeContext, target);
        }
        return projectFs.delete(runtimeContext, target);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> workspaceFiles = new ArrayList<>();
        List<Map.Entry<String, byte[]>> projectFiles = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            if (isWorkspacePath(runtimeContext, entry.getKey())) {
                workspaceFiles.add(entry);
            } else {
                projectFiles.add(
                        Map.entry(
                                toWorkspaceRelativePath(runtimeContext, entry.getKey()),
                                entry.getValue()));
            }
        }
        List<FileUploadResponse> results = new ArrayList<>();
        if (!workspaceFiles.isEmpty()) {
            results.addAll(upper().uploadFiles(runtimeContext, workspaceFiles));
        }
        if (!projectFiles.isEmpty()) {
            List<Map.Entry<String, byte[]>> visible = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : projectFiles) {
                WriteResult shadowed = shadowedByWorkspaceCopy(runtimeContext, entry.getKey());
                if (shadowed == null) {
                    visible.add(entry);
                } else {
                    results.add(FileUploadResponse.fail(entry.getKey(), shadowed.error()));
                }
            }
            results.addAll(projectFs.uploadFiles(runtimeContext, visible));
        }
        return results;
    }

    // ==================== Read / search / move routing ====================

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String linked = linkedWorkspaceTarget(runtimeContext, filePath);
        if (linked != null) {
            return projectFs.read(runtimeContext, linked, offset, limit);
        }
        String target = toWorkspaceRelativePath(runtimeContext, filePath);
        return viewFor(runtimeContext, target).read(runtimeContext, target, offset, limit);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        String linked = linkedWorkspaceTarget(runtimeContext, path);
        if (linked != null) {
            return projectFs.exists(runtimeContext, linked);
        }
        String target = toWorkspaceRelativePath(runtimeContext, path);
        return viewFor(runtimeContext, target).exists(runtimeContext, target);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            String target = toWorkspaceRelativePath(runtimeContext, path);
            // Each layer answers for itself, so one path a layer refuses does not fail the batch.
            results.addAll(
                    viewFor(runtimeContext, target).downloadFiles(runtimeContext, List.of(target)));
        }
        return results;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String linked = linkedWorkspaceTarget(runtimeContext, path);
        if (linked != null) {
            return projectFs.ls(runtimeContext, linked);
        }
        String target = toWorkspaceRelativePath(runtimeContext, path);
        return viewFor(runtimeContext, target).ls(runtimeContext, target);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String linked = linkedWorkspaceTarget(runtimeContext, path);
        if (linked != null) {
            return projectFs.grep(runtimeContext, pattern, linked, glob);
        }
        String target = toWorkspaceRelativePath(runtimeContext, path);
        return viewFor(runtimeContext, target).grep(runtimeContext, pattern, target, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String linked = linkedWorkspaceTarget(runtimeContext, path);
        if (linked != null) {
            return projectFs.glob(runtimeContext, pattern, linked);
        }
        String target = toWorkspaceRelativePath(runtimeContext, path);
        return viewFor(runtimeContext, target).glob(runtimeContext, pattern, target);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        String from = toWorkspaceRelativePath(runtimeContext, fromPath);
        String to = toWorkspaceRelativePath(runtimeContext, toPath);
        // Move folds an absolute workspace spelling into the workspace view even when the path is
        // not workspace metadata, because the overlay's copy-on-write rules decide what it becomes.
        if (underWorkspace(runtimeContext, fromPath) || underWorkspace(runtimeContext, toPath)) {
            return super.move(runtimeContext, from, to);
        }
        // The project layer is the one that knows the workspace subtree is hidden.
        WriteResult shadowed = shadowedByWorkspaceCopy(runtimeContext, from);
        if (shadowed == null) {
            shadowed = shadowedByWorkspaceCopy(runtimeContext, to);
        }
        if (shadowed != null) {
            return shadowed;
        }
        return projectFs.move(runtimeContext, from, to);
    }

    /**
     * The layers a read-style operation should ask. An absolute path outside the workspace root
     * names project content, so the workspace layer is not asked for it: it would answer for a
     * file above its own root under a {@code ../} spelling, duplicating the project layer under a
     * second key. A project spelling that lands in the caller's own workspace through a link is
     * still workspace content.
     */
    private AbstractFilesystem viewFor(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return view;
        }
        Path absolute = Path.of(normalizeSeparators(filePath));
        if (!absolute.isAbsolute()) {
            return view;
        }
        Path normalized = absolute.normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return view;
        }
        if (namespaceFactory == null) {
            return projectView;
        }
        Path position = FilesystemUtils.physicalPath(normalized);
        return position != null && isInsideOwnSubtree(runtimeContext, position)
                ? view
                : projectView;
    }

    private boolean underWorkspace(RuntimeContext runtimeContext, String filePath) {
        if (isWorkspacePath(runtimeContext, filePath)) {
            return true;
        }
        Path path = Path.of(normalizeSeparators(filePath));
        return path.isAbsolute() && path.normalize().startsWith(workspaceRoot);
    }

    // ==================== Path classification ====================

    /**
     * True when the path names workspace content: a relative key whose spelling is workspace
     * metadata, or an absolute path that either denotes the caller's workspace root or spells
     * workspace metadata inside it.
     */
    boolean isWorkspacePath(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return true;
        }
        String normalized = normalizeSeparators(filePath);
        Path absolute = Path.of(normalized);
        if (absolute.isAbsolute()) {
            String key = workspaceKey(runtimeContext, absolute.normalize());
            return key != null && (key.isEmpty() || matchesWorkspacePrefix(key));
        }
        return matchesWorkspacePrefix(relativeKey(normalized));
    }

    private boolean matchesWorkspacePrefix(String rel) {
        for (String prefix : WORKSPACE_PREFIXES) {
            if (rel.equals(prefix) || rel.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The agent-visible key of an absolute path: the empty key for the workspace root and the
     * caller's namespace root, the workspace-relative form otherwise (with the caller's namespace
     * prefix stripped when it applies), and {@code null} outside the workspace root.
     */
    private String workspaceKey(RuntimeContext runtimeContext, Path absolute) {
        if (!absolute.startsWith(workspaceRoot)) {
            return null;
        }
        Path relative = workspaceRoot.relativize(absolute);
        List<String> namespace =
                namespaceFactory == null ? null : namespaceFactory.getNamespace(runtimeContext);
        if (namespace == null || namespace.isEmpty()) {
            return separators(relative);
        }
        Path namespaceRoot = Path.of(String.join("/", namespace));
        if (relative.toString().isEmpty() || relative.equals(namespaceRoot)) {
            return "";
        }
        return relative.startsWith(namespaceRoot)
                ? separators(namespaceRoot.relativize(relative))
                : separators(relative);
    }

    /**
     * The absolute spelling of a path that reaches the workspace through a link, or {@code null}.
     * The workspace layer refuses such a path by physical position; the project view hides the
     * subtree, so it answers instead of letting a namespace error escape a read.
     */
    private String linkedWorkspaceTarget(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path absolute = Path.of(normalizeSeparators(filePath));
        if (!absolute.isAbsolute()) {
            return null;
        }
        Path normalized = absolute.normalize();
        if (namespaceFactory == null || normalized.startsWith(workspaceRoot)) {
            // Without namespaces nothing is isolated here, and an in-workspace spelling is keyed.
            return null;
        }
        Path position = FilesystemUtils.physicalPath(normalized);
        if (position == null || isInsideOwnSubtree(runtimeContext, position)) {
            // The caller's own subtree stays theirs, whichever spelling or link reaches it.
            return null;
        }
        boolean inWorkspace =
                position.startsWith(workspaceRoot)
                        || (workspaceReal != null && position.startsWith(workspaceReal));
        return inWorkspace ? normalized.toString() : null;
    }

    /** True when the position lands inside the caller's own namespace of the workspace. */
    private boolean isInsideOwnSubtree(RuntimeContext runtimeContext, Path position) {
        Path base = workspaceReal != null ? workspaceReal : workspaceRoot;
        if (!position.startsWith(base)) {
            return false;
        }
        List<String> namespace =
                namespaceFactory == null ? null : namespaceFactory.getNamespace(runtimeContext);
        if (namespace == null || namespace.isEmpty()) {
            return true;
        }
        return position.startsWith(base.resolve(String.join("/", namespace)));
    }

    private static String separators(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Rewrites an absolute path in the caller's workspace to its agent-visible key (see
     * {@link #workspaceKey}); every other path is returned unchanged. All routed operations pass
     * their inputs through this method so that both spellings of the same path resolve identically
     * in the workspace, project, and overlay layers.
     */
    private String toWorkspaceRelativePath(RuntimeContext runtimeContext, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        Path absolute = Path.of(normalizeSeparators(filePath));
        if (!absolute.isAbsolute()) {
            return relativeKey(filePath);
        }
        String key = workspaceKey(runtimeContext, absolute.normalize());
        return key != null ? key : filePath;
    }

    private static String normalizeSeparators(String filePath) {
        return filePath.replace('\\', '/').strip();
    }

    /**
     * The key a relative spelling names: separators unified and a leading {@code ./} dropped, so
     * {@code ./MEMORY.md} classifies and routes exactly like {@code MEMORY.md}. A dot segment used
     * to survive into the key while the classifier stripped it, which sent workspace metadata into
     * the project layer.
     */
    private static String relativeKey(String filePath) {
        String key = normalizeSeparators(filePath);
        while (key.startsWith("./")) {
            key = key.substring(2);
        }
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key;
    }
}
