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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OpenSandbox implementation of the Harness sandbox lifecycle. */
public class OpenSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {
    private static final Logger log = LoggerFactory.getLogger(OpenSandbox.class);

    private final OpenSandboxState state;
    private final OpenSandboxClientOptions options;
    private final OpenSandboxSdk sdk;
    private OpenSandboxSdk.Handle handle;
    private boolean terminated;

    OpenSandbox(OpenSandboxState state, OpenSandboxClientOptions options, OpenSandboxSdk sdk) {
        super(Objects.requireNonNull(state, "state"));
        this.state = state;
        this.options = OpenSandboxClientOptions.copyOf(options);
        this.sdk = Objects.requireNonNull(sdk, "sdk");
    }

    @Override
    public void start() throws Exception {
        ensureSandbox();
        try {
            super.start();
        } catch (Exception e) {
            closeHandle();
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        try {
            super.stop();
        } finally {
            closeHandle();
        }
    }

    @Override
    public void shutdown() throws Exception {
        Exception failure = null;
        try {
            closeHandle();
        } catch (Exception e) {
            failure = e;
        }
        if (!terminated && state.isSandboxOwned() && !isBlank(state.getSandboxId())) {
            try {
                sdk.kill(state.getSandboxId(), options);
                terminated = true;
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return checkedExec(command, getWorkspaceRoot(), timeoutSeconds);
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String temp = tempArchive("persist");
        StringBuilder command = new StringBuilder("tar ");
        for (String exclude :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(state.getWorkspaceSpec())) {
            command.append(shellQuote(exclude)).append(' ');
        }
        command.append("-cf ")
                .append(shellQuote(temp))
                .append(" -C ")
                .append(shellQuote(getWorkspaceRoot()))
                .append(" .");
        checkedExec(command.toString(), getWorkspaceRoot(), 120);
        try (InputStream remote = handle().read(temp)) {
            return new ByteArrayInputStream(remote.readAllBytes());
        } finally {
            cleanup(temp);
        }
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String temp = tempArchive("hydrate");
        handle().write(temp, archive.readAllBytes());
        try {
            checkedExec("mkdir -p " + shellQuote(getWorkspaceRoot()), getWorkspaceRoot(), 30);
            checkedExec(
                    "tar -xf " + shellQuote(temp) + " -C " + shellQuote(getWorkspaceRoot()),
                    getWorkspaceRoot(),
                    120);
        } finally {
            cleanup(temp);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        checkedExec("mkdir -p " + shellQuote(getWorkspaceRoot()), getWorkspaceRoot(), 30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        checkedExec("rm -rf " + shellQuote(getWorkspaceRoot()), "/", 30);
    }

    @Override
    public String getWorkspaceRoot() {
        return state.getWorkspaceSpec().getRoot();
    }

    @Override
    public boolean supportsFileTransfer(String absolutePath) {
        return absolutePath != null && absolutePath.startsWith("/");
    }

    @Override
    public void uploadFile(String absolutePath, byte[] content) throws Exception {
        requireAbsolute(absolutePath);
        Objects.requireNonNull(content, "content");
        int slash = absolutePath.lastIndexOf('/');
        if (slash > 0) {
            checkedExec(
                    "mkdir -p " + shellQuote(absolutePath.substring(0, slash)),
                    getWorkspaceRoot(),
                    30);
        }
        handle().write(absolutePath, content);
    }

    @Override
    public byte[] downloadFile(String absolutePath) throws Exception {
        requireAbsolute(absolutePath);
        try (InputStream input = handle().read(absolutePath)) {
            return input.readAllBytes();
        }
    }

    private void ensureSandbox() throws Exception {
        if (handle != null) {
            return;
        }
        if (isBlank(state.getSandboxId())) {
            assign(sdk.create(state, options));
            return;
        }
        try {
            assign(sdk.connect(state.getSandboxId(), options));
        } catch (Exception error) {
            if (!sdk.isNotFound(error)) {
                throw error;
            }
            state.setWorkspaceRootReady(false);
            assign(sdk.create(state, options));
        }
    }

    private void assign(OpenSandboxSdk.Handle created) {
        handle = Objects.requireNonNull(created, "OpenSandbox SDK returned a null handle");
        state.setSandboxId(handle.id());
        terminated = false;
    }

    private ExecResult checkedExec(String command, String workingDirectory, int timeoutSeconds)
            throws Exception {
        ExecResult result = handle().exec(command, workingDirectory, Math.max(timeoutSeconds, 1));
        if (!result.ok()) {
            throw new SandboxException.ExecException(
                    result.exitCode(), result.stdout(), result.stderr());
        }
        return result;
    }

    private OpenSandboxSdk.Handle handle() {
        if (handle == null) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "OpenSandbox is not connected; call start() first");
        }
        return handle;
    }

    private void closeHandle() throws Exception {
        if (handle == null) {
            return;
        }
        OpenSandboxSdk.Handle current = handle;
        handle = null;
        current.close();
    }

    private void cleanup(String path) {
        if (handle == null) {
            return;
        }
        try {
            handle.exec("rm -f " + shellQuote(path), getWorkspaceRoot(), 30);
        } catch (Exception e) {
            log.debug("[sandbox-opensandbox] Failed to clean temporary archive {}", path, e);
        }
    }

    private String tempArchive(String operation) {
        String session = state.getSessionId() == null ? "unknown" : state.getSessionId();
        return "/tmp/agentscope-"
                + operation
                + "-"
                + Integer.toHexString(session.hashCode())
                + ".tar";
    }

    private static void requireAbsolute(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("OpenSandbox file path must be absolute: " + path);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
