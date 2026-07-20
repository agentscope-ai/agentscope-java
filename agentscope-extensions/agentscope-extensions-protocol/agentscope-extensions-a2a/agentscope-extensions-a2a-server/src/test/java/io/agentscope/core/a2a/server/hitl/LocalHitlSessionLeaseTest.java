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

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalHitlSessionLeaseTest {

    @Test
    void serializesTheSameLogicalSessionAndReleasesOnClose() {
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlExecutionKey identity = new HitlExecutionKey("alice", "agent", "ctx");

        HitlLeaseHandle first = lease.acquire(identity, Duration.ofSeconds(30));

        assertThrows(
                HitlResumeRejectedException.class,
                () -> lease.acquire(identity, Duration.ofSeconds(30)));
        first.close();
        assertDoesNotThrow(() -> lease.acquire(identity, Duration.ofSeconds(30)).close());
    }

    @Test
    void doesNotShareLeaseAcrossDifferentUserOrContext() {
        LocalHitlSessionLease lease = new LocalHitlSessionLease();
        HitlLeaseHandle first =
                lease.acquire(
                        new HitlExecutionKey("alice", "agent", "ctx"), Duration.ofSeconds(30));

        assertDoesNotThrow(
                () ->
                        lease.acquire(
                                        new HitlExecutionKey("bob", "agent", "ctx"),
                                        Duration.ofSeconds(30))
                                .close());
        assertDoesNotThrow(
                () ->
                        lease.acquire(
                                        new HitlExecutionKey("alice", "agent", "ctx-2"),
                                        Duration.ofSeconds(30))
                                .close());
        first.close();
    }
}
