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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSandboxFilesystem} that delegates execution to a live {@link Sandbox}.
 *
 * <p>Stable proxy created once per agent bean. The live {@link Sandbox} for a call is bound
 * <em>per-call</em> on the invocation's {@link RuntimeContext} by {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} and resolved here via {@link
 * #requireSandbox(RuntimeContext)} — this per-call binding takes precedence and is what keeps
 * concurrent distinct-session calls on the same agent bean isolated (issue #2490). The legacy
 * {@code volatile sandbox} field is retained only as a best-effort fallback for context-free
 * internal callers that resolve the filesystem with a shared empty {@link RuntimeContext} (e.g.
 * {@link io.agentscope.harness.agent.bus.WorkspaceMessageBus}, which carries no per-call binding).
 * The middleware still maintains that field via {@link #setSandbox} on acquire and {@link
 * #clearSandboxIfCurrent} on release, so it remains last-writer-wins under concurrency and must not
 * be relied on for isolation.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

    private final String fsId;
    private volatile Sandbox sandbox;

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public synchronized void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    /**
     * Clears the fallback {@code sandbox} field only if it still points at {@code expected}. Used by
     * {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} on release so a
     * finishing call never nulls a concurrent sibling call's fallback binding (issue #2490).
     *
     * @param expected the sandbox this call bound at acquire time
     */
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        if (this.sandbox == expected) {
            this.sandbox = null;
        }
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox(runtimeContext);
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileUploadResponse> results = new ArrayList<>(files.size());

        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();
            if (content == null) {
                results.add(FileUploadResponse.fail(path, "File content must not be null"));
                continue;
            }

            if (active instanceof SandboxFileTransfer transfer) {
                String transferPath = null;
                try {
                    transferPath = resolveTransferPath(active, path);
                } catch (IllegalArgumentException e) {
                    // Same contract as the archive fallback: an invalid path fails this file.
                    log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                    results.add(FileUploadResponse.fail(path, e.getMessage()));
                    continue;
                } catch (IOException e) {
                    log.debug(
                            "[sandbox-fs] Workspace root unavailable, keeping archive fallback: {}",
                            path);
                }
                if (transferPath != null && transfer.supportsFileTransfer(transferPath)) {
                    try {
                        transfer.uploadFile(transferPath, content);
                        results.add(FileUploadResponse.success(path));
                    } catch (Exception e) {
                        log.warn("[sandbox-fs] native upload failed for path: {}", path, e);
                        results.add(FileUploadResponse.fail(path, e.getMessage()));
                    }
                    continue;
                }
            }

            try {
                byte[] archive = buildSingleFileArchive(active, path, content);
                try (InputStream archiveStream = new ByteArrayInputStream(archive)) {
                    active.hydrateWorkspace(archiveStream);
                }
                results.add(FileUploadResponse.success(path));
            } catch (Exception e) {
                log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());

        for (String path : paths) {
            // Reads keep the raw-path probe, unlike uploads: no shared temp state to race on,
            // and the exec fallback below stays a single round trip.
            if (active instanceof SandboxFileTransfer transfer
                    && transfer.supportsFileTransfer(path)) {
                try {
                    results.add(FileDownloadResponse.success(path, transfer.downloadFile(path)));
                } catch (Exception e) {
                    log.warn("[sandbox-fs] native download failed for path: {}", path, e);
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
                continue;
            }

            try {
                String escapedPath = shellSingleQuote(path);
                String cmd = "base64 " + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    // MIME decoder tolerates wrapped base64 output from GNU `base64`.
                    byte[] decoded =
                            Base64.getMimeDecoder()
                                    .decode(result.stdout() != null ? result.stdout() : "");
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    /**
     * Resolves the {@link Sandbox} bound to the current call, preferring the per-call binding
     * carried on {@code runtimeContext} (concurrency-safe under parallel distinct-session calls,
     * issue #2490) and falling back to the legacy {@code sandbox} field for direct
     * {@link #setSandbox} callers that do not thread a per-call context.
     */
    private Sandbox requireSandbox(RuntimeContext runtimeContext) {
        Sandbox s = null;
        if (runtimeContext != null) {
            SandboxAcquireResult bound = runtimeContext.get(SandboxAcquireResult.class);
            if (bound != null) {
                s = bound.getSandbox();
            }
        }
        if (s == null) {
            s = sandbox;
        }
        if (s == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        return s;
    }

    private String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Builds a single-file tar archive relative to the workspace root. */
    private byte[] buildSingleFileArchive(Sandbox active, String path, byte[] content)
            throws IOException {
        if (content == null) {
            throw new IOException("File content must not be null");
        }

        String archivePath = resolveArchivePath(active, path);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(output)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(archivePath);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return output.toByteArray();
    }

    /** Constrains an upload path to the workspace and converts it to an archive path. */
    private String resolveArchivePath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);

        if (normalized.startsWith("/")) {
            String workspaceRoot = resolveWorkspaceRoot(active);
            String rootPrefix = "/".equals(workspaceRoot) ? "/" : workspaceRoot + "/";
            if (!normalized.startsWith(rootPrefix)) {
                throw new IOException("Upload path is outside the sandbox workspace: " + path);
            }
            normalized = normalized.substring(rootPrefix.length());
        }

        if (normalized.isBlank()) {
            throw new IOException("Upload path must identify a file: " + path);
        }
        return normalized;
    }

    /**
     * Normalizes an upload path and resolves it to its sandbox-absolute form so
     * workspace-relative paths can ride the native single-file transfer
     * ({@link SandboxFileTransfer#uploadFile}) instead of the archive-hydrate fallback. Throws
     * {@link IllegalArgumentException} for invalid paths — the caller fails just that file,
     * matching the archive fallback's validation contract.
     */
    private String resolveTransferPath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return resolveWorkspaceRoot(active) + "/" + normalized;
    }

    /** Normalizes separators and strips leading {@code ./} segments. */
    private static String normalizeUploadPath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /** Resolves the normalized workspace root used to convert absolute upload paths. */
    private String resolveWorkspaceRoot(Sandbox active) throws IOException {
        SandboxState state = active.getState();
        WorkspaceSpec workspaceSpec = state != null ? state.getWorkspaceSpec() : null;
        String root = workspaceSpec != null ? workspaceSpec.getRoot() : null;
        if (root == null || root.isBlank()) {
            throw new IOException("Sandbox workspace root is unavailable");
        }

        String normalized = root.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("/")) {
            throw new IOException("Sandbox workspace root must be absolute: " + root);
        }
        return normalized;
    }
}
