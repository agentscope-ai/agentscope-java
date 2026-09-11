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
package io.agentscope.harness.agent.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes file paths to workspace-relative form by stripping the active mode's workspace
 * prefix.
 *
 * <p>Only the prefix matching the current filesystem mode is registered, so there is no risk
 * of a sandbox prefix ({@code /workspace/}) accidentally matching a real host directory in
 * local mode, or vice versa.
 *
 * <p>Paths that don't match any registered prefix pass through unchanged, preserving the
 * ability to access non-workspace files in modes that allow it.
 */
public final class WorkspacePathNormalizer {

    private final List<String> prefixes;
    private final NamespaceFactory namespaceFactory;

    private WorkspacePathNormalizer(List<String> prefixes, NamespaceFactory namespaceFactory) {
        this.prefixes = List.copyOf(prefixes);
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * Creates a normalizer that strips the given prefix.
     *
     * @param workspacePrefix the workspace root path for the active mode (e.g.
     *     {@code "/workspace"} for sandbox, or the host workspace absolute path for local)
     */
    public static WorkspacePathNormalizer of(String workspacePrefix) {
        List<String> list = new ArrayList<>(1);
        String trimmed = trimTrailingSlash(workspacePrefix);
        if (trimmed != null && !trimmed.isEmpty()) {
            list.add(trimmed);
        }
        return new WorkspacePathNormalizer(list, null);
    }

    /**
     * Creates a normalizer that tries multiple prefixes in order. Use only when the active
     * mode has more than one valid prefix (e.g. local-with-shell where project dir and
     * workspace dir are both valid roots).
     */
    public static WorkspacePathNormalizer of(String... workspacePrefixes) {
        List<String> list = new ArrayList<>(workspacePrefixes.length);
        for (String p : workspacePrefixes) {
            String trimmed = trimTrailingSlash(p);
            if (trimmed != null && !trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return new WorkspacePathNormalizer(list, null);
    }

    /**
     * Creates a normalizer that also strips the runtime namespace beneath the workspace prefix.
     * This is needed when a prompt advertises a session-scoped absolute path while the filesystem
     * applies the same namespace to relative paths at operation time.
     *
     * @param workspacePrefix the unscoped workspace root
     * @param namespaceFactory factory for the current runtime namespace
     */
    public static WorkspacePathNormalizer of(
            String workspacePrefix, NamespaceFactory namespaceFactory) {
        List<String> list = new ArrayList<>(1);
        String trimmed = trimTrailingSlash(workspacePrefix);
        if (trimmed != null && !trimmed.isEmpty()) {
            list.add(trimmed);
        }
        return new WorkspacePathNormalizer(list, namespaceFactory);
    }

    /**
     * Normalize a path to workspace-relative form by stripping the active mode's prefix.
     *
     * @param path the raw path (absolute or relative)
     * @return workspace-relative path, or the original path if no registered prefix matched
     */
    public String normalize(String path) {
        return normalize(path, RuntimeContext.empty());
    }

    /**
     * Normalizes a path using the namespace for the supplied runtime context.
     *
     * <p>Namespaced workspace prefixes are checked before the unscoped prefix, so an absolute path
     * such as {@code /workspace/session-1/file.txt} becomes {@code file.txt} rather than
     * {@code session-1/file.txt} when the active namespace is {@code session-1}.
     *
     * @param path the raw path (absolute or relative)
     * @param runtimeContext current operation context
     * @return workspace-relative path, or the original path if no registered prefix matched
     */
    public String normalize(String path, RuntimeContext runtimeContext) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (namespaceFactory != null) {
            List<String> namespace =
                    namespaceFactory.getNamespace(
                            runtimeContext != null ? runtimeContext : RuntimeContext.empty());
            if (namespace != null && !namespace.isEmpty()) {
                String namespacePrefix = String.join("/", namespace);
                for (String prefix : prefixes) {
                    String stripped = tryStrip(path, prefix + "/" + namespacePrefix);
                    if (stripped != null) {
                        return stripped;
                    }
                }
            }
        }
        for (String prefix : prefixes) {
            String stripped = tryStrip(path, prefix);
            if (stripped != null) {
                return stripped;
            }
        }
        return path;
    }

    private static String tryStrip(String path, String prefix) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedPrefix = prefix.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedPrefix + "/")) {
            return normalizedPath.substring(normalizedPrefix.length() + 1);
        }
        if (normalizedPath.equals(normalizedPrefix)) {
            return ".";
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        if (s != null && s.length() > 1 && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
