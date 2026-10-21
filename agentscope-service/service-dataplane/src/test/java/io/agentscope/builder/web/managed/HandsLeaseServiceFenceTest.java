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
package io.agentscope.builder.web.managed;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.EnvironmentWorkQueue.Status;
import io.agentscope.builder.web.managed.EnvironmentWorkQueue.WorkItem;
import io.agentscope.builder.web.managed.service.HandsMetrics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandsLeaseServiceFenceTest {

    @Test
    void delayedOldTurnReleaseCannotStopReplacementWorkItem() {
        EnvironmentWorkQueue queue = mock(EnvironmentWorkQueue.class);
        when(queue.enqueue("session-a", "environment-a", "owner-a"))
                .thenReturn(work("lease-a"), work("lease-b"));
        HandsLeaseService service =
                new HandsLeaseService(
                        queue, mock(ExternalSandboxRegistry.class), new HandsMetrics());
        ManagedSessionDto session = session();
        EnvironmentDto environment =
                new EnvironmentDto(
                        "environment-a",
                        "self hosted",
                        EnvironmentTypes.TYPE_SELF_HOSTED,
                        Map.of(),
                        "owner-a",
                        null,
                        1,
                        1,
                        null);

        service.acquire(session, environment, "turn-a");
        service.release("session-a", "turn-a");
        service.acquire(session, environment, "turn-b");
        service.release("session-a", "turn-a");

        verify(queue).stop("lease-a");
        verify(queue, never()).stop("lease-b");

        service.release("session-a", "turn-b");
        verify(queue).stop("lease-b");
    }

    private static WorkItem work(String leaseId) {
        return new WorkItem(
                leaseId,
                "session-a",
                "environment-a",
                "owner-a",
                1,
                1,
                Status.queued,
                null,
                null,
                Map.of());
    }

    private static ManagedSessionDto session() {
        return new ManagedSessionDto(
                "session-a",
                "owner-a",
                "agent-a",
                "owner-a",
                1,
                "managed",
                null,
                "environment-a",
                null,
                List.of(),
                List.of(),
                List.of(),
                "running",
                null,
                1,
                1,
                null);
    }
}
