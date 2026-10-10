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

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("team-wakeups")
class LocalTeamClientTest {

    @TempDir Path tempDir;

    @Test
    void sendMessage_isolatesSameNamedMembersByNamespace() {
        try (ScopedTeams teams = new ScopedTeams("namespace-messages", tempDir)) {
            teams.client
                    .sendMessage("namespace-a", teams.teamName, "lead", "worker", "message for A")
                    .block();

            teams.assertNotice("namespace-a", "worker", "message for A");
            assertEquals(
                    "message for A",
                    teams.client
                            .listMessages("namespace-a", teams.teamName, 50)
                            .block()
                            .get(0)
                            .content());
            assertTrue(
                    teams.client.listMessages("namespace-b", teams.teamName, 50).block().isEmpty());

            teams.client
                    .sendMessage("namespace-b", teams.teamName, "lead", "worker", "message for B")
                    .block();

            teams.assertNotice("namespace-b", "worker", "message for B");
            assertEquals(
                    1, teams.client.listMessages("namespace-a", teams.teamName, 50).block().size());
            assertEquals(
                    "message for B",
                    teams.client
                            .listMessages("namespace-b", teams.teamName, 50)
                            .block()
                            .get(0)
                            .content());
        }
    }

    @Test
    void createTaskWithOwner_isolatesAssignmentNoticeByNamespace() {
        try (ScopedTeams teams = new ScopedTeams("namespace-create-task", tempDir)) {
            TeamTask task =
                    teams.client
                            .createTask(
                                    "namespace-a",
                                    teams.teamName,
                                    "collect A docs",
                                    "",
                                    List.of(),
                                    "worker")
                            .block();

            teams.assertNotice("namespace-a", "worker", "collect A docs");
            assertEquals(TeamTask.PENDING, task.state());
            assertTrue(teams.client.listTasks("namespace-b", teams.teamName).block().isEmpty());
            assertTrue(
                    teams.client.listMessages("namespace-b", teams.teamName, 50).block().isEmpty());
        }
    }

    @Test
    void assignTask_isolatesAssignmentNoticeByNamespace() {
        try (ScopedTeams teams = new ScopedTeams("namespace-assign-task", tempDir)) {
            TeamTask task =
                    teams.client
                            .createTask(
                                    "namespace-a",
                                    teams.teamName,
                                    "assigned A work",
                                    "",
                                    List.of(),
                                    "")
                            .block();

            TeamTask assigned =
                    teams.client
                            .assignTask(
                                    "namespace-a",
                                    teams.teamName,
                                    task.taskId(),
                                    "worker",
                                    task.version())
                            .block();

            teams.assertNotice("namespace-a", "worker", "assigned A work");
            assertEquals("worker", assigned.owner());
            assertTrue(teams.client.listTasks("namespace-b", teams.teamName).block().isEmpty());
            assertTrue(
                    teams.client.listMessages("namespace-b", teams.teamName, 50).block().isEmpty());
        }
    }

    @Test
    void completeTask_isolatesResultNoticeByNamespace() {
        try (ScopedTeams teams = new ScopedTeams("namespace-complete-task", tempDir)) {
            TeamTask task = teams.claimTaskInA();

            TeamTask completed =
                    teams.client
                            .completeTask("namespace-a", teams.teamName, task.taskId(), "A result")
                            .block();

            teams.assertNotice("namespace-a", "lead", "A result");
            assertEquals(TeamTask.COMPLETED, completed.state());
            assertEquals("A result", completed.result());
            assertEquals(
                    TeamTask.PENDING,
                    teams.client.listTasks("namespace-b", teams.teamName).block().get(0).state());
            assertTrue(
                    teams.client.listMessages("namespace-b", teams.teamName, 50).block().isEmpty());
        }
    }

