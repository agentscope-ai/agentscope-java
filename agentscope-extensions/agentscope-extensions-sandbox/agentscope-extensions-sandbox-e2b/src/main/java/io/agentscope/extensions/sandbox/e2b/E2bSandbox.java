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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by E2B cloud sandboxes.
 *
 * <p>Execution uses envd {@code process.Process/Start} over HTTPS (Connect+protobuf). For {@link
 * E2bPersistenceMode#TAR}, the workspace tar is written to a temp file and moved through the
 * envd Filesystem REST API; {@link E2bPersistenceMode#NATIVE_SNAPSHOT} uses the platform
 * snapshot API and {@link E2bSnapshotRefs} marker bytes in the Harness snapshot stream.
 */
public class E2bSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(E2bSandbox.class);

    private static final int TAR_TIMEOUT_SECONDS = 300;

    private final E2bSandboxState e2bState;
    private final E2bSandboxClientOptions opt;
    private final E2bPlatformHttp platform;
    private volatile E2bEnvdProcessClient envd;

    /**
     * Snapshot id created by the workspace persist in flight on this instance, or {@code null} when
     * none is running. {@link #doPersistWorkspace()} sets it right before the id enters {@link
     * E2bSandboxState#getSnapshotIds()}, so {@link #stop()} can drop exactly the id this call
     * created when the archive fails to persist.
     */
    private String lastCreatedSnapshotId;

    /**
     * Restore decision made by {@link #ensureSandbox()} for the current {@link #start()} call. The
     * plan is reset at the start of every call and is deliberately instance-local: it describes
     * which source this live sandbox was built from, not a persisted property of the session.
     */
    private NativeSnapshotPlan nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;

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
        nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
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
            // A successful kill invalidates the id and avoids a dead reconnect on resume.
            e2bState.setSandboxId(null);
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
        String root = e2bState.getWorkspaceRoot();
        String tarPath = newTempTarPath();
        String script = buildTarCommand(true, tarPath, root);
        try {
            envd().runShell(e2bState, root, script, TAR_TIMEOUT_SECONDS);
            byte[] tar = envd().downloadFile(e2bState, tarPath);
            return new ByteArrayInputStream(tar);
        } finally {
            removeTempFileBestEffort(tarPath);
        }
    }

    @Override
    protected Optional<InputStream> openSnapshotArchive(SandboxSnapshot snapshot) throws Exception {
        NativeSnapshotPlan plan = nativeSnapshotPlan;
        if (plan.isUnavailable()) {
            return Optional.empty();
        }
        if (plan.isRestored()) {
            return Optional.of(new ByteArrayInputStream(plan.archive()));
        }
        return super.openSnapshotArchive(snapshot);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        byte[] all = archive.readAllBytes();
        String nativeId = E2bSnapshotRefs.decodeSnapshotIdIfPresent(all);
        if (nativeId != null && !nativeId.isBlank()) {
            restoreSandboxFromSnapshotTemplate(nativeId, all);
            return;
        }
        String tarPath = newTempTarPath();
        String root = e2bState.getWorkspaceRoot();
        String script = buildTarCommand(false, tarPath, root);
        try {
            envd().uploadFile(e2bState, tarPath, all);
            envd().runShell(e2bState, root, script, TAR_TIMEOUT_SECONDS);
        } finally {
            removeTempFileBestEffort(tarPath);
        }
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

    @Override
    public boolean supportsFileTransfer(String absolutePath) {
        return workspaceTransferPath(absolutePath) != null;
    }

    /**
     * Upload a file to the sandbox filesystem via the E2B Filesystem REST API.
     *
     * @param absolutePath absolute destination path inside the sandbox
     * @param content      file content
     */
    @Override
    public void uploadFile(String absolutePath, byte[] content) throws Exception {
        envd().uploadFile(e2bState, requireWorkspaceTransferPath(absolutePath), content);
    }

    /**
     * Download a file from the sandbox filesystem via the E2B Filesystem REST API.
     *
     * @param absolutePath absolute path inside the sandbox
     * @return file content
     */
    @Override
    public byte[] downloadFile(String absolutePath) throws Exception {
        return envd().downloadFile(e2bState, requireWorkspaceTransferPath(absolutePath));
    }

    private void ensureSandbox() throws Exception {
        JsonNode sandbox;
        if (e2bState.getSandboxId() == null || e2bState.getSandboxId().isBlank()) {
            sandbox = createSandboxForRestore();
        } else {
            try {
                sandbox =
                        platform.connectSandbox(
                                e2bState.getSandboxId(), opt.getSandboxTimeoutSeconds());
                nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
            } catch (Exception e) {
                log.warn("[sandbox-e2b] connect failed, recreating sandbox: {}", e.getMessage());
                e2bState.setWorkspaceRootReady(false);
                e2bState.setWorkspaceProjectionHash(null);
                sandbox = createSandboxForRestore();
            }
        }
        platform.applySandboxFields(e2bState, sandbox);
        applyDefaultDomain();
    }

    /**
     * Resolves configured env suppliers at sandbox-creation time. Suppliers are re-evaluated on
     * every creation so short-lived values stay fresh; {@code null} or empty values are skipped.
     */
    Map<String, String> resolveEnvVars() {
        Map<String, Supplier<String>> configured = opt.getEnvs();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        configured.forEach(
                (name, supplier) -> {
                    String value = supplier != null ? supplier.get() : null;
                    if (value != null && !value.isEmpty()) {
                        resolved.put(name, value);
                    }
                });
        return resolved;
    }

    /**
     * Creates the sandbox from the cheapest usable source: a restorable native snapshot when one is
     * present, the configured original template otherwise.
     */
    private JsonNode createSandboxForRestore() throws IOException {
        NativeSnapshotPeek peek = peekNativeSnapshot();
        if (peek.unavailable()) {
            nativeSnapshotPlan = NativeSnapshotPlan.UNAVAILABLE;
            return platform.createSandbox(
                    e2bState.getTemplateId(), opt.getSandboxTimeoutSeconds(), resolveEnvVars());
        }
        String snapshotId = peek.snapshotId();
        if (snapshotId != null) {
            try {
                return createFromSnapshot(snapshotId, peek.archive());
            } catch (Exception e) {
                log.warn(
                        "[sandbox-e2b] create from snapshot {} failed, creating from template: {}",
                        snapshotId,
                        e.getMessage());
                nativeSnapshotPlan = NativeSnapshotPlan.UNAVAILABLE;
                return platform.createSandbox(
                        e2bState.getTemplateId(), opt.getSandboxTimeoutSeconds(), resolveEnvVars());
            }
        }
        nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
        return platform.createSandbox(
                e2bState.getTemplateId(), opt.getSandboxTimeoutSeconds(), resolveEnvVars());
    }

    private JsonNode createFromSnapshot(String snapshotId, byte[] archive) throws IOException {
        JsonNode created =
                platform.createSandbox(
                        snapshotId, opt.getSandboxTimeoutSeconds(), resolveEnvVars());
        nativeSnapshotPlan = NativeSnapshotPlan.restored(snapshotId, archive);
        return created;
    }

    private NativeSnapshotPeek peekNativeSnapshot() {
        if (e2bState.getPersistenceMode() != E2bPersistenceMode.NATIVE_SNAPSHOT) {
            return NativeSnapshotPeek.NOT_NATIVE;
        }
        SandboxSnapshot snapshot = e2bState.getSnapshot();
        if (snapshot == null) {
            return NativeSnapshotPeek.NOT_NATIVE;
        }
        try {
            if (!snapshot.isRestorable()) {
                return NativeSnapshotPeek.FAILED;
            }
            try (InputStream in = snapshot.restore()) {
                E2bSnapshotRefs.SnapshotIdRef ref = E2bSnapshotRefs.peekSnapshotId(in);
                if (ref == null) {
                    return NativeSnapshotPeek.NOT_NATIVE;
                }
                return new NativeSnapshotPeek(ref.snapshotId(), ref.archive(), false);
            }
        } catch (Exception e) {
            log.debug(
                    "[sandbox-e2b] native snapshot peek failed, using template: {}",
                    e.getMessage());
            return NativeSnapshotPeek.FAILED;
        }
    }

    private void applyDefaultDomain() {
        if (e2bState.getSandboxDomain() == null || e2bState.getSandboxDomain().isBlank()) {
            e2bState.setSandboxDomain(opt.getDomain());
        }
    }

    private void restoreSandboxFromSnapshotTemplate(String snapshotTemplateId, byte[] archive)
            throws Exception {
        if (nativeSnapshotPlan.isRestored()
                && snapshotTemplateId.equals(nativeSnapshotPlan.snapshotId())) {
            log.debug(
                    "[sandbox-e2b] sandbox already restored from snapshot {}, skipping rebuild",
                    snapshotTemplateId);
            return;
        }
        String oldId = e2bState.getSandboxId();
        JsonNode created = createFromSnapshot(snapshotTemplateId, archive);
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

    private enum NativeSnapshotPlanStatus {
        DEFAULT,
        RESTORED,
        UNAVAILABLE
    }

    private record NativeSnapshotPlan(
            NativeSnapshotPlanStatus status, String snapshotId, byte[] archive) {

        private static final NativeSnapshotPlan DEFAULT =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.DEFAULT, null, null);

        private static final NativeSnapshotPlan UNAVAILABLE =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.UNAVAILABLE, null, null);

        private static NativeSnapshotPlan restored(String snapshotId, byte[] archive) {
            return new NativeSnapshotPlan(NativeSnapshotPlanStatus.RESTORED, snapshotId, archive);
        }

        private boolean isRestored() {
            return status == NativeSnapshotPlanStatus.RESTORED;
        }

        private boolean isUnavailable() {
            return status == NativeSnapshotPlanStatus.UNAVAILABLE;
        }
    }

    private record NativeSnapshotPeek(String snapshotId, byte[] archive, boolean unavailable) {

        private static final NativeSnapshotPeek NOT_NATIVE =
                new NativeSnapshotPeek(null, null, false);

        private static final NativeSnapshotPeek FAILED = new NativeSnapshotPeek(null, null, true);
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

    private void removeTempFileBestEffort(String tarPath) {
        try {
            envd().runShell(e2bState, e2bState.getWorkspaceRoot(), "rm -f " + tarPath, 30);
        } catch (Exception e) {
            log.debug("[sandbox-e2b] remove temp tar best-effort: {}", e.getMessage());
        }
    }

    private static String newTempTarPath() {
        return "/tmp/agentscope-ws-" + UUID.randomUUID() + ".tar";
    }

    private String buildTarCommand(boolean create, String tarPath, String root) {
        StringBuilder script = new StringBuilder("tar ");
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(e2bState.getWorkspaceSpec())) {
            script.append(ex).append(' ');
        }
        script.append(create ? "-cf " : "-xf ")
                .append(tarPath)
                .append(" -C ")
                .append(shellSingleQuote(root));
        if (create) {
            script.append(" .");
        }
        return script.toString();
    }

    private String requireWorkspaceTransferPath(String path) {
        String transferPath = workspaceTransferPath(path);
        if (transferPath == null) {
            throw new IllegalArgumentException(
                    "Path is outside the sandbox workspace or invalid: " + path);
        }
        return transferPath;
    }

    private String workspaceTransferPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        String root = e2bState.getWorkspaceRoot();
        if (root == null || root.isBlank() || !root.startsWith("/")) {
            return null;
        }
        String normalizedRoot = root.replace('\\', '/');
        while (normalizedRoot.length() > 1 && normalizedRoot.endsWith("/")) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        String rootPrefix = "/".equals(normalizedRoot) ? "/" : normalizedRoot + "/";
        if (!path.startsWith(rootPrefix)) {
            return null;
        }
        String relative = path.substring(rootPrefix.length());
        if (relative.isEmpty()) {
            return null;
        }
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return rootPrefix + relative;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
