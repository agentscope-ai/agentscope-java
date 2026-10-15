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

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.Objects;

/**
 * Mount of a pre-existing E2B volume into a sandbox.
 *
 * <p>Volumes live independently of sandbox lifecycles and are attached at sandbox creation time
 * via {@code volumeMounts: [{name, path}]} on {@code POST /sandboxes}. Volume lifecycle
 * operations (create/delete/list, content read/write) stay in the E2B dashboard/SDK; this class
 * only describes attach-at-create.
 *
 * <p>Both fields map 1:1 to E2B's {@code SandboxVolumeMount}: {@code name} is the existing
 * volume name, {@code path} is the absolute in-sandbox mount path. E2B volumes are read-write
 * and the platform defines no mount-path prefix whitelist, so validation only enforces
 * non-blank {@code name} and an absolute {@code path}.
 */
public class E2bVolumeMount {

    private String name;
    private String path;

    /** Default constructor. */
    public E2bVolumeMount() {}

    /**
     * Creates a volume mount.
     *
     * @param name existing E2B volume name
     * @param path absolute in-sandbox mount path
     */
    public E2bVolumeMount(String name, String path) {
        setName(name);
        setPath(path);
    }

    /**
     * Returns the existing E2B volume name.
     *
     * @return volume name
     */
    public String getName() {
        return name;
    }

    public E2bVolumeMount setName(String name) {
        if (name == null || name.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount name must be set (name of an existing volume)");
        }
        this.name = name.strip();
        return this;
    }

    /**
     * Returns the normalized absolute in-sandbox mount path.
     *
     * @return absolute mount path (duplicate slashes collapsed, no trailing slash except root)
     */
    public String getPath() {
        return path;
    }

    public E2bVolumeMount setPath(String path) {
        this.path = normalizeMountPath(path);
        return this;
    }

    /**
     * Normalizes an in-sandbox mount path: trims whitespace, collapses duplicate slashes and
     * strips a trailing slash (except for the root {@code /} itself).
     *
     * @param path raw mount path
     * @return normalized absolute path
     * @throws SandboxException.SandboxConfigurationException when the path is not absolute
     */
    public static String normalizeMountPath(String path) {
        if (path == null || path.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount path must be set (absolute in-sandbox path, e.g. /mnt/data)");
        }
        String normalized = path.strip().replace('\\', '/').replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B volume mount path must be absolute but was: " + path);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Returns whether {@code workspaceRoot} lives on the given mount, i.e. it equals the mount
     * path or sits strictly beneath it. The trailing-slash guard keeps {@code /mnt/data-x} from
     * matching a {@code /mnt/data} mount.
     *
     * @param workspaceRoot workspace root to test (may be blank)
     * @param mountPath normalized mount path (may be blank)
     * @return true when the workspace root is hosted on the mount
     */
    public static boolean coversWorkspaceRoot(String workspaceRoot, String mountPath) {
        if (workspaceRoot == null
                || workspaceRoot.isBlank()
                || mountPath == null
                || mountPath.isBlank()) {
            return false;
        }
        String root = normalizeMountPath(workspaceRoot);
        String mount = normalizeMountPath(mountPath);
        if (mount.equals("/")) {
            return true;
        }
        return root.equals(mount) || root.startsWith(mount + "/");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof E2bVolumeMount other)) {
            return false;
        }
        return Objects.equals(name, other.name) && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path);
    }

    @Override
    public String toString() {
        return "E2bVolumeMount{name='" + name + "', path='" + path + "'}";
    }
}
