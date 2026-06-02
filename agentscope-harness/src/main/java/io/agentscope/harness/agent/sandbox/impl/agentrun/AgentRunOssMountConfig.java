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
 * OSS instance-level mount configuration for an AgentRun sandbox.
 *
 * <p>OSS mounts use ossfs FUSE and apply per-sandbox instance. Up to five mounts per sandbox
 * are supported. The mount target directory must reside under {@code /home}, {@code /mnt} or
 * {@code /data}, and the bucket must be in standard storage in the same region as the sandbox.
 */
public class AgentRunOssMountConfig {

    private String bucketName;
    private String bucketPath = "/";
    private String endpoint;
    private String mountDir;
    private boolean readOnly = false;

    /** Default constructor. */
    public AgentRunOssMountConfig() {}

    /**
     * Returns the OSS bucket name to mount.
     *
     * @return bucket name
     */
    public String getBucketName() {
        return bucketName;
    }

    public AgentRunOssMountConfig setBucketName(String bucketName) {
        this.bucketName = bucketName;
        return this;
    }

    /**
     * Returns the OSS bucket sub-path to mount.
     *
     * @return bucket path, defaults to {@code "/"}
     */
    public String getBucketPath() {
        return bucketPath;
    }

    public AgentRunOssMountConfig setBucketPath(String bucketPath) {
        this.bucketPath = bucketPath != null ? bucketPath : "/";
        return this;
    }

    /**
     * Returns the OSS endpoint (e.g. {@code oss-cn-hangzhou-internal.aliyuncs.com}).
     *
     * @return OSS endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    public AgentRunOssMountConfig setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    /**
     * Returns the in-sandbox mount directory.
     *
     * @return absolute mount path (must start with {@code /home}, {@code /mnt} or {@code /data})
     */
    public String getMountDir() {
        return mountDir;
    }

    public AgentRunOssMountConfig setMountDir(String mountDir) {
        this.mountDir = mountDir;
        return this;
    }

    /**
     * Returns whether the mount is read-only.
     *
     * @return true when read-only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    public AgentRunOssMountConfig setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }
}
