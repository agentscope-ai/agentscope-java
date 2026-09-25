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
package io.agentscope.extensions.sandbox.e2b;

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
import java.util.List;
import java.util.UUID;
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

    /**
     * Snapshot id created by the workspace persist in flight on this instance, or {@code null} when
     * none is running. {@link #doPersistWorkspace()} sets it right before the id enters {@link
     * E2bSandboxState#getSnapshotIds()}, so {@link #stop()} can drop exactly the id this call
     * created when the archive fails to persist.
     */
    private String lastCreatedSnapshotId;

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

    /**
     * {@inheritDoc}
     *
     * <p>A failed workspace persist must not leave its snapshot id in {@link
     * E2bSandboxState#getSnapshotIds()}: the persisted archive still references the previous
     * snapshot, and a record holding the newer id as well would make {@link #cleanupSnapshots()}
     * delete the snapshot that archive restores from. The id is therefore removed by identity, not
     * by position — the record is shared state, and ids another session appended meanwhile are not
     * this call's to drop. The exception is re-thrown unchanged: {@code SandboxManager.release}
     * swallows it and the caller persists the state afterwards, so the corrected record still
     * reaches the store.
     */
    @Override
    public void stop() throws Exception {
        // Only the persist in flight may be rolled back: an id recorded by an earlier persist
        // (e.g. a direct persistWorkspace() call) must not be dropped by a later failure here.
        lastCreatedSnapshotId = null;
        try {
            super.stop();
        } catch (Exception e) {
            String created = lastCreatedSnapshotId;
            if (created != null) {
                e2bState.getSnapshotIds().remove(created);
                log.debug(
                        "[sandbox-e2b] rolled back snapshot id {} after failed workspace persist",
                        created);
            }
            throw e;
        }
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
        // Only after killSandbox are the snapshot templates unlocked by E2B (deleting them while a
        // sandbox restored from one runs returns 400), so clean up here to converge to retention.
        cleanupSnapshots();
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return envd().runShell(e2bState, getWorkspaceRoot(), command, timeoutSeconds);
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (e2bState.getPersistenceMode() == E2bPersistenceMode.NATIVE_SNAPSHOT) {
            JsonNode snap = platform.createSandboxSnapshot(e2bState.getSandboxId(), snapshotName());
            String id = snap.path("snapshotID").asText("");
            if (id.isBlank()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                        "E2B snapshot response missing snapshotID: " + snap);
            }
            lastCreatedSnapshotId = id;
            e2bState.getSnapshotIds().add(id);
            return new ByteArrayInputStream(E2bSnapshotRefs.encodeSnapshotId(id));
        }
        String root = e2bState.getWorkspaceSpec().getRoot();
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
        String root = e2bState.getWorkspaceSpec().getRoot();
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
                        "/",
                        "mkdir -p " + shellSingleQuote(e2bState.getWorkspaceSpec().getRoot()),
                        30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            envd().runShell(
                            e2bState,
                            getWorkspaceRoot(),
                            "rm -rf " + shellSingleQuote(e2bState.getWorkspaceSpec().getRoot()),
                            30);
        } catch (Exception e) {
            log.debug("[sandbox-e2b] destroy workspace best-effort: {}", e.getMessage());
        }
    }

    @Override
    public String getWorkspaceRoot() {
        return e2bState.getWorkspaceSpec().getRoot();
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
            e2bState.setWorkspaceProjectionHash(null);
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

    private void cleanupSnapshots() {
        int retention = opt.getSnapshotRetention();
        if (retention <= 0) {
            return;
        }
        // One-shot correction regardless of any earlier residue: keep the last retention ids by
        // insertion order (most recent last) and delete the rest. E2B only unlocks the
        // templates after killSandbox, so this runs on shutdown.
        try {
            List<String> kept = platform.cleanupSnapshots(e2bState.getSnapshotIds(), retention);
            e2bState.setSnapshotIds(kept);
        } catch (Exception e) {
            log.warn("[sandbox-e2b] snapshot cleanup best-effort skipped: {}", e.getMessage());
        }
    }

    /**
     * AgentScope-native snapshot alias: {@code agentscope-<shortId>-<epochMillis>}, where the
     * middle segment is an 8-hex-char short UUID generated locally and the trailing segment is the
     * creation epoch millis (kept human-readable; retention does not parse it). Retention keeps the
     * last {@code snapshotRetention} ids in {@link E2bSandboxState#getSnapshotIds()} by insertion
     * order (most recent last) and deletes the rest via {@link E2bPlatformHttp#cleanupSnapshots},
     * regardless of id format.
     */
    private static String snapshotName() {
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "agentscope-" + shortId + "-" + System.currentTimeMillis();
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
