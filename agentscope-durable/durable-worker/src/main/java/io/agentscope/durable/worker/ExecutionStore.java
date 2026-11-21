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

package io.agentscope.durable.worker;

import io.agentscope.durable.DurableTypes.ExecutionReconciliation;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Independent Worker ledger. Admission is acknowledged before provider work begins. */
public interface ExecutionStore {
    record Work(
            String key,
            ExecutionRequest request,
            String owner,
            long epoch,
            boolean attempted,
            boolean cancellationRequested,
            ExecutionResult result,
            long outputOffset,
            boolean outputSealed) {}

    ExecutionResult admit(ExecutionRequest request);

    ExecutionResult query(ExecutionRequest request);

    ExecutionResult cancel(ExecutionRequest request);

    List<String> keys(String after, int limit);

    List<String> ready(int limit);

    /** Separate discovery prevents blocked first dispatches from starving query/cancel work. */
    List<String> ready(boolean recovery, int limit);

    /** Immutable output already captured by the Worker; never reopens a Provider connection. */
    OutputPage output(ExecutionRequest request, long offset);

    record Statistics(
            int scanned,
            boolean truncated,
            int pending,
            int unknown,
            int pendingPublications,
            long oldestPendingAgeMillis,
            long oldestPublicationBacklogAgeMillis) {}

    /** Bounded operational inspection. Counts are lower bounds when truncated is true. */
    Statistics statistics(int limit);

    Optional<Work> claim(String key, String owner, Duration lease);

    /** Claim an exact admitted identity without exposing a provider's physical storage keys. */
    Optional<Work> claim(ExecutionRequest request, String owner, Duration lease);

    void renew(Work work, Duration lease);

    Work begin(Work work);

    void configureOutput(Work work, boolean enabled);

    void appendOutput(Work work, OutputPage page);

    void update(Work work, ExecutionResult result);

    /**
     * Host-only resolution of UNKNOWN under a claimed lease. The host must authorize the operator
     * and independently verify the terminal fact. This does not cancel or repeat a provider effect.
     */
    ExecutionResult reconcile(Work work, ExecutionReconciliation reconciliation);

    /** Durable receipt lookup after an uncertain reconciliation response. */
    Optional<ExecutionReconciliation> reconciliation(ExecutionRequest request, String requestId);

    void release(Work work, Duration delay);

    /** Persist capped exponential backoff; a restart does not reset the failure count. */
    void defer(Work work, Duration minimum, Duration maximum);
}
