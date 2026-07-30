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
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSandboxFilesystem} that delegates execution to a live {@link Sandbox}.
 *
 * <p>Stable proxy created at agent build time. The active {@link Sandbox} for an operation is
 * resolved <b>per call</b> from the {@link RuntimeContext} (bound by {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} via {@code
 * ctx.put(Sandbox.class, ...)}), so concurrent calls on the same agent instance each see their
 * own sandbox.
 *
 * <p>The agent-level {@link SandboxAware} slot is kept only as a fallback for internal paths
 * that operate without a per-call context (e.g. {@code RuntimeContext.empty()} maintenance
 * writes); it must not be relied on when calls may run concurrently.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

    private final String fsId;

    /** Fallback slot for context-less paths; compare-and-set cleared to avoid cross-call races. */
    private final AtomicReference<Sandbox> fallbackSandbox = new AtomicReference<>();

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void setSandbox(Sandbox sandbox) {
        this.fallbackSandbox.set(sandbox);
    }

    @Override
    public Sandbox getSandbox() {
        return fallbackSandbox.get();
    }

    /**
     * Clears the fallback slot only if it still holds {@code expected}. Used by the lifecycle
     * middleware on release so a finishing call never wipes a sandbox injected by a newer
     * concurrent call.
     */
    public void clearSandbox(Sandbox expected) {
        fallbackSandbox.compareAndSet(expected, null);
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

            if (active instanceof SandboxFileTransfer transfer
                    && transfer.supportsFileTransfer(path)) {
                try {
                    transfer.uploadFile(path, content);
                    results.add(FileUploadResponse.success(path));
                } catch (Exception e) {
                    log.warn("[sandbox-fs] native upload failed for path: {}", path, e);
                    results.add(FileUploadResponse.fail(path, e.getMessage()));
                }
                continue;
            }

            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = shellSingleQuote(path);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && "
                                + "printf '%s' '"
                                + base64Content
                                + "' | base64 -d > "
                                + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    results.add(FileUploadResponse.success(path));
                } else {
                    results.add(FileUploadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(path, combined));
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

    private Sandbox requireSandbox(RuntimeContext runtimeContext) {
        // Per-call binding first: each in-flight call sees the sandbox bound to its own context,
        // so concurrent calls on the same agent instance never observe each other's sandbox.
        if (runtimeContext != null) {
            Sandbox bound = runtimeContext.get(Sandbox.class);
            if (bound != null) {
                return bound;
            }
        }
        // Fallback for context-less internal paths (RuntimeContext.empty() maintenance writes).
        Sandbox s = fallbackSandbox.get();
        if (s == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        return s;
    }

    private String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
