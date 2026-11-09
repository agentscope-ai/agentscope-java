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
package io.agentscope.harness.agent.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Default log storage uses the workspace backend, including its distributed namespace routing. */
public final class WorkspaceSessionLogStore implements SessionLogStore {
    private final AbstractFilesystem filesystem;

    public WorkspaceSessionLogStore(AbstractFilesystem filesystem) {
        this.filesystem = Objects.requireNonNull(filesystem);
    }

    public WorkspaceSessionLogStore(WorkspaceManager workspace) {
        this(
                workspace.getFilesystem() != null
                        ? workspace.getFilesystem()
                        : new LocalFilesystem(workspace.getWorkspace()));
    }

    /**
     * Discover in the namespace selected by the filesystem and the explicit caller identity.
     * SESSION isolation (including USER fallback without a user ID) only exposes the current
     * namespace. Use a shared user-scoped log backend for cross-session discovery. A baked
     * filesystem still requires the matching user ID here because logical keys are user-scoped.
     */
    @Override
    public List<SessionKey> list(RuntimeContext context) {
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        return filesystem.sessionStorage(rc).listPaths("agents/").stream()
                .filter(path -> path.endsWith("/session.json"))
                .map(path -> path.split("/"))
                .filter(
                        parts ->
                                (parts.length == 6 || parts.length == 7 && parts[5].equals("inbox"))
                                        && parts[2].equals("sessions"))
                .map(
                        parts ->
                                new SessionKey(
                                        decodeUser(parts[3]), decode(parts[1]), decode(parts[4])))
                .filter(key -> Objects.equals(key.userId(), normalizeUser(rc.getUserId())))
                .distinct()
                .sorted(
                        Comparator.comparing(SessionKey::agentId)
                                .thenComparing(SessionKey::sessionId))
                .toList();
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private static String decodeUser(String segment) {
        return normalizeUser(decode(segment));
    }

    private static String decode(String segment) {
        if (!segment.startsWith("s_")) throw new IllegalArgumentException("Invalid session key");
        return new String(
                Base64.getUrlDecoder().decode(segment.substring(2)), StandardCharsets.UTF_8);
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        return new JournalSessionLog(
                filesystem.sessionStorage(context == null ? RuntimeContext.empty() : context),
                key.storagePath());
    }
}
