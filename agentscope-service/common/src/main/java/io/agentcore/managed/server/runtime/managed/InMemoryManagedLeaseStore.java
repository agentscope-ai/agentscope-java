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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Test-only ephemeral ownership, including expiry and replacement-token checks. */
public final class InMemoryManagedLeaseStore implements ManagedLeaseStore {
    private final LongSupplier clock;
    private final Map<String, Held> leases = new HashMap<>();

    public InMemoryManagedLeaseStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Optional<Lease> acquire(String key, Duration ttl) {
        ManagedScope.requireText(key, "lease key");
        long duration = ManagedMessageBus.positiveMillis(ttl);
        var current = leases.get(key);
        if (current != null && current.until > clock.getAsLong()) return Optional.empty();
        var lease = new Lease(key, UUID.randomUUID().toString());
        leases.put(key, new Held(lease, Math.addExact(clock.getAsLong(), duration)));
        return Optional.of(lease);
    }

    @Override
    public synchronized boolean renew(Lease lease, Duration ttl) {
        long duration = ManagedMessageBus.positiveMillis(ttl);
        if (!owns(lease)) return false;
        leases.put(lease.key(), new Held(lease, Math.addExact(clock.getAsLong(), duration)));
        return true;
    }

    @Override
    public synchronized boolean owns(Lease lease) {
        var current = leases.get(lease.key());
        return current != null
                && current.until > clock.getAsLong()
                && current.lease.token().equals(lease.token());
    }

    @Override
    public synchronized boolean release(Lease lease) {
        if (!owns(lease)) return false;
        leases.remove(lease.key());
        return true;
    }

    private record Held(Lease lease, long until) {}
}
