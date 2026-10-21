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
package io.agentscope.builder.runtime.managed;

import io.agentscope.builder.runtime.managed.ManagedJournalStore.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Brain advances its native prefix only after the complete boundary commits in PostgreSQL. */
public final class ManagedRuntimeWriter {
    private static final Logger log = LoggerFactory.getLogger(ManagedRuntimeWriter.class);
    private final ManagedJournalStore journal;

    public ManagedRuntimeWriter(ManagedJournalStore journal) {
        this.journal = journal;
    }

    public Receipt commit(ManagedRuntimeBatch batch, ManagedExecutionGuard guard) {
        guard.check();
        var sent = journal.publishTools(batch);
        guard.check();
        var receipt = journal.commit(batch, sent);
        if (!"committed".equals(receipt.outcome()))
            throw new ManagedStateException(receipt.errorCode(), "Runtime boundary was rejected");
        ManagedRuntimeLog.checkpoints(log, "brain.checkpoint.committed", batch);
        return receipt;
    }
}
