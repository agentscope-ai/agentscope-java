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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.harness.agentscope.AgentScopeEventSchemas;

/** Explicit AgentScope composition. The platform accepts only the resulting neutral plan. */
public final class AgentScopePreparation {
    private AgentScopePreparation() {}

    public static ManagedCommitPlan prepare(
            ManagedJournalStore journal, ObjectMapper mapper, ManagedRuntimeBatch batch) {
        AgentScopeEventSchemas.ensureRegistered();
        return AgentScopeCommitAdapter.prepare(batch, hash(mapper, batch), mapper, journal);
    }

    public static String hash(ObjectMapper mapper, ManagedRuntimeBatch batch) {
        try {
            return ManagedScope.fingerprint(
                    mapper.copy()
                            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(batch));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode runtime boundary", error);
        }
    }

    /** Embedded integration path; production submits the prepared plan over Session Service HTTP. */
    public static SessionCommitReceipt commit(
            ManagedJournalStore journal, PreparedCommitter commits, ManagedRuntimeBatch batch) {
        for (int attempt = 0; ; attempt++) {
            try {
                var mapper = new ObjectMapper();
                String hash = hash(mapper, batch);
                var prior = journal.receipt(batch.scope(), batch.batchId());
                if (prior != null) {
                    if (!prior.hash().equals(hash))
                        throw new ManagedStateException(
                                "batch_content_conflict",
                                "Commit identity was reused with different content");
                    return prior;
                }
                return commits.commitPrepared(prepare(journal, mapper, batch));
            } catch (ManagedStateException changed) {
                if (!"projection_preparation_changed".equals(changed.code())) throw changed;
                if (attempt >= 2)
                    throw new ManagedTransportException(
                            "Concurrent fact preparation; retry delivery", changed);
            }
        }
    }
}
