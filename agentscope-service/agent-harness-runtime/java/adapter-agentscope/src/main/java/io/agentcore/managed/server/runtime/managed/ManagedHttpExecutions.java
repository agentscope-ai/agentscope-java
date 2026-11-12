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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.ExecutionAccess.Claim;
import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local handles for the single Service-owned execution lease. */
public final class ManagedHttpExecutions {
    private final SessionExecutionAccess access;
    private final ConcurrentHashMap<ManagedScope, Active> active = new ConcurrentHashMap<>();

    public ManagedHttpExecutions(SessionExecutionAccess access) {
        this.access = access;
    }

    public ObjectNode request(ManagedScope scope) {
        var handle = active.get(scope);
        if (handle == null || !handle.owns(handle.lease))
            throw new ManagedStateException("stale_execution", "No active HTTP execution");
        return access.request(handle.claim);
    }

    public ManagedExecutionGuard attach(Claim claim, Runnable lost) {
        var scope =
                new ManagedScope(
                        claim.scope().tenant(),
                        claim.scope().namespace(),
                        claim.scope().sessionId());
        var handle = new Active(scope, claim);
        handle.renew(handle.lease, Duration.ofSeconds(30));
        if (active.putIfAbsent(scope, handle) != null)
            throw new IllegalStateException("Execution already active in this process");
        return ManagedExecutionGuard.attached(
                scope, handle, handle.lease, Duration.ofSeconds(30), lost);
    }

    private final class Active implements ManagedLeaseStore {
        private final ManagedScope scope;
        private final Claim claim;
        private final Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile long deadline;

        Active(ManagedScope scope, Claim claim) {
            this.scope = scope;
            this.claim = claim;
            this.lease = new Lease(scope.key("execution"), claim.fence().executionToken());
        }

        public Optional<Lease> acquire(String key, Duration ttl) {
            throw new UnsupportedOperationException(
                    "Execution is already claimed by Session Service");
        }

        public boolean renew(Lease value, Duration ttl) {
            if (closed.get() || !lease.equals(value)) return false;
            long before = System.nanoTime();
            var renewal = access.renew(claim);
            if (renewal.remainingMillis() < ttl.toMillis())
                throw new ManagedTransportException("Insufficient remaining execution lease");
            deadline = before + Duration.ofMillis(renewal.remainingMillis()).toNanos();
            return true;
        }

        public boolean owns(Lease value) {
            return !closed.get() && lease.equals(value) && deadline - System.nanoTime() > 0;
        }

        public boolean release(Lease value) {
            if (!lease.equals(value) || !closed.compareAndSet(false, true)) return false;
            active.remove(scope, this);
            access.release(claim);
            return true;
        }
    }
}
