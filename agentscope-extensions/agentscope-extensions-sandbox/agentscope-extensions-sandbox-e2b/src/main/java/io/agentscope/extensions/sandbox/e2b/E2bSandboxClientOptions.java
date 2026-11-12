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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;

/** Options for {@link E2bSandboxClient}. */
public class E2bSandboxClientOptions extends SandboxClientOptions {

    private OkHttpClient httpClient;
    private String apiKey;
    private String apiBaseUrl = "https://api.e2b.app";
    private String domain = "e2b.app";

    /**
     * Fixed data-plane URL replacing the per-sandbox {@code <port>-<sandboxId>.<domain>} envd
     * address — the equivalent of the official SDK's {@code E2B_SANDBOX_URL}. The gateway behind
     * it must route by the {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} request headers (sent
     * on every envd request). When set, the data plane ignores {@code domain}; control-plane
     * calls still use {@link #getApiBaseUrl()}.
     */
    private String envdSandboxUrl;

    /** E2B template id (or snapshot id when creating from a snapshot). */
    private String templateId = "base";

    /** Absolute path of the workspace root inside the sandbox. */
    private String workspaceRoot = "/home/user";

    private int sandboxTimeoutSeconds = 300;
    private String runUser = "user";
    private E2bPersistenceMode persistenceMode = E2bPersistenceMode.TAR;
    private E2bCodec codec = E2bCodec.PROTO;

    /** Pre-existing E2B volumes to mount at sandbox creation time. */
    private List<E2bVolumeMount> volumeMounts = new ArrayList<>();

    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 120;
    private int maxRetries = 3;

    /**
     * Maximum number of snapshots created by this session that are kept on shutdown; {@code <= 0}
     * disables pruning. Defaults to {@code 0} (disabled), matching the historical no-pruning
     * behaviour.
     *
     * <p>Pruning runs once in {@link E2bSandbox#shutdown} after the sandbox is killed (E2B locks a
     * snapshot template while a sandbox restored from it is running, so deleting earlier returns
     * HTTP 400). The session records every snapshot id it creates in {@link
     * E2bSandboxState#getSnapshotIds()} in creation order (most recent last); cleanup keeps the
     * last {@code snapshotRetention} recorded ids and deletes the rest via {@code DELETE
     * /templates/{snapshotId}} (404 is treated as already deleted). Snapshots never recorded in
     * this list — e.g. created by other sessions, or by a run whose state was never persisted —
     * are never touched. Blank ids are dropped and duplicates collapsed (first occurrence wins)
     * before counting. A snapshot whose deletion fails is kept in the record so a later
     * shutdown can retry, which may temporarily leave more than {@code snapshotRetention} ids
     * recorded. A failed stop removes the id its own persist recorded, so the record always
     * matches the archive and unconditional cleanup never removes the referenced snapshot (ids
     * recorded concurrently by another session over the same state are left alone); the price is
     * that a persist failure strands the just-created cloud snapshot unreferenced — it is never
     * retried.
     *
     * <p>Like {@link #setMaxRetries(int)} and the timeout fields, this value is overridden by the
     * per-call options passed to {@link E2bSandboxClient#create}, but only when the call options
     * set a positive value; a call leaving it at its default does not reset the client default
     * (and a single call cannot disable a client-level retention — disable it on the client).
     */
    private int snapshotRetention = 0;

    /**
     * Environment variables injected into the sandbox at creation time. Values are resolved
     * through their suppliers on every sandbox creation (not once at construction), so dynamic
     * values such as short-lived tokens stay fresh. {@code null} values are skipped. Applies only
     * when a sandbox is created; {@code connect} to an existing sandbox keeps its original
     * environment.
     */
    private Map<String, Supplier<String>> envs = new LinkedHashMap<>();

    @Override
    public String getType() {
        return "e2b";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new E2bSandboxClient(this, null);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getDomain() {
        return domain;
    }

    /**
     * Sets the fallback envd domain used to build per-sandbox data-plane URLs such as {@code
     * https://<port>-<sandboxId>.<domain>} when {@link #getEnvdSandboxUrl()} is unset. This does
     * not affect the control-plane base URL, which remains {@link #getApiBaseUrl()}.
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getEnvdSandboxUrl() {
        return envdSandboxUrl;
    }

    /**
     * Sets a fixed data-plane gateway URL, equivalent to the official SDK's {@code
     * E2B_SANDBOX_URL}. When present, envd requests go to this gateway and route by the {@code
     * E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} headers instead of composing a per-sandbox host
     * from {@link #getDomain()}; control-plane calls still use {@link #getApiBaseUrl()}.
     */
    public void setEnvdSandboxUrl(String envdSandboxUrl) {
        this.envdSandboxUrl = envdSandboxUrl;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public String getRunUser() {
        return runUser;
    }

    public void setRunUser(String runUser) {
        this.runUser = runUser;
    }

    public E2bPersistenceMode getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(E2bPersistenceMode persistenceMode) {
        this.persistenceMode = persistenceMode != null ? persistenceMode : E2bPersistenceMode.TAR;
    }

    public E2bCodec getCodec() {
        return codec;
    }

    public void setCodec(E2bCodec codec) {
        this.codec = codec != null ? codec : E2bCodec.PROTO;
    }

    /**
     * Returns the volume mounts attached at sandbox creation time.
     *
     * @return defensive copy of the configured mounts (never null)
     */
    public List<E2bVolumeMount> getVolumeMounts() {
        if (volumeMounts == null) {
            return new ArrayList<>();
        }
        List<E2bVolumeMount> out = new ArrayList<>(volumeMounts.size());
        for (E2bVolumeMount m : volumeMounts) {
            if (m == null) {
                continue;
            }
            out.add(new E2bVolumeMount(m.getName(), m.getPath()));
        }
        return out;
    }

    public void setVolumeMounts(List<E2bVolumeMount> volumeMounts) {
        List<E2bVolumeMount> checked = new ArrayList<>();
        if (volumeMounts != null) {
            for (E2bVolumeMount m : volumeMounts) {
                if (m == null) {
                    continue;
                }
                checked.add(new E2bVolumeMount(m.getName(), m.getPath()));
            }
        }
        E2bVolumeMount.requireDistinctMountPaths(checked);
        this.volumeMounts = checked;
    }

    public E2bSandboxClientOptions addVolumeMount(String name, String path) {
        List<E2bVolumeMount> next = getVolumeMounts();
        next.add(new E2bVolumeMount(name, path));
        setVolumeMounts(next);
        return this;
    }

    public E2bSandboxClientOptions addVolumeMount(E2bVolumeMount mount) {
        if (mount == null) {
            return this;
        }
        return addVolumeMount(mount.getName(), mount.getPath());
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getSnapshotRetention() {
        return snapshotRetention;
    }

    public void setSnapshotRetention(int snapshotRetention) {
        this.snapshotRetention = snapshotRetention;
    }

    public Map<String, Supplier<String>> getEnvs() {
        return envs;
    }

    public void setEnvs(Map<String, Supplier<String>> envs) {
        this.envs = envs != null ? new LinkedHashMap<>(envs) : new LinkedHashMap<>();
    }

    /** Registers a sandbox-creation environment variable with a fixed value. */
    public void env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        envs.put(name, () -> value);
    }

    /** Registers a sandbox-creation environment variable resolved on every sandbox creation. */
    public void env(String name, Supplier<String> valueSupplier) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        envs.put(name, valueSupplier);
    }
}
