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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/** Brain adapter: preserve native durable-head semantics through the direct Session Store port. */
public final class ManagedRuntimeWriter {
    private final Function<ManagedRuntimeBatch, SessionCommitReceipt> commit;

    public ManagedRuntimeWriter(ManagedJournalStore journal, PreparedCommitter commits) {
        this(new ManagedSessionStore(journal, commits));
    }

    public ManagedRuntimeWriter(SessionStore store) {
        this.commit = batch -> store.commit(ManagedSessionCommitAdapter.encode(batch));
    }

    /** The production Brain prepares native facts; only Session Service commits metadata. */
    public ManagedRuntimeWriter(
            ManagedJournalStore journal, SessionServiceClient client, ObjectMapper mapper) {
        this.commit =
                batch -> {
                    var request =
                            Map.of(
                                    "protocolVersion",
                                    "0.1.0",
                                    "plan",
                                    AgentScopePreparation.prepare(journal, mapper, batch));
                    String hash = CanonicalJson.hash(mapper.valueToTree(request));
                    SessionCommitReceipt receipt;
                    try {
                        receipt =
                                mapper.convertValue(
                                        client.post("prepared/commits", request),
                                        SessionCommitReceipt.class);
                    } catch (SessionServiceClient.Unavailable uncertain) {
                        // A lost response is not proof of rollback. Query the original ID before
                        // retry.
                        try {
                            receipt =
                                    mapper.convertValue(
                                            client.post(
                                                    "prepared/receipt",
                                                    Map.of(
                                                            "scope",
                                                            batch.scope(),
                                                            "commitId",
                                                            batch.batchId())),
                                            SessionCommitReceipt.class);
                        } catch (SessionServiceClient.Rejected missing) {
                            if (missing.status() != 404) throw missing;
                            receipt =
                                    mapper.convertValue(
                                            client.post("prepared/commits", request),
                                            SessionCommitReceipt.class);
                        }
                    }
                    if (!hash.equals(receipt.hash()) || !batch.batchId().equals(receipt.batchId()))
                        throw new ManagedStateException(
                                "commit_receipt_conflict", "Committed request differs");
                    return receipt;
                };
    }

    public SessionCommitReceipt commit(ManagedRuntimeBatch batch, ManagedExecutionGuard guard) {
        guard.check();
        var receipt = commit.apply(batch);
        guard.check();
        if (!"committed".equals(receipt.outcome()))
            throw new ManagedStateException(receipt.errorCode(), "Boundary was not committed");
        AgentScopeRuntimeLog.checkpoints(
                LoggerFactory.getLogger(ManagedRuntimeWriter.class),
                "harness_runtime.checkpoint.committed",
                batch,
                "batch_id",
                batch.batchId());
        return receipt;
    }
}
