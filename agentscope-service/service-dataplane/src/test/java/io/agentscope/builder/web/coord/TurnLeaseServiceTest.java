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
package io.agentscope.builder.web.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TurnLeaseServiceTest {

    @Test
    void eachTurnUsesAFencedOwnerEvenOnTheSameJvm() {
        CoordinationStore store = mock(CoordinationStore.class);
        AtomicReference<CoordinationStore.LeaseHandle> held = new AtomicReference<>();
        when(store.tryAcquireTurnLease(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String sessionId = invocation.getArgument(0);
                            String ownerId = invocation.getArgument(1);
                            String leaseOwnerId = invocation.getArgument(2);
                            long now = System.currentTimeMillis();
                            CoordinationStore.LeaseHandle candidate =
                                    new CoordinationStore.LeaseHandle(
                                            sessionId,
                                            ownerId,
                                            leaseOwnerId,
                                            now,
                                            now + Duration.ofSeconds(30).toMillis());
                            return held.compareAndSet(null, candidate)
                                    ? Optional.of(candidate)
                                    : Optional.empty();
                        });
        when(store.getTurnLease(anyString()))
                .thenAnswer(ignored -> Optional.ofNullable(held.get()));
        doAnswer(
                        invocation -> {
                            String leaseOwnerId = invocation.getArgument(1);
                            CoordinationStore.LeaseHandle current = held.get();
                            return current != null
                                    && current.instanceId().equals(leaseOwnerId)
                                    && held.compareAndSet(current, null);
                        })
                .when(store)
                .releaseTurnLease(anyString(), anyString());

        TurnLeaseService service =
                new TurnLeaseService(store, new BuilderInstanceId("brain-a", "ignored"), 30);
        TurnLeaseService.TurnLease first =
                service.acquireOrConflict("session-a", "owner-a", () -> {});
        String firstOwner = held.get().instanceId();

        assertThat(firstOwner).startsWith("brain-a/");
        assertThatThrownBy(() -> service.acquireOrConflict("session-a", "owner-a", () -> {}))
                .isInstanceOf(ResponseStatusException.class);

        first.close();
        TurnLeaseService.TurnLease second =
                service.acquireOrConflict("session-a", "owner-a", () -> {});
        assertThat(held.get().instanceId()).startsWith("brain-a/").isNotEqualTo(firstOwner);
        second.close();
    }

    @Test
    void heartbeatErrorsAreToleratedOnlyUntilTheLocalLeaseDeadline() throws Exception {
        CoordinationStore store = mock(CoordinationStore.class);
        when(store.tryAcquireTurnLease(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            long acquiredAt = System.currentTimeMillis();
                            return Optional.of(
                                    new CoordinationStore.LeaseHandle(
                                            "session-a",
                                            "owner-a",
                                            invocation.getArgument(2),
                                            acquiredAt,
                                            acquiredAt + 150L));
                        });
        when(store.heartbeatTurnLease(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("coordination database unavailable"));
        CountDownLatch leaseLost = new CountDownLatch(1);
        TurnLeaseService service =
                new TurnLeaseService(
                        store, new BuilderInstanceId("brain-a", "ignored"), Duration.ofMillis(150));

        TurnLeaseService.TurnLease lease =
                service.acquireOrConflictFenced(
                        "session-a",
                        "owner-a",
                        request -> {
                            if ("turn_lease_lost".equals(request.reason())) {
                                leaseLost.countDown();
                            }
                        });

        assertThat(leaseLost.await(70, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(leaseLost.await(500, TimeUnit.MILLISECONDS)).isTrue();
        lease.close();
    }
}
