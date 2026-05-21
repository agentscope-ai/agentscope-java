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
package io.agentscope.builder.web.workspace;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Builds {@link WorkspaceManager} instances for a given (ownerId, agentId) pair, scoping every
 * filesystem operation to that agent's namespace within the shared workspace root.
 *
 * <p>Each call to {@link #forAgent(String, String)} creates a {@link LocalFilesystem} with a
 * {@link NamespaceFactory} that prefixes all paths with {@code [users, ownerId, agents, agentId]}.
 * This uses {@code LocalFilesystem}'s built-in namespace support ({@code applyNamespacePrefix} /
 * {@code stripNamespacePrefix}) to scope reads and writes to the correct tenant subtree.
 *
 * <p>Construction is cheap; callers may invoke {@link #forAgent(String, String)} per request
 * without caching. Each call creates a lightweight {@link LocalFilesystem} instance.
 */
public final class WorkspaceManagerFactory {

    private final Path workspaceRoot;
    private final int maxFileSizeMb;
    private final BaseStore remoteStore;

    public WorkspaceManagerFactory(Path workspaceRoot, int maxFileSizeMb) {
        this(workspaceRoot, maxFileSizeMb, null);
    }

    public WorkspaceManagerFactory(Path workspaceRoot, int maxFileSizeMb, BaseStore remoteStore) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.maxFileSizeMb = maxFileSizeMb;
        this.remoteStore = remoteStore;
    }

    /** Returns a {@link WorkspaceManager} scoped to {@code (ownerId, agentId)}. */
    public WorkspaceManager forAgent(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        NamespaceFactory ns = namespaceFor(ownerId, agentId);
        AbstractFilesystem scoped;
        if (remoteStore != null) {
            scoped = new RemoteFilesystem(remoteStore, ns);
        } else {
            scoped = new LocalFilesystem(workspaceRoot, true, maxFileSizeMb, ns);
        }
        return new WorkspaceManager(localWorkspacePath(ownerId, agentId), scoped);
    }

    /**
     * Returns a {@code NamespaceFactory} that emits the canonical
     * {@code [users, ownerId, agents, agentId]} tuple.
     */
    public NamespaceFactory namespaceFor(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        List<String> ns = List.of("users", ownerId, "agents", agentId);
        return () -> ns;
    }

    /**
     * Returns the on-disk path that corresponds to the given agent's namespace within the
     * workspace root.
     */
    public Path localWorkspacePath(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return workspaceRoot.resolve("users").resolve(ownerId).resolve("agents").resolve(agentId);
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must not contain path separators or '..': " + value);
        }
    }
}
