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

import io.agentscope.builder.runtime.managed.ManagedLeaseStore.Lease;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Active inference ownership only. SDK callback lifetime and message renewal are independent. */
public final class ManagedExecutionGuard implements AutoCloseable {
    private final ManagedScope scope;
    private final ManagedMessageBus bus;
    private final ManagedLeaseStore leases;
    private final ManagedPushDelivery delivery;
    private final Lease lease;
    private final Duration ttl;
    private final LongSupplier nanoClock;
    private final Runnable onLost;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private volatile long deadline;

    private ManagedExecutionGuard(
            ManagedScope scope,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ManagedPushDelivery delivery,
            Lease lease,
            Duration ttl,
            LongSupplier nanoClock,
            Runnable onLost,
            long acquiredAt) {
        this.scope = scope;
        this.bus = bus;
        this.leases = leases;
        this.delivery = delivery;
        this.lease = lease;
        this.ttl = ttl;
        this.nanoClock = nanoClock;
        this.onLost = Objects.requireNonNull(onLost, "loss callback");
        this.deadline = acquiredAt + ttl.toNanos();
    }

    public static Optional<ManagedExecutionGuard> acquire(
            ManagedScope scope,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ManagedPushDelivery delivery,
            Duration ttl,
            LongSupplier nanoClock,
            Runnable onLost) {
        ManagedMessageBus.positiveMillis(ttl);
        if ((delivery.channel() != Channel.TURNS && delivery.channel() != Channel.TURN_RESUMES)
                || !matchesScope(scope, delivery))
            throw new IllegalArgumentException("Delivery does not belong to this Session");
        if (!bus.active(delivery))
            throw new ManagedTransportException("SDK callback is no longer active");
        long before = nanoClock.getAsLong();
        var lease = leases.acquire(scope.key("execution"), ttl);
        if (lease.isEmpty()) return Optional.empty();
        var guard =
                new ManagedExecutionGuard(
                        scope, bus, leases, delivery, lease.get(), ttl, nanoClock, onLost, before);
        try {
            guard.check();
            return Optional.of(guard);
        } catch (RuntimeException failure) {
            try {
                guard.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** A committed completion is its own delivery intent; Redis and journal epochs fence recovery. */
    public static Optional<ManagedExecutionGuard> acquireContinuation(
            ManagedScope scope,
            ManagedLeaseStore leases,
            Duration ttl,
            LongSupplier clock,
            Runnable onLost) {
        ManagedMessageBus.positiveMillis(ttl);
        long before = clock.getAsLong();
        var lease = leases.acquire(scope.key("execution"), ttl);
        if (lease.isEmpty()) return Optional.empty();
        var guard =
                new ManagedExecutionGuard(
                        scope, null, leases, null, lease.get(), ttl, clock, onLost, before);
        try {
            guard.check();
            return Optional.of(guard);
        } catch (RuntimeException failure) {
            guard.close();
            throw failure;
        }
    }

    private static boolean matchesScope(ManagedScope scope, ManagedPushDelivery delivery) {
        return scope.messageGroup().equals(delivery.message().messageGroup());
    }

    public String token() {
        return lease.token();
    }

    public void invalidate(String message, Throwable cause) {
        lose(message, cause);
    }

    public void check() {
        checkDeadline();
        try {
            if (!leases.owns(lease)) throw lose("Managed execution token was lost", null);
        } catch (RuntimeException unavailable) {
            throw lose("Cannot verify Managed execution ownership", unavailable);
        }
    }

    /** Only Redis execution ownership is renewed. No SDK receive, renew or ACK call exists here. */
    public synchronized void renew() {
        checkDeadline();
        long before = nanoClock.getAsLong();
        try {
            if (!leases.renew(lease, ttl))
                throw new ManagedTransportException("Redis execution renewal was rejected");
            deadline = before + ttl.toNanos();
            checkDeadline();
        } catch (RuntimeException unavailable) {
            throw lose("Managed execution renewal failed", unavailable);
        }
    }

    public Duration remaining() {
        return valid.get()
                ? Duration.ofNanos(Math.max(0, deadline - nanoClock.getAsLong()))
                : Duration.ZERO;
    }

    @Override
    public void close() {
        valid.set(false);
        leases.release(lease);
    }

    private void checkDeadline() {
        if (!valid.get()
                || deadline - nanoClock.getAsLong() <= 0
                || (delivery != null && !bus.active(delivery)))
            throw lose("Managed execution expired or its callback stopped", null);
    }

    private ManagedTransportException lose(String message, Throwable cause) {
        var error = new ManagedTransportException(message, cause);
        if (valid.compareAndSet(true, false)) {
            try {
                onLost.run();
            } catch (RuntimeException callbackFailure) {
                error.addSuppressed(callbackFailure);
            }
        }
        return error;
    }
}
