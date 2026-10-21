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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedExecutionGuardTest {
    private final AtomicLong clock = new AtomicLong();
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    private final ManagedMessageBus bus = mock(ManagedMessageBus.class);
    private final InMemoryManagedLeaseStore leases = new InMemoryManagedLeaseStore(clock::get);
    private final AtomicInteger losses = new AtomicInteger();
    private final Duration ttl = Duration.ofSeconds(60);

    @BeforeEach
    void activeCallbacks() {
        when(bus.active(any())).thenReturn(true);
    }

    @Test
    void directContinuationIsFencedByTheSameSessionLeaseWithoutAMqCallback() {
        var guard =
                ManagedExecutionGuard.acquireContinuation(
                                scope,
                                leases,
                                ttl,
                                () -> clock.get() * 1_000_000,
                                losses::incrementAndGet)
                        .orElseThrow();
        assertTrue(
                ManagedExecutionGuard.acquireContinuation(
                                scope,
                                leases,
                                ttl,
                                () -> clock.get() * 1_000_000,
                                losses::incrementAndGet)
                        .isEmpty());
        clock.set(60_000);
        var replacement =
                ManagedExecutionGuard.acquireContinuation(
                                scope,
                                leases,
                                ttl,
                                () -> clock.get() * 1_000_000,
                                losses::incrementAndGet)
                        .orElseThrow();
        assertThrows(ManagedTransportException.class, guard::check);
        guard.close();
        replacement.check();
        replacement.close();
        verifyNoInteractions(bus);
    }

    @Test
    void redisRenewalExtendsInferenceOwnershipWithoutAnyMqRenewal() {
        var guard = guard(delivery(), leases);
        clock.set(45_000);
        guard.renew();
        clock.set(70_000);
        guard.check();
        assertEquals(Duration.ofSeconds(35), guard.remaining());
        assertEquals(0, losses.get());
        verify(bus, atLeastOnce()).active(any());
        verifyNoMoreInteractions(bus);
    }

    @Test
    void expiredRedisLeaseStopsExecutionEvenWhileSdkCallbackRemainsActive() {
        var guard = guard(delivery(), leases);
        clock.set(60_000);
        assertThrows(ManagedTransportException.class, guard::check);
        assertThrows(ManagedTransportException.class, guard::renew);
        assertEquals(1, losses.get());
        assertEquals(Duration.ZERO, guard.remaining());
    }

    @Test
    void failedRedisRenewalStopsExecutionImmediately() {
        var delegate = spy(leases);
        var guard = guard(delivery(), delegate);
        doReturn(false).when(delegate).renew(any(), any());
        clock.set(15_000);
        assertThrows(ManagedTransportException.class, guard::renew);
        assertThrows(ManagedTransportException.class, guard::check);
        assertEquals(1, losses.get());
    }

    @Test
    void closingOldGuardDoesNotReleaseReplacementOrCompleteEitherCallback() {
        var original = delivery();
        var first = guard(original, leases);
        clock.set(60_000);
        var replacement = guard(delivery(), leases);
        assertNotEquals(first.token(), replacement.token());
        first.close();
        replacement.check();
        replacement.close();
        assertTrue(bus.active(original));
        assertTrue(leases.acquire(scope.key("execution"), ttl).isPresent());
    }

    @Test
    void inactiveCallbackCannotAcquireAndWrongScopeIsRejected() {
        var delivery = delivery();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ManagedExecutionGuard.acquire(
                                new ManagedScope("other", "namespace", "session"),
                                bus,
                                leases,
                                delivery,
                                ttl,
                                () -> clock.get() * 1_000_000,
                                losses::incrementAndGet));
        when(bus.active(delivery)).thenReturn(false);
        assertThrows(ManagedTransportException.class, () -> guard(delivery, leases));
        assertTrue(leases.acquire(scope.key("execution"), ttl).isPresent());
    }

    @Test
    void callbackLossFencesGuardBeforeRedisTtl() {
        var delivery = delivery();
        var guard = guard(delivery, leases);
        when(bus.active(delivery)).thenReturn(false);
        assertThrows(ManagedTransportException.class, guard::check);
        assertEquals(1, losses.get());
    }

    @Test
    void runtimeCommitChecksOwnershipBeforeTouchingTheJournal() {
        var delivery = delivery();
        var delegate = spy(leases);
        var guard = guard(delivery, delegate);
        verify(delegate, times(1)).owns(any());

        doReturn(false).when(delegate).owns(any());
        var journal = mock(ManagedJournalStore.class);
        var codec = mock(ManagedBatchCodec.class);
        var writer = new ManagedRuntimeWriter(journal);
        var batch = mock(ManagedRuntimeBatch.class);
        assertThrows(ManagedTransportException.class, () -> writer.commit(batch, guard));
        verify(delegate, times(2)).owns(any());
        verify(bus, atLeastOnce()).active(any());
        verifyNoMoreInteractions(bus);
        verifyNoInteractions(journal, codec, batch);
        assertEquals(1, losses.get());
    }

    @Test
    void guardRejectsExpiredClosedAndInactiveExecutions() {
        var expired = guard(delivery(), leases);
        clock.set(60_000);
        assertThrows(ManagedTransportException.class, expired::check);

        var closed = guard(delivery(), leases);
        closed.close();
        assertThrows(ManagedTransportException.class, closed::check);

        var delivery = delivery();
        var inactive = guard(delivery, leases);
        when(bus.active(delivery)).thenReturn(false);
        assertThrows(ManagedTransportException.class, inactive::check);
    }

    private ManagedExecutionGuard guard(ManagedPushDelivery delivery, ManagedLeaseStore store) {
        return ManagedExecutionGuard.acquire(
                        scope,
                        bus,
                        store,
                        delivery,
                        ttl,
                        () -> clock.get() * 1_000_000,
                        losses::incrementAndGet)
                .orElseThrow();
    }

    private ManagedPushDelivery delivery() {
        return new ManagedPushDelivery(
                UUID.randomUUID().toString(),
                "consumer",
                Channel.TURNS,
                "turns",
                "brain",
                "broker",
                1,
                new Message("turn", scope.messageGroup(), new byte[] {1}));
    }
}