    @Test
    void failTask_isolatesFailureNoticeByNamespace() {
        try (ScopedTeams teams = new ScopedTeams("namespace-fail-task", tempDir)) {
            TeamTask task = teams.claimTaskInA();

            TeamTask failed =
                    teams.client
                            .failTask("namespace-a", teams.teamName, task.taskId(), "A failure")
                            .block();

            teams.assertNotice("namespace-a", "lead", "A failure");
            assertEquals(TeamTask.FAILED, failed.state());
            assertEquals("A failure", failed.result());
            assertEquals(
                    TeamTask.PENDING,
                    teams.client.listTasks("namespace-b", teams.teamName).block().get(0).state());
            assertTrue(
                    teams.client.listMessages("namespace-b", teams.teamName, 50).block().isEmpty());
        }
    }

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
    void claimWithExpectedVersionZero_usesCurrentVersion() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("t0", "ns", "obj", "lead", "", List.of())).block();
        TeamTask created = client.createTask("ns", "t0", "work", "", List.of(), "w1").block();
        assertEquals(1L, created.version());

        TeamTask claimed = client.claimTask("ns", "t0", created.taskId(), "w1", 0L).block();
        assertEquals(TeamTask.IN_PROGRESS, claimed.state());
        assertEquals("w1", claimed.owner());

        // Idempotent second claim by same owner.
        TeamTask again = client.claimTask("ns", "t0", created.taskId(), "w1", 0L).block();
        assertEquals(TeamTask.IN_PROGRESS, again.state());
    }

    @Test
    void listClaimableTasks_includesAssignedToMember() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("tc", "ns", "obj", "lead", "", List.of())).block();
        TeamTask assigned = client.createTask("ns", "tc", "for-w1", "", List.of(), "w1").block();
        TeamTask open = client.createTask("ns", "tc", "open", "", List.of(), "").block();

        assertEquals(1, client.listClaimableTasks("ns", "tc").block().size());
        List<TeamTask> forW1 = client.listClaimableTasks("ns", "tc", "w1").block();
        assertEquals(2, forW1.size());
        assertTrue(forW1.stream().anyMatch(t -> t.taskId().equals(assigned.taskId())));
        assertTrue(forW1.stream().anyMatch(t -> t.taskId().equals(open.taskId())));
    }

    @Test
    void completeTask_notifiesLeadWithResult() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("done", "ns", "obj", "lead", "", List.of())).block();
        TeamTask task = client.createTask("ns", "done", "ship it", "", List.of(), "w1").block();
        client.claimTask("ns", "done", task.taskId(), "w1", 0L).block();

        client.completeTask("ns", "done", task.taskId(), "shipped in commit abc123").block();

        List<TeamMessage> inbox = leadInbox(client, "done");
        assertEquals(1, inbox.size());
        assertEquals("w1", inbox.get(0).from());
        assertTrue(inbox.get(0).content().contains("completed"));
        assertTrue(inbox.get(0).content().contains("shipped in commit abc123"));
    }

    @Test
    void failTask_marksFailedNotifiesLeadAndIsTerminal() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("bad", "ns", "obj", "lead", "", List.of())).block();
        TeamTask task = client.createTask("ns", "bad", "risky", "", List.of(), "w1").block();
        client.claimTask("ns", "bad", task.taskId(), "w1", 0L).block();

        TeamTask failed = client.failTask("ns", "bad", task.taskId(), "sandbox exploded").block();
        assertEquals(TeamTask.FAILED, failed.state());
        assertEquals("sandbox exploded", failed.result());
        assertTrue(TeamTask.isTerminal(failed.state()));

        List<TeamMessage> inbox = leadInbox(client, "bad");
        assertEquals(1, inbox.size());
        assertTrue(inbox.get(0).content().contains("sandbox exploded"));

        assertThrows(
                TeamConflictException.class,
                () -> client.failTask("ns", "bad", task.taskId(), "again").block());
        assertTrue(client.listClaimableTasks("ns", "bad", "w1").block().isEmpty());
    }

    private static List<TeamMessage> leadInbox(LocalTeamClient client, String team) {
        return inboxOf(client, team, "lead");
    }

    private static List<TeamMessage> inboxOf(LocalTeamClient client, String team, String member) {
        return client.listMessages("ns", team, 50).block().stream()
                .filter(m -> member.equals(m.to()))
                .toList();
    }

    @Test
    void assignTask_notifiesOwnerWithTaskAndDescription() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("nudge", "ns", "obj", "lead", "", List.of())).block();
        TeamTask open =
                client.createTask("ns", "nudge", "collect docs", "read the guide", List.of(), "")
                        .block();
        assertTrue(inboxOf(client, "nudge", "w1").isEmpty(), "unowned task notifies nobody");

        client.assignTask("ns", "nudge", open.taskId(), "w1", open.version()).block();

        List<TeamMessage> inbox = inboxOf(client, "nudge", "w1");
        assertEquals(1, inbox.size());
        assertEquals("lead", inbox.get(0).from());
        assertTrue(inbox.get(0).content().contains(open.taskId()));
        assertTrue(inbox.get(0).content().contains("read the guide"));
        assertTrue(inbox.get(0).content().contains("claimTask"));
    }

    @Test
    void selfAddressedMessage_isRejected() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("echo", "ns", "obj", "lead", "", List.of())).block();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.sendMessage("ns", "echo", "lead", "lead", "here are the docs")
                                .block());
        assertTrue(inboxOf(client, "echo", "lead").isEmpty());
    }

    @Test
    void createTaskWithOwner_notifiesOwnerImmediately() {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec("direct", "ns", "obj", "lead", "", List.of())).block();

        client.createTask("ns", "direct", "ship it", "", List.of(), "w1").block();

        assertEquals(1, inboxOf(client, "direct", "w1").size());
        assertTrue(inboxOf(client, "direct", "lead").isEmpty(), "lead must not notify itself");
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

    private static final class ScopedTeams implements AutoCloseable {

        private final LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        private final WorkspaceMessageBus bus;
        private final String teamName;
        private final List<String> sessions = new ArrayList<>();

        private ScopedTeams(String teamName, Path tempDir) {
            this.teamName = teamName;
            this.bus = new WorkspaceMessageBus(new LocalFilesystem(tempDir, true, 10), "/bus");
            for (String namespace : List.of("namespace-a", "namespace-b")) {
                client.createTeam(
                                new TeamCreateSpec(
                                        teamName,
                                        namespace,
                                        "test namespace isolation",
                                        "lead-agent",
                                        "",
                                        List.of(
                                                new TeamMemberSpec(
                                                        "worker", "worker-agent", "", "byo"))))
                        .block();
                for (String member : List.of("lead", "worker")) {
                    TeamsMiddleware middleware =
                            new TeamsMiddleware(
                                    client,
                                    new TeamContext(
                                            teamName,
                                            namespace,
                                            "test namespace isolation",
                                            member,
                                            "lead".equals(member),
                                            List.of(),
                                            List.of()));
                    middleware.wireMessageBus(bus, agentId(namespace, member));
                    String sessionId = sessionId(namespace, member);
                    middleware.bindSession(sessionId);
                    sessions.add(sessionId);
                }
            }
        }

        private TeamTask claimTaskInA() {
            TeamTask task = null;
            for (String namespace : List.of("namespace-a", "namespace-b")) {
                TeamTask created =
                        client.createTask(namespace, teamName, "scoped work", "", List.of(), "")
                                .block();
                if ("namespace-a".equals(namespace)) {
                    task = created;
                }
            }
            return client.claimTask(
                            "namespace-a", teamName, task.taskId(), "worker", task.version())
                    .block();
        }

        private void assertNotice(String namespace, String member, String content) {
            String targetSession = sessionId(namespace, member);
            List<BusEntry> inbox = bus.inboxDrain(targetSession, 50).block();
            assertEquals(1, inbox.size(), "notification must reach " + targetSession);
            Map<String, Object> payload = inbox.get(0).payload();
            assertEquals("hint", payload.get("type"));
            assertEquals("team_event", payload.get("source"));
            assertTrue(payload.get("hint").toString().contains(content));
            for (String otherSession : sessions) {
                if (!targetSession.equals(otherSession)) {
                    assertTrue(
                            bus.inboxDrain(otherSession, 50).block().isEmpty(),
                            "notification must not reach " + otherSession);
                }
            }
            List<BusEntry> wakeups = bus.queueDrain("agentscope:wakeups", 50).block();
            assertEquals(1, wakeups.size());
            assertEquals(targetSession, wakeups.get(0).payload().get("sessionId"));
            assertEquals(agentId(namespace, member), wakeups.get(0).payload().get("agentId"));
        }

        private String sessionId(String namespace, String member) {
            return teamName + ":" + namespace + ":" + member;
        }

        private static String agentId(String namespace, String member) {
            return namespace + "-" + member;
        }

        @Override
        public void close() {
            for (String sessionId : sessions) {
                TeamsMiddleware.unregisterSession(sessionId);
            }
        }
    }
}
