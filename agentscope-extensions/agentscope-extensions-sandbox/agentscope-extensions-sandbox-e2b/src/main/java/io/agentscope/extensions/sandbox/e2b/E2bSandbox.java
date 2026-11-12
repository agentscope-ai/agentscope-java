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
import java.util.ArrayList;
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
    protected boolean probeWorkspaceRootForPreservedResume() {
        if (e2bState.isWorkspaceOnVolume()) {
            // Files live on a persistent volume — the directory is durable across sandbox
            // recreations, so treat it as preserved without probing.
            return true;
        }
        return super.probeWorkspaceRootForPreservedResume();
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (e2bState.getPersistenceMode() == E2bPersistenceMode.NATIVE_SNAPSHOT) {
            // Native snapshots preserve the software environment (installed deps, system state);
            // volume bytes stay durable via the volume itself and mounts are re-attached on
            // restore. Keep the snapshot call even when the workspace sits on a volume.
            // Verified live
            // (E2bVolumeMountLiveTest#nestedMountContentSurvivesNativeSnapshotRestoreUnchanged):
            // platform snapshots do not capture mounted volume bytes, so a nested mount under a
            // non-volume workspace root is unaffected by snapshot restore.
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
        if (e2bState.isWorkspaceOnVolume()) {
            // Workspace bytes are durable via the volume — nothing to archive. The empty stream
            // doubles as the no-op marker read back by doHydrateWorkspace: a session flipping
            // between volume-backed and plain persistence replaces a real archive with this
            // empty one, meaning "hydrate nothing, the volume already holds the files".
            return InputStream.nullInputStream();
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
        if (plan.isAbsent()) {
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
        if (all.length == 0) {
            // Volume-backed workspaces persist nothing to hydrate (see doPersistWorkspace); the
            // empty archive is an intentional no-op marker, not an error, and must not overwrite
            // what a previous non-volume archive contained.
            return;
        }
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
        // mkdir's own cwd must already exist: a workspace root on a freshly mounted volume
        // (e.g. /mnt/data/ws) typically does not exist yet, so run from / instead. The
        // mkdir target is absolute and unaffected by the cwd.
        envd().runShell(
                        e2bState,
                        "/",
                        "mkdir -p " + shellSingleQuote(e2bState.getWorkspaceRoot()),
                        30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        // No-op by contract. An E2B workspace's lifetime is bounded by the sandbox itself, so
        // destruction is delegated to sandbox teardown; running "rm -rf" here beforehand would
        // stretch a deletion through any nested volume mount into shared, persistent data
        // without ever having an observable effect (the disk vanishes with the sandbox).
        log.debug("[sandbox-e2b] workspace destruction is a no-op; sandbox teardown releases it");
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
                sandbox = createSandboxForRestore();
                e2bState.setWorkspaceRootReady(false);
                e2bState.setWorkspaceProjectionHash(null);
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
     *
     * <p>A sandbox created from the template cannot hold the workspace this session had, so every
     * template-created path clears the workspace-ready flags; only a sandbox created from the
     * native snapshot keeps them, which lets {@code start()} apply ephemeral entries alone.
     */
    private JsonNode createSandboxForRestore() throws IOException {
        NativeSnapshotPeek peek = peekNativeSnapshot();
        if (peek.missing()) {
            nativeSnapshotPlan = NativeSnapshotPlan.ABSENT;
            log.warn(
                    "[sandbox-e2b] snapshot record {} is not restorable; creating an empty"
                            + " workspace from the template",
                    e2bState.getSnapshot() != null ? e2bState.getSnapshot().getId() : "unknown");
            JsonNode created =
                    platform.createSandbox(
                            e2bState.getTemplateId(),
                            opt.getSandboxTimeoutSeconds(),
                            e2bState.getVolumeMounts(),
                            resolveEnvVars());
            markWorkspaceUnready();
            return created;
        }
        String snapshotId = peek.snapshotId();
        if (snapshotId != null) {
            try {
                return createFromSnapshot(snapshotId, peek.archive());
            } catch (E2bPlatformHttp.PlatformHttpException e) {
                if (e.statusCode() != 404) {
                    throw snapshotRestoreFailure(
                            "Failed to create sandbox from native snapshot " + snapshotId, e);
                }
                log.warn(
                        "[sandbox-e2b] native snapshot {} no longer exists (HTTP 404); creating an"
                                + " empty workspace from the template",
                        snapshotId);
                nativeSnapshotPlan = NativeSnapshotPlan.ABSENT;
                JsonNode created =
                        platform.createSandbox(
                                e2bState.getTemplateId(),
                                opt.getSandboxTimeoutSeconds(),
                                e2bState.getVolumeMounts(),
                                resolveEnvVars());
                markWorkspaceUnready();
                return created;
            } catch (SandboxException e) {
                throw e;
            } catch (Exception e) {
                throw snapshotRestoreFailure(
                        "Failed to create sandbox from native snapshot " + snapshotId, e);
            }
        }
        nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
        JsonNode created =
                platform.createSandbox(
                        e2bState.getTemplateId(),
                        opt.getSandboxTimeoutSeconds(),
                        e2bState.getVolumeMounts(),
                        resolveEnvVars());
        markWorkspaceUnready();
        return created;
    }

    private SandboxException.SandboxRuntimeException snapshotRestoreFailure(
            String message, Exception cause) {
        return new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.SNAPSHOT_RESTORE_ERROR,
                message + ": " + cause.getMessage(),
                cause);
    }

    private void markWorkspaceUnready() {
        e2bState.setWorkspaceRootReady(false);
        e2bState.setWorkspaceProjectionHash(null);
    }

    private JsonNode createFromSnapshot(String snapshotId, byte[] archive) throws IOException {
        JsonNode created =
                platform.createSandbox(
                        snapshotId,
                        opt.getSandboxTimeoutSeconds(),
                        e2bState.getVolumeMounts(),
                        resolveEnvVars());
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
                return NativeSnapshotPeek.MISSING;
            }
            try (InputStream in = snapshot.restore()) {
                E2bSnapshotRefs.SnapshotIdRef ref = E2bSnapshotRefs.peekSnapshotId(in);
                return new NativeSnapshotPeek(ref.snapshotId(), ref.archive(), false);
            }
        } catch (Exception e) {
            throw snapshotRestoreFailure("Failed to read native snapshot " + snapshot.getId(), e);
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
        ABSENT
    }

    private record NativeSnapshotPlan(
            NativeSnapshotPlanStatus status, String snapshotId, byte[] archive) {

        private static final NativeSnapshotPlan DEFAULT =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.DEFAULT, null, null);

        private static final NativeSnapshotPlan ABSENT =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.ABSENT, null, null);

        private static NativeSnapshotPlan restored(String snapshotId, byte[] archive) {
            return new NativeSnapshotPlan(NativeSnapshotPlanStatus.RESTORED, snapshotId, archive);
        }

        private boolean isRestored() {
            return status == NativeSnapshotPlanStatus.RESTORED;
        }

        private boolean isAbsent() {
            return status == NativeSnapshotPlanStatus.ABSENT;
        }
    }

    private record NativeSnapshotPeek(String snapshotId, byte[] archive, boolean missing) {

        private static final NativeSnapshotPeek NOT_NATIVE =
                new NativeSnapshotPeek(null, null, false);

        private static final NativeSnapshotPeek MISSING = new NativeSnapshotPeek(null, null, true);
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

    /**
     * {@code tar --exclude} args for volume mounts nested strictly beneath the workspace root.
     * A mount covering the root needs no exclusion (persistence is a no-op in that case).
     */
    private List<String> volumeTarExcludeArgs() {
        List<E2bVolumeMount> mounts = e2bState.getVolumeMounts();
        if (mounts.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(mounts.size());
        for (E2bVolumeMount m : mounts) {
            paths.add(m.getPath());
        }
        return WorkspaceMountSupport.tarExcludeArgsForAbsolutePaths(
                e2bState.getWorkspaceRoot(), paths);
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
            // Quote: exclude args derive from user-configured paths and may contain spaces.
            script.append(shellSingleQuote(ex)).append(' ');
        }
        // Apply volume-mount excludes on both branches. The create branch must not re-duplicate
        // volume bytes into the archive; the extract branch must not let archive entries that
        // predate the current mount configuration (e.g. a NATIVE_SNAPSHOT archive written before
        // the volume was mounted under the workspace root) write through the mount point into
        // the shared volume.
        for (String ex : volumeTarExcludeArgs()) {
            script.append(shellSingleQuote(ex)).append(' ');
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
        String normalizedRoot = WorkspaceMountSupport.normalizeVirtualPath(root);
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
