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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by an Alibaba Cloud AgentRun sandbox
 * (FC 3.0 Sandbox API 2025-09-10).
 *
 * <p>Execution runs over the AgentRun MCP server bundled with the sandbox template. When the
 * sandbox state declares {@code workspaceOnNas=true}, persistence is delegated entirely to the
 * NAS mount and {@link #doPersistWorkspace()} is a no-op; otherwise the sandbox falls back to a
 * tar-via-MCP archive identical in shape to Daytona's payload.
 */
public class AgentRunSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final int TAR_TIMEOUT_SECONDS = 300;
    private static final int START_WAIT_SECONDS = 300;
    private static final int B64_CHUNK = 4000;

    private final AgentRunSandboxState arState;
    private final AgentRunSandboxClientOptions options;
    private final AgentRunDataPlaneHttp http;
    private final AgentRunExecutor mcp;

    public AgentRunSandbox(
            AgentRunSandboxState state,
            AgentRunSandboxClientOptions options,
            AgentRunDataPlaneHttp http,
            AgentRunExecutor mcp) {
        super(state);
        this.arState = state;
        this.options = options;
        this.http = http;
        this.mcp = mcp;
    }

    @Override
    public void start() throws Exception {
        if (WorkspaceMountSupport.hasBindMounts(arState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-agentrun] WorkspaceSpec contains bind_mount entries; "
                            + "AgentRun does not apply host bind mounts — paths are not mounted.");
        }
        ensureSandbox();
        mcp.connect();
        super.start();
    }

    @Override
    public void stop() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            try {
                mcp.exec("sync", null, 5);
            } catch (Exception e) {
                log.warn("[sandbox-agentrun] sync before stop failed: {}", e.getMessage());
            }
        }
        super.stop();
    }

    @Override
    public void shutdown() throws Exception {
        try {
            mcp.close();
        } catch (Exception ignore) {
            // best-effort
        }
        if (!arState.isSandboxOwned()) {
            return;
        }
        String id = arState.getSandboxId();
        if (id != null && !id.isBlank()) {
            http.deleteSandbox(id);
        }
    }

    @Override
    protected boolean probeWorkspaceRootForPreservedResume() {
        if (arState.isWorkspaceOnNas()) {
            // Files live on a persistent mount — the directory is durable across sandbox
            // recreations, so we treat it as preserved without probing.
            return true;
        }
        return super.probeWorkspaceRootForPreservedResume();
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        AgentRunMcpChannel.ExecResult r =
                mcp.exec(command, relativeOrAbsoluteCwd(), timeoutSeconds);
        String out = r.stdout != null ? r.stdout : "";
        boolean truncated = out.length() >= OUTPUT_TRUNCATE_BYTES;
        if (truncated) {
            out = out.substring(0, OUTPUT_TRUNCATE_BYTES);
        }
        ExecResult result = new ExecResult(r.exitCode, out, r.stderr, truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(r.exitCode, out, r.stderr);
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            // Persistence is handled by the NAS/OSS mount — nothing to archive.
            return InputStream.nullInputStream();
        }
        String root = arState.getWorkspaceRoot();
        String cmd = "tar -cf - -C " + shellSingleQuote(root) + " . | base64 -w0";
        AgentRunMcpChannel.ExecResult r = mcp.exec(cmd, null, TAR_TIMEOUT_SECONDS);
        if (r.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun tar failed (exit=" + r.exitCode + "): " + r.stderr);
        }
        String b64 = (r.stdout != null ? r.stdout : "").replace("\n", "").replace("\r", "");
        byte[] raw = Base64.getDecoder().decode(b64);
        return new ByteArrayInputStream(raw);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = arState.getWorkspaceRoot();
        byte[] all = archive.readAllBytes();
        if (all.length == 0) {
            return;
        }
        ensureWorkspaceRoot();
        String b64 = Base64.getEncoder().encodeToString(all);
        mcp.exec("rm -f /tmp/agentscope-ws.b64", null, 30);
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            String chunk = b64.substring(i, Math.min(b64.length(), i + B64_CHUNK));
            String py =
                    "import pathlib; pathlib.Path('/tmp/agentscope-ws.b64').open('a').write("
                            + jsonLiteral(chunk)
                            + ")";
            AgentRunMcpChannel.ExecResult chunkRes =
                    mcp.exec("python3 -c " + shellSingleQuote(py), null, 120);
            if (chunkRes.exitCode != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "AgentRun chunk write failed: " + chunkRes.stderr);
            }
        }
        String pyFin =
                "import base64,pathlib,subprocess; d="
                        + jsonLiteral(root)
                        + "; raw=base64.standard_b64decode(pathlib.Path('/tmp/agentscope-ws.b64').read_text());"
                        + " subprocess.run(['tar','xf','-','-C',d],input=raw,check=True)";
        AgentRunMcpChannel.ExecResult finRes =
                mcp.exec("python3 -c " + shellSingleQuote(pyFin), null, TAR_TIMEOUT_SECONDS);
        if (finRes.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun tar extract failed: " + finRes.stderr);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        ensureWorkspaceRoot();
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            // Do not destroy NAS-backed workspaces; the mount is shared/persistent.
            return;
        }
        try {
            mcp.exec("rm -rf " + shellSingleQuote(arState.getWorkspaceRoot()), null, 30);
        } catch (Exception e) {
            // best-effort
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return arState.getWorkspaceRoot();
    }

    private void ensureWorkspaceRoot() throws Exception {
        AgentRunMcpChannel.ExecResult r =
                mcp.exec("mkdir -p " + shellSingleQuote(arState.getWorkspaceRoot()), null, 30);
        if (r.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "AgentRun workspace root setup failed (exit=" + r.exitCode + "): " + r.stderr);
        }
    }

    private void ensureSandbox() throws Exception {
        String id = arState.getSandboxId();
        if (id == null || id.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun sandbox id is not set — call AgentRunSandboxClient#create or resume");
        }
        try {
            http.getSandbox(id);
            // Already exists; assume it's reusable (READY/RUNNING). Poll once to be sure.
            http.waitUntilReady(id, 30);
            return;
        } catch (SandboxException.SandboxRuntimeException e) {
            // Most likely 404 — fall through to create
            if (!isNotFound(e)) {
                throw e;
            }
            arState.setWorkspaceRootReady(false);
        }
        http.createSandbox(id);
        http.waitUntilReady(id, START_WAIT_SECONDS);
    }

    private static boolean isNotFound(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 404") || m.contains("NotFound"));
    }

    private String relativeOrAbsoluteCwd() {
        String root = arState.getWorkspaceRoot();
        return root != null && !root.isBlank() ? root : null;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String jsonLiteral(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    interface AgentRunExecutor extends AutoCloseable {
        void connect();

        AgentRunMcpChannel.ExecResult exec(String command, String cwd, int timeoutSeconds);

        @Override
        void close();
    }
}
