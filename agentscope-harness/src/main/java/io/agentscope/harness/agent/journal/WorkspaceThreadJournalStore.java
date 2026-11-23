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
package io.agentscope.harness.agent.journal;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.AtomicThreadJournal;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.journal.ThreadJournalStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Default log storage uses the workspace backend, including its distributed namespace routing. */
public final class WorkspaceThreadJournalStore implements ThreadJournalStore {

    private static final String HEAD_FILE_SUFFIX = "/head.json";
    private static final String SESSION_KEY_PREFIX = "s_";

    /** Layered shape of a discoverable thread journal path. */
    private static final int THREAD_KEY_SEGMENTS = 8;

    private static final int SESSIONS_INDEX = 2;
    private static final int THREADS_INDEX = 5;

    /** Segments that carry a base64url key and are therefore decoded into the {@link ThreadKey}. */
    private static final int[] ENCODED_SEGMENTS = {1, 3, 4, 6};

    private final AbstractFilesystem filesystem;

    public WorkspaceThreadJournalStore(AbstractFilesystem filesystem) {
        this.filesystem = Objects.requireNonNull(filesystem);
    }

    public WorkspaceThreadJournalStore(WorkspaceManager workspace) {
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
    public List<ThreadKey> list(RuntimeContext context) {
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        return filesystem.journalStorage(rc).listPaths("agents/").stream()
                .filter(path -> path.endsWith(HEAD_FILE_SUFFIX))
                .map(path -> path.split("/"))
                .filter(WorkspaceThreadJournalStore::isThreadKeyPath)
                .map(
                        parts ->
                                new ThreadKey(
                                        decodeUser(parts[3]),
                                        decode(parts[1]),
                                        decode(parts[4]),
                                        decode(parts[6])))
                .filter(key -> Objects.equals(key.userId(), normalizeUser(rc.getUserId())))
                .distinct()
                .sorted(
                        Comparator.comparing(ThreadKey::agentId)
                                .thenComparing(ThreadKey::sessionId)
                                .thenComparing(ThreadKey::threadId))
                .toList();
    }

    /**
     * Whether a split path addresses a thread journal, i.e.
     * {@code agents/<agent>/sessions/<user>/<session>/threads/<thread>/head.json}.
     *
     * <p>Every segment is validated before any segment is indexed or decoded. A storage backend
     * may also list {@code head.json} files that belong to other layouts, partially written
     * trees, or foreign tenants; discovery must skip those instead of failing the whole listing
     * with an index or decoding error.
     *
     * @param parts the path split on {@code /}
     * @return true when the path is a thread journal of this namespace layout
     */
    private static boolean isThreadKeyPath(String[] parts) {
        if (parts.length != THREAD_KEY_SEGMENTS
                || !"sessions".equals(parts[SESSIONS_INDEX])
                || !"threads".equals(parts[THREADS_INDEX])) {
            return false;
        }
        for (int index : ENCODED_SEGMENTS) {
            if (!parts[index].startsWith(SESSION_KEY_PREFIX)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private static String decodeUser(String segment) {
        return normalizeUser(decode(segment));
    }

    private static String decode(String segment) {
        if (!segment.startsWith(SESSION_KEY_PREFIX))
            throw new IllegalArgumentException("Invalid session key");
        return new String(
                Base64.getUrlDecoder().decode(segment.substring(2)), StandardCharsets.UTF_8);
    }

    @Override
    public ThreadJournal open(ThreadKey key, RuntimeContext context) {
        return new AtomicThreadJournal(
                filesystem.journalStorage(context == null ? RuntimeContext.empty() : context),
                key.storagePath());
    }
}
