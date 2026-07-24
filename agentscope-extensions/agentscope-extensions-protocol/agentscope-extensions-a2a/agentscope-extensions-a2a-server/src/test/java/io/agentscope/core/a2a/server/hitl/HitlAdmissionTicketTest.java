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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HITL admission ticket ownership")
class HitlAdmissionTicketTest {

    @Test
    @DisplayName("Handler abort closes lease and marks a claimed handoff recovery-required once")
    void abortClaimedTicketExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        HitlAdmissionTicket ticket = claimedTicket(coordinator, closes);

        assertTrue(ticket.abort());
        assertFalse(ticket.abort());

        assertEquals(1, closes.get());
        assertEquals(HitlAdmissionTicket.State.ABORTED, ticket.state());
        verify(coordinator, times(1))
                .transition(
                        "handoff-1",
                        HitlHandoffStatus.CLAIMED,
                        HitlHandoffStatus.RECOVERY_REQUIRED);
    }

    @Test
    @DisplayName("Executor ownership prevents handler abort and closes the lease once")
    void executorTakeWinsOwnership() {
        AtomicInteger closes = new AtomicInteger();
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        HitlAdmissionTicket ticket = claimedTicket(coordinator, closes);
        RequestContext context = requestContext();

        ticket.take(context);
        assertFalse(ticket.abort());
        ticket.markRecoveryRequired();
        ticket.closeExecution();
        ticket.closeExecution();

        assertEquals(1, closes.get());
        assertEquals(HitlAdmissionTicket.State.CLOSED, ticket.state());
        verify(coordinator, times(1))
                .transition(
                        "handoff-1",
                        HitlHandoffStatus.CLAIMED,
                        HitlHandoffStatus.RECOVERY_REQUIRED);
    }

    @Test
    @DisplayName("Concurrent handler abort and executor take have exactly one owner")
    void handlerAbortAndExecutorTakeRace() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        HitlAdmissionTicket ticket = claimedTicket(coordinator, closes);
        RequestContext context = requestContext();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Boolean> abortWon = new AtomicReference<>(false);
        AtomicReference<Boolean> takeWon = new AtomicReference<>(false);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread abortThread =
                new Thread(
                        () -> {
                            await(start);
                            abortWon.set(ticket.abort());
                        });
        Thread takeThread =
                new Thread(
                        () -> {
                            await(start);
                            try {
                                ticket.take(context);
                                takeWon.set(true);
                            } catch (IllegalStateException expectedIfAbortWon) {
                                if (!abortWon.get()
                                        && ticket.state() != HitlAdmissionTicket.State.ABORTED) {
                                    unexpected.set(expectedIfAbortWon);
                                }
                            }
                        });
        abortThread.start();
        takeThread.start();
        start.countDown();
        abortThread.join(2_000);
        takeThread.join(2_000);

        if (takeWon.get()) {
            ticket.closeExecution();
        }
        assertFalse(abortThread.isAlive());
        assertFalse(takeThread.isAlive());
        assertNull(unexpected.get());
        assertTrue(abortWon.get() ^ takeWon.get());
        assertEquals(1, closes.get());
    }

    @Test
    @DisplayName("Ticket cannot be taken twice")
    void ticketCannotBeTakenTwice() {
        HitlAdmissionTicket ticket =
                claimedTicket(mock(HitlResumeCoordinator.class), new AtomicInteger());
        RequestContext context = requestContext();

        ticket.take(context);

        assertThrows(IllegalStateException.class, () -> ticket.take(context));
        ticket.closeExecution();
    }

    private HitlAdmissionTicket claimedTicket(
            HitlResumeCoordinator coordinator, AtomicInteger closes) {
        ResolvedAgentRequestMetadata request = mock(ResolvedAgentRequestMetadata.class);
        when(request.matches(org.mockito.ArgumentMatchers.any(RequestContext.class)))
                .thenReturn(true);
        HitlLeaseHandle lease = closes::incrementAndGet;
        return new HitlAdmissionTicket(
                request,
                HitlAdmissionTicket.Operation.RESUME,
                null,
                "handoff-1",
                null,
                lease,
                coordinator);
    }

    private RequestContext requestContext() {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getContextId()).thenReturn("context-1");
        return context;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
