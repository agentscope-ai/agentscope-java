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

import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Membership;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalTeamClientTest {

    private static final List<String> COMPLETION_META = List.of("teams", "ns", "membership");

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

    @Test
    void completionWithoutMembershipOptInRetainsVersionedStoreFallback() {
        CompletionFaultStore store = new CompletionFaultStore();
        LocalTeamClient client = new LocalTeamClient(store);
        createCompletionTeam(client);
        store.rejectCompletion = true;

        client.completeTeam("ns", "membership").block();

        assertEquals("Completed", store.get(COMPLETION_META, "meta").value().get("phase"));
        assertEquals(1, store.casAttempts);
        assertEquals(1, store.unconditionalWrites);
    }

    @Test
    void rejectedMembershipConfigurationDoesNotChangeLegacyCompletion() {
        CompletionFaultStore store = new CompletionFaultStore();
        LocalTeamClient client = new LocalTeamClient(store);
        createCompletionTeam(client);
        assertThrows(IllegalArgumentException.class, () -> client.sessionMembership("", Map.of()));
        store.rejectCompletion = true;

        client.completeTeam("ns", "membership").block();

        assertEquals("Completed", store.get(COMPLETION_META, "meta").value().get("phase"));
        assertEquals(1, store.casAttempts);
        assertEquals(1, store.unconditionalWrites);
    }

    @Test
    void successfulMembershipOptInProtectsUnadoptedMetadataOnContention() {
        CompletionFaultStore store = new CompletionFaultStore();
        LocalTeamClient client = new LocalTeamClient(store);
        createCompletionTeam(client);
        client.sessionMembership("app", Map.of());
        StoreItem before = store.get(COMPLETION_META, "meta");
        store.rejectCompletion = true;

        assertThrows(
                TeamConflictException.class, () -> client.completeTeam("ns", "membership").block());

        assertEquals(before, store.get(COMPLETION_META, "meta"));
        assertEquals(10, store.casAttempts);
        assertEquals(0, store.unconditionalWrites);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enabledOrReopenedClientPreservesAdoptedMetadataAndTokenOnContention(boolean reopen) {
        CompletionFaultStore store = new CompletionFaultStore();
        LocalTeamClient original = new LocalTeamClient(store);
        InMemoryAgentStateStore states = new InMemoryAgentStateStore();
        TeamSessionMembership membership = adoptCompletionTeam(original, states);
        SessionKey session = new SessionKey("state", "alice", "leader-session");
        Membership beforeMembership = membership.findMembership(session).block();
        StoreItem beforeMeta = store.get(COMPLETION_META, "meta");
        LocalTeamClient completing = reopen ? new LocalTeamClient(store) : original;
        store.rejectCompletion = true;

        assertThrows(
                TeamConflictException.class,
                () -> completing.completeTeam("ns", "membership").block());

        assertEquals(beforeMeta, store.get(COMPLETION_META, "meta"));
        assertEquals(beforeMembership, membership.findMembership(session).block());
        assertEquals(10, store.casAttempts);
        assertEquals(0, store.unconditionalWrites);
    }

    @Test
    void adoptedMetadataCannotFallBackThroughNonversionedProvider() {
        InMemoryStore store = new InMemoryStore();
        adoptCompletionTeam(new LocalTeamClient(store), new InMemoryAgentStateStore());
        StoreItem before = store.get(COMPLETION_META, "meta");
        AtomicInteger writes = new AtomicInteger();
        BaseStore legacy =
                new BaseStore() {
                    @Override
                    public StoreItem get(List<String> namespace, String key) {
                        return store.get(namespace, key);
                    }

                    @Override
                    public void put(List<String> namespace, String key, Map<String, Object> value) {
                        writes.incrementAndGet();
                        store.put(namespace, key, value);
                    }

                    @Override
                    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
                        return store.search(namespace, limit, offset);
                    }

                    @Override
                    public void delete(List<String> namespace, String key) {
                        store.delete(namespace, key);
                    }
                };

        assertThrows(
                IllegalStateException.class,
                () -> new LocalTeamClient(legacy).completeTeam("ns", "membership").block());

        assertEquals(before, store.get(COMPLETION_META, "meta"));
        assertEquals(0, writes.get());
    }

    private static void createCompletionTeam(LocalTeamClient client) {
        client.createTeam(
                        new TeamCreateSpec("membership", "ns", "objective", "lead", "", List.of()))
                .block();
    }

    private static TeamSessionMembership adoptCompletionTeam(
            LocalTeamClient client, InMemoryAgentStateStore states) {
        createCompletionTeam(client);
        states.save("alice", "leader-session", "agent", new SavedState("original"));
        TeamSessionMembership membership = client.sessionMembership("app", Map.of("state", states));
        membership
                .adoptTeam(
                        "alice",
                        new TeamAddress("ns", "membership"),
                        "membership",
                        List.of(
                                new MemberSession(
                                        "lead",
                                        "alice",
                                        "lead",
                                        Role.LEADER,
                                        new SessionKey("state", "alice", "leader-session"))))
                .block();
        return membership;
    }

    private static class CompletionFaultStore extends InMemoryStore {
        boolean rejectCompletion;
        int casAttempts;
        int unconditionalWrites;

        @Override
        public boolean putIfVersion(
                List<String> namespace,
                String key,
                Map<String, Object> value,
                long expectedVersion) {
            if (rejectCompletion && namespace.equals(COMPLETION_META) && key.equals("meta")) {
                casAttempts++;
                return false;
            }
            return super.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            if (rejectCompletion && namespace.equals(COMPLETION_META) && key.equals("meta")) {
                unconditionalWrites++;
            }
            super.put(namespace, key, value);
        }
    }

    public record SavedState(String value) implements State {}
}
