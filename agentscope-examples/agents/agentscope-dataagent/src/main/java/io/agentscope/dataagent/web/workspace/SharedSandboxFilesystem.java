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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractFilesystem} that delegates to a fixed {@link Sandbox} reference owned by
 * {@link UserSandboxRegistry}.
 *
 * <p>Used by browser-side controllers (workspace tree, file read/write, upload) so the UI sees
 * exactly the same files the agent does. Unlike {@link
 * io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem} — which is a stable
 * proxy whose sandbox is flipped per-call by {@link
 * io.agentscope.harness.agent.hook.SandboxLifecycleHook} — this filesystem holds the sandbox
 * directly, because controllers are not inside an agent-call lifecycle and have no hook to do
 * the flip for them.
 *
 * <p>The exec/upload/download mapping mirrors {@code SandboxBackedFilesystem} exactly; we don't
 * subclass it because that class implements {@link io.agentscope.harness.agent.sandbox.SandboxAware}
 * and relies on a mutable {@code sandbox} field, which is the wrong contract here (a sandbox
 * supplied to the registry must not be silently overwritten).
 */
public final class SharedSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(SharedSandboxFilesystem.class);

    private final String fsId;
    private final Sandbox sandbox;

    public SharedSandboxFilesystem(Sandbox sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.fsId = "shared-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        try {
            ExecResult result = sandbox.exec(runtimeContext, command, timeoutSeconds);
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
            log.error("[shared-sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();
            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = shellSingleQuote(path);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && printf '%s' '"
                                + base64Content
                                + "' | base64 -d > "
                                + escapedPath;
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    results.add(FileUploadResponse.success(path));
                } else {
                    results.add(FileUploadResponse.fail(path, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                String cmd = "base64 " + shellSingleQuote(path);
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    byte[] decoded =
                            Base64.getDecoder()
                                    .decode(r.stdout().trim().getBytes(StandardCharsets.UTF_8));
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
