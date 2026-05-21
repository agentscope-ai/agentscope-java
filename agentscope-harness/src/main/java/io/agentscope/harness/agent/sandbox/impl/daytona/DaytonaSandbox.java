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
package io.agentscope.harness.agent.sandbox.impl.daytona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by Daytona cloud sandboxes. */
public class DaytonaSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(DaytonaSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final int TAR_TIMEOUT_SECONDS = 300;
    private static final int B64_CHUNK = 4000;

    private final DaytonaSandboxState daytonaState;
    private final DaytonaHttp http;

    public DaytonaSandbox(DaytonaSandboxState state, DaytonaHttp http) {
        super(state);
        this.daytonaState = state;
        this.http = http;
    }

    @Override
    public void start() throws Exception {
        if (WorkspaceMountSupport.hasBindMounts(daytonaState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-daytona] WorkspaceSpec contains bind_mount entries; "
                            + "Daytona does not apply host bind mounts — paths are not mounted.");
        }
        ensureSandbox();
        super.start();
    }

    @Override
    public void shutdown() throws Exception {
        if (!daytonaState.isSandboxOwned()) {
            return;
        }
        String id = daytonaState.getSandboxId();
        if (id != null && !id.isBlank()) {
            http.deleteSandbox(id);
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        JsonNode j =
                http.execute(
                        daytonaState.getSandboxId(),
                        command,
                        relativeCwd(daytonaState.getWorkspaceRoot()),
                        timeoutSeconds);
        int exit = j.path("exitCode").asInt(-1);
        String out = j.path("result").asText("");
        boolean truncated = out.length() >= OUTPUT_TRUNCATE_BYTES;
        if (truncated) {
            out = out.substring(0, OUTPUT_TRUNCATE_BYTES);
        }
        ExecResult r = new ExecResult(exit, out, "", truncated);
        if (!r.ok()) {
            throw new SandboxException.ExecException(exit, out, "");
        }
        return r;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String root = daytonaState.getWorkspaceRoot();
        String cmd = "tar -cf - -C " + shellSingleQuote(root) + " . | base64 -w0";
        JsonNode j =
                http.execute(
                        daytonaState.getSandboxId(), cmd, relativeCwd(root), TAR_TIMEOUT_SECONDS);
        int exit = j.path("exitCode").asInt(-1);
        if (exit != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "Daytona tar failed (exit=" + exit + "): " + j.path("result").asText(""));
        }
        String b64 = j.path("result").asText("").replace("\n", "").replace("\r", "");
        byte[] raw = Base64.getDecoder().decode(b64);
        return new ByteArrayInputStream(raw);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = daytonaState.getWorkspaceRoot();
        String rel = relativeCwd(root);
        byte[] all = archive.readAllBytes();
        String b64 = Base64.getEncoder().encodeToString(all);
        http.execute(daytonaState.getSandboxId(), "rm -f /tmp/agentscope-ws.b64", rel, 30);
        ObjectMapper om = new ObjectMapper();
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            String chunk = b64.substring(i, Math.min(b64.length(), i + B64_CHUNK));
            String lit = om.writeValueAsString(chunk);
            String py =
                    "import pathlib; pathlib.Path('/tmp/agentscope-ws.b64').open('a').write("
                            + lit
                            + ")";
            JsonNode j =
                    http.execute(
                            daytonaState.getSandboxId(),
                            "python3 -c " + shellSingleQuote(py),
                            rel,
                            120);
            if (j.path("exitCode").asInt(-1) != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "Daytona chunk write failed: " + j.path("result").asText(""));
            }
        }
        String pyFin =
                "import base64,pathlib,subprocess; d="
                        + om.writeValueAsString(root)
                        + "; raw=base64.standard_b64decode(pathlib.Path('/tmp/agentscope-ws.b64').read_text());"
                        + " subprocess.run(['tar','xf','-','-C',d],input=raw,check=True)";
        JsonNode fin =
                http.execute(
                        daytonaState.getSandboxId(),
                        "python3 -c " + shellSingleQuote(pyFin),
                        rel,
                        TAR_TIMEOUT_SECONDS);
        if (fin.path("exitCode").asInt(-1) != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Daytona tar extract failed: " + fin.path("result").asText(""));
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        exec(null, "mkdir -p " + shellSingleQuote(daytonaState.getWorkspaceRoot()), 30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            exec(null, "rm -rf " + shellSingleQuote(daytonaState.getWorkspaceRoot()), 30);
        } catch (Exception e) {
            // best-effort
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return daytonaState.getWorkspaceRoot();
    }

    private void ensureSandbox() throws Exception {
        if (daytonaState.getSandboxId() == null || daytonaState.getSandboxId().isBlank()) {
            String id = http.createSandbox();
            daytonaState.setSandboxId(id);
            http.startSandbox(id);
            http.waitUntilStarted(id, 300);
            return;
        }
        try {
            http.getSandbox(daytonaState.getSandboxId());
        } catch (Exception e) {
            daytonaState.setWorkspaceRootReady(false);
            String id = http.createSandbox();
            daytonaState.setSandboxId(id);
            http.startSandbox(id);
            http.waitUntilStarted(id, 300);
            return;
        }
        http.startSandbox(daytonaState.getSandboxId());
        http.waitUntilStarted(daytonaState.getSandboxId(), 120);
    }

    private static String relativeCwd(String abs) {
        if (abs == null || abs.isBlank()) {
            return "";
        }
        return abs.startsWith("/") ? abs.substring(1) : abs;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
