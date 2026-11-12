/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import java.util.Objects;

/** Embedded application service. Object preparation and SQL atomicity are owned by the journal. */
public final class ManagedSessionStore implements SessionStore {
    private final ManagedJournalStore journal;
    private final PreparedCommitter commits;

    public ManagedSessionStore(ManagedJournalStore journal, PreparedCommitter commits) {
        this.journal = Objects.requireNonNull(journal);
        this.commits = Objects.requireNonNull(commits);
    }

    @Override
    public SessionCommitReceipt commit(SessionCommit boundary) {
        return AgentScopePreparation.commit(
                journal, commits, ManagedSessionCommitAdapter.decode(boundary));
    }

    @Override
    public SessionCommitReceipt receipt(ManagedScope scope, String id) {
        return journal.receipt(scope, id);
    }

    @Override
    public SessionRecoveryHead head(ManagedScope scope) {
        return journal.head(scope);
    }
}
