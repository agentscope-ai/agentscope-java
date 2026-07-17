/* Copyright 2024-2026 the original author or authors. */
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
