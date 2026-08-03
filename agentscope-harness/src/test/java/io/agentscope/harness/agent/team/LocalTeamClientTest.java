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
package io.agentscope.harness.agent.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalTeamClientTest {

    @Test
    void assignThenOwnerClaim_selfClaimRejectedForOthers() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(
                        new TeamCreateSpec(
                                "t1",
                                "ns",
                                "obj",
                                "lead-agent",
                                "",
                                List.of(new TeamMemberSpec("alice", "a", "", "byo"))))
                .block();

        TeamTask created = client.createTask("ns", "t1", "work", "", List.of(), "").block();
        TeamTask assigned =
                client.assignTask("ns", "t1", created.taskId(), "alice", created.version()).block();
        assertEquals("alice", assigned.owner());
        assertEquals(TeamTask.PENDING, assigned.state());

        assertThrows(
                TeamConflictException.class,
                () ->
                        client.claimTask("ns", "t1", assigned.taskId(), "bob", assigned.version())
                                .block());

        TeamTask started =
                client.claimTask("ns", "t1", assigned.taskId(), "alice", assigned.version())
                        .block();
        assertEquals(TeamTask.IN_PROGRESS, started.state());
    }

    @Test
    void concurrentSelfClaim_onlyOneWins() throws Exception {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("race", "ns", "obj", "lead", "", List.of())).block();
        TeamTask task = client.createTask("ns", "race", "one", "", List.of(), "").block();

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Thread t1 =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                client.claimTask("ns", "race", task.taskId(), "a", task.version())
                                        .block();
                                wins.incrementAndGet();
                            } catch (TeamConflictException e) {
                                conflicts.incrementAndGet();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        Thread t2 =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                client.claimTask("ns", "race", task.taskId(), "b", task.version())
                                        .block();
                                wins.incrementAndGet();
                            } catch (TeamConflictException e) {
                                conflicts.incrementAndGet();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        assertEquals(1, wins.get());
        assertEquals(1, conflicts.get());
        assertTrue(client.listClaimableTasks("ns", "race").block().isEmpty());
    }
}
