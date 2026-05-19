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
package io.agentscope.harness.agent.sandbox.impl.e2b;

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

/**
 * {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by E2B cloud sandboxes.
 *
 * <p>Execution uses envd {@code process.Process/Start} over HTTPS (Connect+protobuf). Workspace
 * tar is streamed as binary stdout for {@link E2bPersistenceMode#TAR}; {@link
 * E2bPersistenceMode#NATIVE_SNAPSHOT} uses the platform snapshot API and {@link E2bSnapshotRefs}
 * marker bytes in the Harness snapshot stream.
 */
public class E2bSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(E2bSandbox.class);

    private static final int TAR_TIMEOUT_SECONDS = 300;
    private static final int B64_CHUNK = 4000;

    private final E2bSandboxState e2bState;
    private final E2bSandboxClientOptions opt;
    private final E2bPlatformHttp platform;
    private E2bEnvdProcessClient envd;

    public E2bSandbox(E2bSandboxState state, E2bSandboxClientOptions opt) {
        super(state);
        this.e2bState = state;
        this.opt = opt != null ? opt : new E2bSandboxClientOptions();
        this.platform = new E2bPlatformHttp(this.opt);
    }

    @Override
    public void start() throws Exception {
        if (WorkspaceMountSupport.hasBindMounts(e2bState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-e2b] WorkspaceSpec contains bind_mount entries; "
                            + "E2B does not apply host bind mounts — paths are not mounted.");
        }
        ensureSandbox();
        super.start();
    }

    @Override
    public void shutdown() throws Exception {
        if (!e2bState.isSandboxOwned()) {
            return;
        }
        String id = e2bState.getSandboxId();
        if (id != null && !id.isBlank()) {
            platform.killSandbox(id);
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return envd().runShell(e2bState, getWorkspaceRoot(), command, timeoutSeconds);
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (e2bState.getPersistenceMode() == E2bPersistenceMode.NATIVE_SNAPSHOT) {
            JsonNode snap = platform.createSandboxSnapshot(e2bState.getSandboxId());
            String id = snap.path("snapshotID").asText("");
            if (id.isBlank()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                        "E2B snapshot response missing snapshotID: " + snap);
            }
            return new ByteArrayInputStream(E2bSnapshotRefs.encodeSnapshotId(id));
        }
        String root = e2bState.getWorkspaceRoot();
        StringBuilder script = new StringBuilder("tar ");
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(e2bState.getWorkspaceSpec())) {
            script.append(ex).append(' ');
        }
        script.append("-cf - -C ").append(shellSingleQuote(root)).append(" .");
        String cmd = script.toString();
        byte[] tar = envd().runShellBinaryStdout(e2bState, root, cmd, TAR_TIMEOUT_SECONDS);
        return new ByteArrayInputStream(tar);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        byte[] all = archive.readAllBytes();
        String nativeId = E2bSnapshotRefs.decodeSnapshotIdIfPresent(all);
        if (nativeId != null && !nativeId.isBlank()) {
            restoreSandboxFromSnapshotTemplate(nativeId);
            return;
        }
        String root = e2bState.getWorkspaceRoot();
        String b64 = Base64.getEncoder().encodeToString(all);
        envd().runShell(e2bState, root, "rm -f /tmp/agentscope-ws.b64", 30);
        ObjectMapper om = new ObjectMapper();
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            String chunk = b64.substring(i, Math.min(b64.length(), i + B64_CHUNK));
            String lit = om.writeValueAsString(chunk);
            String py =
                    "import pathlib; pathlib.Path('/tmp/agentscope-ws.b64').open('a').write("
                            + lit
                            + ")";
            envd().runShell(e2bState, root, "python3 -c " + shellSingleQuote(py), 120);
        }
        String pyFin =
                "import base64,pathlib,subprocess; d="
                        + om.writeValueAsString(root)
                        + "; raw=base64.standard_b64decode(pathlib.Path('/tmp/agentscope-ws.b64').read_text());"
                        + " subprocess.run(['tar','xf','-','-C',d],input=raw,check=True)";
        envd().runShell(
                        e2bState,
                        root,
                        "python3 -c " + shellSingleQuote(pyFin),
                        TAR_TIMEOUT_SECONDS);
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        envd().runShell(
                        e2bState,
                        getWorkspaceRoot(),
                        "mkdir -p " + shellSingleQuote(e2bState.getWorkspaceRoot()),
                        30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            envd().runShell(
                            e2bState,
                            getWorkspaceRoot(),
                            "rm -rf " + shellSingleQuote(e2bState.getWorkspaceRoot()),
                            30);
        } catch (Exception e) {
            log.debug("[sandbox-e2b] destroy workspace best-effort: {}", e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return e2bState.getWorkspaceRoot();
    }

    private void ensureSandbox() throws Exception {
        if (e2bState.getSandboxId() == null || e2bState.getSandboxId().isBlank()) {
            JsonNode n =
                    platform.createSandbox(
                            e2bState.getTemplateId(), opt.getSandboxTimeoutSeconds());
            platform.applySandboxFields(e2bState, n);
            applyDefaultDomain();
            envd = null;
            return;
        }
        try {
            JsonNode n =
                    platform.connectSandbox(
                            e2bState.getSandboxId(), opt.getSandboxTimeoutSeconds());
            platform.applySandboxFields(e2bState, n);
        } catch (Exception e) {
            log.warn("[sandbox-e2b] connect failed, recreating sandbox: {}", e.getMessage());
            e2bState.setWorkspaceRootReady(false);
            JsonNode n =
                    platform.createSandbox(
                            e2bState.getTemplateId(), opt.getSandboxTimeoutSeconds());
            platform.applySandboxFields(e2bState, n);
        }
        applyDefaultDomain();
        envd = null;
    }

    private void applyDefaultDomain() {
        if (e2bState.getSandboxDomain() == null || e2bState.getSandboxDomain().isBlank()) {
            e2bState.setSandboxDomain(opt.getDomain());
        }
    }

    private void restoreSandboxFromSnapshotTemplate(String snapshotTemplateId) throws Exception {
        String oldId = e2bState.getSandboxId();
        JsonNode created =
                platform.createSandbox(snapshotTemplateId, opt.getSandboxTimeoutSeconds());
        platform.applySandboxFields(e2bState, created);
        applyDefaultDomain();
        if (e2bState.isSandboxOwned()
                && oldId != null
                && !oldId.isBlank()
                && !oldId.equals(e2bState.getSandboxId())) {
            try {
                platform.killSandbox(oldId);
            } catch (Exception e) {
                log.debug(
                        "[sandbox-e2b] failed to delete old sandbox {}: {}", oldId, e.getMessage());
            }
        }
        envd = null;
    }

    private E2bEnvdProcessClient envd() throws Exception {
        E2bEnvdProcessClient c = envd;
        if (c == null) {
            synchronized (this) {
                if (envd == null) {
                    envd = new E2bEnvdProcessClient(opt);
                }
                c = envd;
            }
        }
        return c;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
