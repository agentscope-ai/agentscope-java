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
package io.agentscope.harness.agent.sandbox.impl.agentrun;

/**
 * NAS mount configuration for an AgentRun sandbox.
 *
 * <p>NAS mounts are configured at sandbox creation time and provide instance-level persistent
 * storage that survives sandbox restarts. Use a NAS mount as the {@code workspaceRoot} backing
 * store to get "free" persistence without managing tar snapshots.
 */
public class AgentRunNasMountConfig {

    private String serverAddr;
    private String mountDir;
    private String remotePath = "/";
    private boolean enableTLS = false;

    /** Default constructor. */
    public AgentRunNasMountConfig() {}

    /**
     * Returns the NAS server address (e.g. {@code 12345-abc.cn-hangzhou.nas.aliyuncs.com}).
     *
     * @return NAS server address
     */
    public String getServerAddr() {
        return serverAddr;
    }

    public AgentRunNasMountConfig setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
        return this;
    }

    /**
     * Returns the in-sandbox mount directory (must start with {@code /home}, {@code /mnt} or
     * {@code /data}).
     *
     * @return absolute mount path
     */
    public String getMountDir() {
        return mountDir;
    }

    public AgentRunNasMountConfig setMountDir(String mountDir) {
        this.mountDir = mountDir;
        return this;
    }

    /**
     * Returns the remote path on the NAS filesystem to mount.
     *
     * @return remote path, defaults to {@code "/"}
     */
    public String getRemotePath() {
        return remotePath;
    }

    public AgentRunNasMountConfig setRemotePath(String remotePath) {
        this.remotePath = remotePath != null ? remotePath : "/";
        return this;
    }

    /**
     * Returns whether TLS is enabled for the NAS connection.
     *
     * @return true when TLS is enabled
     */
    public boolean isEnableTLS() {
        return enableTLS;
    }

    public AgentRunNasMountConfig setEnableTLS(boolean enableTLS) {
        this.enableTLS = enableTLS;
        return this;
    }
}
