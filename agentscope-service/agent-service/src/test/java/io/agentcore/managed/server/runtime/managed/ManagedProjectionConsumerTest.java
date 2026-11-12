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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagedProjectionConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("t", "n", "projection");

    private ManagedProjectionNotice notice() {
        var entry =
                new ManagedProjectionFacts.Entry(
                        1,
                        "one",
                        "session.created",
                        ManagedScope.fingerprint("session.created", "{}"),
                        1,
                        "inline:one",
                        0);
        var manifest =
                new ManagedProjectionFacts.Manifest(
                        1, 1, 1, Map.of(), List.of(entry), Map.of("one", "{}"));
        return new ManagedProjectionNotice(
                3,
                scope,
                List.of(
                        new ManagedProjectionNotice.Commit(
                                "one", ManagedProjectionFacts.identity(manifest), manifest)));
    }

    @Test
    void receiptCannotBeAckedBeforeTargetVisibilityAndCapacityIsBounded() throws Exception {
        var projections = mock(ManagedSessionProjections.class);
        var bus = mock(ManagedMessageBus.class);
        var callback = new AtomicReference<ManagedMessageBus.Listener>();
        when(bus.subscribe(any(), anyInt(), any(), any()))
                .thenAnswer(
                        call -> {
                            callback.set(call.getArgument(2));
                            return mock(ManagedMessageBus.Subscription.class);
                        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(projections)
                .deliver(any());
        var pool = Executors.newSingleThreadExecutor();
        try (var consumer = new ManagedProjectionConsumer(projections, bus, mapper, 1)) {
            consumer.start();
            var capacity =
                    callback.get().receiveCapacity(ManagedMessageBus.Channel.SESSION_PROJECTIONS);
            var gate = capacity.reserve(1).get(0);
            assertTrue(capacity.reserve(1).isEmpty());
            var delivery =
                    new ManagedPushDelivery(
                            "receipt",
                            "client",
                            ManagedMessageBus.Channel.SESSION_PROJECTIONS,
                            "topic",
                            "group",
                            "broker",
                            1,
                            new ManagedMessageBus.Message(
                                    "one",
                                    scope.messageGroup(),
                                    mapper.writeValueAsBytes(notice())),
                            gate);
            var result =
                    pool.submit(
                            () -> {
                                callback.get().consume(delivery);
                                return null;
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertThrows(ManagedDeliverySuspended.class, () -> consumer.offer(notice()));
            release.countDown();
            result.get(5, TimeUnit.SECONDS);
            gate.close();
            assertEquals(1, capacity.availableExecutions());
            verify(projections, times(1)).deliver(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failureIsReturnedToMqAndRedeliveryHasNoInMemoryRecoveryDependency() {
        var projections = mock(ManagedSessionProjections.class);
        doThrow(new IllegalStateException("offline")).doNothing().when(projections).deliver(any());
        var bus = mock(ManagedMessageBus.class);
        try (var first = new ManagedProjectionConsumer(projections, bus, mapper, 1)) {
            assertThrows(IllegalStateException.class, () -> first.offer(notice()));
            assertEquals(0, first.pendingCount());
        }
        try (var restarted = new ManagedProjectionConsumer(projections, bus, mapper, 1)) {
            restarted.offer(notice());
            assertEquals(0, restarted.pendingCount());
        }
        verify(projections, times(2)).deliver(any());
        verifyNoInteractions(bus);
    }

    @Test
    void protocolRejectsConflictingIdentityAndRoundTrips() throws Exception {
        var notice = notice();
        assertEquals(
                notice,
                mapper.readValue(mapper.writeValueAsBytes(notice), ManagedProjectionNotice.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedProjectionNotice(2, scope, notice.commits()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ManagedProjectionNotice.Commit(
                                "one", "invalid", notice.commits().get(0).manifest()));
    }
}
