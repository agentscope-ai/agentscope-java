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
package io.agentscope.extensions.sandbox.kubernetes;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.sandbox.kubernetes.client.Sandbox;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harness {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by an
 * agent-sandbox CRD via the Java client SDK.
 *
 * <p>Assumes the runtime image conforms to the AgentScope runtime contract (see the sandbox
 * documentation): {@code POST /execute} interprets the command with a POSIX shell, and the
 * file API ({@code /upload} / {@code /download}) is rooted at a known base directory.
 *
 * <p>Workspace archives (persist / hydrate) are transferred through the runtime file API
 * ({@code files().read()} / {@code files().write()}) when {@code fileApiBaseDir} is
 * configured; when it is blank, transfers fall back to base64 payloads over {@code exec}.
 * Single-file transfers requested by the harness filesystem ({@link SandboxFileTransfer})
 * also go through the file API for paths under {@code fileApiBaseDir}.
 */
public class KubernetesSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandbox.class);

    /** Relative directory (under the runtime file API base dir) for temp archives. */
    private static final String TMP_DIR_REL = ".agentscope-tmp";

    private final KubernetesSandboxState k8sState;
    private final Sandbox sdkSandbox;

    public KubernetesSandbox(KubernetesSandboxState state, Sandbox sdkSandbox) {
        super(state);
        this.k8sState = state;
        this.sdkSandbox = sdkSandbox;
    }

    @Override
    public void shutdown() throws Exception {
        if (!k8sState.isClaimOwned()) {
            log.debug(
                    "[sandbox-k8s] Claim {} not owned, closing connection only",
                    k8sState.getClaimName());
            sdkSandbox.closeConnection();
            return;
        }
        try {
            sdkSandbox.terminate();
            log.debug(
                    "[sandbox-k8s] Terminated claim {} in {}",
                    k8sState.getClaimName(),
                    k8sState.getNamespace());
        } catch (Exception e) {
            log.warn(
                    "[sandbox-k8s] Failed to terminate claim {} in {}: {}",
                    k8sState.getClaimName(),
                    k8sState.getNamespace(),
                    e.getMessage());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        String wrapped = "cd " + shellQuote(k8sState.getWorkspaceRoot()) + " && (" + command + ")";
        ExecutionResult result =
                sdkSandbox.commands().run(wrapped, Duration.ofSeconds(Math.max(timeoutSeconds, 1)));

        boolean truncated = false;
        String stdout = result.getStdout() != null ? result.getStdout() : "";
        String stderr = result.getStderr() != null ? result.getStderr() : "";

        ExecResult execResult = new ExecResult(result.getExitCode(), stdout, stderr, truncated);
        if (!execResult.ok()) {
            throw new SandboxException.ExecException(result.getExitCode(), stdout, stderr);
        }
        return execResult;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String root = k8sState.getWorkspaceRoot();
        StringBuilder tarArgs = new StringBuilder();
        // The temp archive may live inside the workspace when the file API is rooted there;
        // never let the archive include itself.
        tarArgs.append("--exclude=./").append(TMP_DIR_REL).append(' ');
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(k8sState.getWorkspaceSpec())) {
            tarArgs.append(ex).append(' ');
        }

        if (fileApiEnabled()) {
            return persistViaFileApi(root, tarArgs.toString());
        }
        return persistViaExec(root, tarArgs.toString());
    }

    private InputStream persistViaFileApi(String root, String tarArgs) throws Exception {
        String tmpRel = TMP_DIR_REL + "/ws-persist-" + sessionHash() + ".tar";
        String tmpAbs = fileApiPath(tmpRel);
        String tarCmd =
                "mkdir -p "
                        + shellQuote(fileApiPath(TMP_DIR_REL))
                        + " && tar "
                        + tarArgs
                        + "-cf "
                        + shellQuote(tmpAbs)
                        + " -C "
                        + shellQuote(root)
                        + " .";
        ExecutionResult result = sdkSandbox.commands().run(tarCmd);
        if (result.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "tar failed (exit=" + result.getExitCode() + "): " + result.getStderr());
        }
        try {
            byte[] tarBytes = sdkSandbox.files().read(tmpRel);
            return new ByteArrayInputStream(tarBytes);
        } finally {
            cleanupTempFile(tmpAbs);
        }
    }

    private InputStream persistViaExec(String root, String tarArgs) throws Exception {
        String b64Script = "tar " + tarArgs + "-cf - -C " + shellQuote(root) + " . | base64";
        ExecutionResult result = sdkSandbox.commands().run(b64Script);
        if (result.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "tar+base64 failed (exit=" + result.getExitCode() + "): " + result.getStderr());
        }
        // MIME decoder tolerates line-wrapped output from GNU `base64`.
        byte[] tarBytes = java.util.Base64.getMimeDecoder().decode(result.getStdout().trim());
        return new ByteArrayInputStream(tarBytes);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = k8sState.getWorkspaceRoot();
        sdkSandbox.commands().run("mkdir -p " + shellQuote(root));

        byte[] tarBytes = archive.readAllBytes();
        if (fileApiEnabled()) {
            hydrateViaFileApi(root, tarBytes);
        } else {
            hydrateViaExec(root, tarBytes);
        }
    }

    private void hydrateViaFileApi(String root, byte[] tarBytes) throws Exception {
        String tmpRel = TMP_DIR_REL + "/ws-hydrate-" + sessionHash() + ".tar";
        String tmpAbs = fileApiPath(tmpRel);
        sdkSandbox.files().write(tmpRel, tarBytes);
        try {
            extractArchive(tmpAbs, root);
        } finally {
            cleanupTempFile(tmpAbs);
        }
    }

    private void hydrateViaExec(String root, byte[] tarBytes) throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(tarBytes);
        String tmpTar = "/tmp/ws-hydrate-" + sessionHash() + ".tar";

        String writeCmd = "echo '" + b64 + "' | base64 -d > " + tmpTar;
        ExecutionResult writeResult = sdkSandbox.commands().run(writeCmd);
        if (writeResult.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Failed to write archive to sandbox: " + writeResult.getStderr());
        }
        try {
            extractArchive(tmpTar, root);
        } finally {
            cleanupTempFile(tmpTar);
        }
    }

    private void extractArchive(String tarPath, String root) throws Exception {
        String extractCmd = "tar -xf " + shellQuote(tarPath) + " -C " + shellQuote(root);
        ExecutionResult extractResult = sdkSandbox.commands().run(extractCmd);
        if (extractResult.getExitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "tar extract failed (exit="
                            + extractResult.getExitCode()
                            + "): "
                            + extractResult.getStderr());
        }
    }

    private void cleanupTempFile(String path) {
        try {
            sdkSandbox.commands().run("rm -f " + shellQuote(path));
        } catch (Exception ignored) {
            log.debug("[sandbox-k8s] Failed to clean up temp archive {}", path);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        sdkSandbox.commands().run("mkdir -p " + shellQuote(k8sState.getWorkspaceRoot()));
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            sdkSandbox.commands().run("rm -rf " + shellQuote(k8sState.getWorkspaceRoot()));
        } catch (Exception e) {
            log.warn(
                    "[sandbox-k8s] rm -rf {} failed: {}",
                    k8sState.getWorkspaceRoot(),
                    e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return k8sState.getWorkspaceRoot();
    }

    /**
     * Returns the underlying SDK sandbox handle.
     *
     * @return SDK sandbox
     */
    public Sandbox getSdkSandbox() {
        return sdkSandbox;
    }

    @Override
    public boolean supportsFileTransfer(String absolutePath) {
        return toFileApiRelative(absolutePath) != null;
    }

    @Override
    public void uploadFile(String absolutePath, byte[] content) throws Exception {
        String rel = requireFileApiRelative(absolutePath);
        int slash = absolutePath.lastIndexOf('/');
        if (slash > 0) {
            // The runtime file API is not required to create parent directories.
            ExecutionResult mkdir =
                    sdkSandbox
                            .commands()
                            .run("mkdir -p " + shellQuote(absolutePath.substring(0, slash)));
            if (mkdir.getExitCode() != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "mkdir failed for " + absolutePath + ": " + mkdir.getStderr());
            }
        }
        sdkSandbox.files().write(rel, content);
    }

    @Override
    public byte[] downloadFile(String absolutePath) throws Exception {
        return sdkSandbox.files().read(requireFileApiRelative(absolutePath));
    }

    /**
     * Maps an absolute sandbox path to a file-API-relative path, or null when the file API
     * is disabled, the path lies outside the file API base dir, or it contains traversal
     * segments (those paths are handled by the exec transfer strategy instead).
     */
    private String toFileApiRelative(String absolutePath) {
        if (!fileApiEnabled() || absolutePath == null) {
            return null;
        }
        String base = k8sState.getFileApiBaseDir();
        String normBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (!absolutePath.startsWith(normBase + "/")) {
            return null;
        }
        String rel = absolutePath.substring(normBase.length() + 1);
        if (rel.isEmpty()) {
            return null;
        }
        for (String part : rel.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return rel;
    }

    private String requireFileApiRelative(String absolutePath) {
        String rel = toFileApiRelative(absolutePath);
        if (rel == null) {
            throw new IllegalArgumentException(
                    "Path not transferable via the runtime file API: " + absolutePath);
        }
        return rel;
    }

    private boolean fileApiEnabled() {
        String base = k8sState.getFileApiBaseDir();
        return base != null && !base.isBlank();
    }

    private String fileApiPath(String relative) {
        String base = k8sState.getFileApiBaseDir();
        return (base.endsWith("/") ? base : base + "/") + relative;
    }

    private String sessionHash() {
        return Integer.toHexString(Math.abs(k8sState.getSessionId().hashCode()));
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
