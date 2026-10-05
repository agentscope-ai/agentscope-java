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
package io.agentscope.extensions.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.remote.store.VersionedBaseStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamSessionMembership;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Membership;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.Status;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamSessionMembershipH2Test {
    @TempDir Path temp;
    private final TeamAddress a = new TeamAddress("ns-a", "a");
    private final TeamAddress b = new TeamAddress("ns-b", "b");

    @Test
    void fileDatabaseReopenPreservesMembershipTokensMetadataAndExistingSessionState()
            throws Exception {
        Fixture initial = open();
        create(initial.client(), a);
        MemberSession lead = member(initial, "lead", "leader-session", "leader-agent");
        MemberSession worker = member(initial, "worker", "worker-session", "worker-agent");
        String id =
                initial.membership()
                        .adoptTeam("alice", a, "display", List.of(lead))
                        .block()
                        .teamId();
        Membership binding = initial.membership().bindMember("alice", a, worker).block();
        shutdown(initial.ds());

        Fixture reopened = open();
        assertEquals(id, reopened.membership().getTeam("alice", a).block().teamId());
        assertEquals(binding, reopened.membership().findMembership(worker.session()).block());
        assertEquals(2, reopened.membership().listMemberships("alice", a).block().size());
        assertEquals(
                "worker-session",
                reopened.client().listMembers("ns-a", "a").block().stream()
                        .filter(m -> m.memberName().equals("worker"))
                        .findFirst()
                        .orElseThrow()
                        .sessionId());
        reopened.client().completeTeam("ns-a", "a").block();
        assertTrue(
                reopened.membership()
                        .unbindMember("alice", a, worker.session(), binding.token())
                        .block());
        Membership rebound = reopened.membership().bindMember("alice", a, worker).block();
        assertNotEquals(binding.token(), rebound.token());
        assertThrows(
                TeamConflictException.class,
                () ->
                        reopened.membership()
                                .unbindMember("alice", a, worker.session(), binding.token())
                                .block());
        reopened.membership().unbindMember("alice", a, worker.session(), rebound.token()).block();
        shutdown(reopened.ds());

        Fixture finalRead = open();
        assertNull(finalRead.membership().findMembership(worker.session()).block());
        assertEquals(
                "Completed", finalRead.membership().getTeam("alice", a).block().info().phase());
        assertEquals(
                1,
                finalRead
                        .membership()
                        .adoptTeam("alice", a, "display", List.of(lead))
                        .block()
                        .memberships()
                        .size());
        assertEquals(
                new SavedState("original"),
                finalRead
                        .states()
                        .get("alice", "worker-session", "legacy", SavedState.class)
                        .orElseThrow());
        shutdown(finalRead.ds());
    }

    @Test
    void independentJdbcStoresAtomicallyCompeteAndIdenticalRequestsRetainOneToken()
            throws Exception {
        Fixture first = open(), second = open();
        create(first.client(), a);
        create(first.client(), b);
        first.membership()
                .adoptTeam(
                        "alice",
                        a,
                        "same display",
                        List.of(member(first, "lead", "one", "leader-agent")))
                .block();
        first.membership()
                .adoptTeam(
                        "alice",
                        b,
                        "same display",
                        List.of(member(first, "lead", "two", "leader-agent")))
                .block();
        MemberSession worker = member(first, "worker", "worker", "worker-agent");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger writes = new AtomicInteger();
        TeamSessionMembership left = racing(first, barrier, writes);
        TeamSessionMembership right = racing(second, barrier, writes);
        CompletableFuture<Object> x = attempt(() -> left.bindMember("alice", a, worker).block());
        CompletableFuture<Object> y = attempt(() -> right.bindMember("alice", b, worker).block());
        List<Object> outcomes = List.of(x.get(10, TimeUnit.SECONDS), y.get(10, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter(Membership.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(TeamConflictException.class::isInstance).count());
        Membership winner = second.membership().findMembership(worker.session()).block();
        assertEquals(winner, first.membership().findMembership(worker.session()).block());
        first.membership()
                .unbindMember("alice", winner.address(), worker.session(), winner.token())
                .block();
        writes.set(0);
        x = attempt(() -> left.bindMember("alice", a, worker).block());
        y = attempt(() -> right.bindMember("alice", a, worker).block());
        Object lx = x.get(10, TimeUnit.SECONDS), ry = y.get(10, TimeUnit.SECONDS);
        assertTrue(lx instanceof Membership);
        assertEquals(lx, ry);
        assertEquals(lx, second.membership().findMembership(worker.session()).block());
        shutdown(first.ds());
    }

    @Test
    void pendingAdoptionSurvivesDatabaseCloseAndRetryCommitsWithoutDeletingState()
            throws Exception {
        Fixture first = open();
        create(first.client(), a);
        MemberSession lead = member(first, "lead", "one", "leader-agent");
        DelegatingStore failBetweenWrites =
                new DelegatingStore(first.store()) {
                    @Override
                    public boolean putIfVersion(
                            List<String> ns, String key, Map<String, Object> value, long version) {
                        if (ns.get(0).equals("team-session-membership")) {
                            throw new IllegalStateException("Injected relation write failure");
                        }
                        return super.putIfVersion(ns, key, value, version);
                    }
                };
        TeamSessionMembership failed =
                new LocalTeamClient(failBetweenWrites)
                        .sessionMembership("app", Map.of("sessions", first.states()));
        assertThrows(
                IllegalStateException.class,
                () -> failed.adoptTeam("alice", a, "a", List.of(lead)).block());
        shutdown(first.ds());
        Fixture reopened = open();
        assertEquals(Status.PENDING, reopened.membership().getTeam("alice", a).block().status());
        assertNull(reopened.membership().findMembership(lead.session()).block());
        assertThrows(
                IllegalStateException.class,
                () -> reopened.client().listMembers("ns-a", "a").block());
        assertEquals(
                Status.ACTIVE,
                reopened.membership().adoptTeam("alice", a, "a", List.of(lead)).block().status());
        Membership bound = reopened.membership().findMembership(lead.session()).block();
        assertTrue(
                reopened.membership()
                        .unbindMember("alice", a, lead.session(), bound.token())
                        .block());
        assertTrue(
                reopened.membership()
                        .adoptTeam("alice", a, "a", List.of(lead))
                        .block()
                        .memberships()
                        .isEmpty());
        assertFalse(
                reopened.membership()
                        .unbindMember("alice", a, lead.session(), bound.token())
                        .block());
        assertTrue(reopened.states().exists("alice", "one"));
        shutdown(reopened.ds());
    }

    private Fixture open() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + temp.resolve("teams").toAbsolutePath() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        JdbcStore store = JdbcStore.builder(ds).dialect(dialect).build();
        JdbcAgentStateStore states = new JdbcAgentStateStore(ds, dialect);
        LocalTeamClient client = new LocalTeamClient(store);
        return new Fixture(
                ds,
                store,
                states,
                client,
                client.sessionMembership("app", Map.of("sessions", states)));
    }

    private static void shutdown(JdbcDataSource ds) throws Exception {
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private static void create(LocalTeamClient client, TeamAddress address) {
        client.createTeam(
                        new TeamCreateSpec(
                                address.teamName(),
                                address.namespace(),
                                "objective",
                                "leader-agent",
                                "",
                                List.of(new TeamMemberSpec("worker", "worker-agent", "", "byo"))))
                .block();
    }

    private static MemberSession member(
            Fixture fixture, String name, String sessionId, String agentRef) {
        fixture.states().save("alice", sessionId, "legacy", new SavedState("original"));
        return new MemberSession(
                name,
                "definition-owner",
                agentRef,
                name.equals("lead") ? Role.LEADER : Role.WORKER,
                new SessionKey("sessions", "alice", sessionId));
    }

    private static TeamSessionMembership racing(
            Fixture fixture, CyclicBarrier barrier, AtomicInteger writes) {
        DelegatingStore controlled =
                new DelegatingStore(fixture.store()) {
                    @Override
                    public boolean putIfVersion(
                            List<String> ns, String key, Map<String, Object> value, long version) {
                        if (ns.get(0).equals("team-session-membership")
                                && writes.incrementAndGet() <= 2) {
                            try {
                                barrier.await(5, TimeUnit.SECONDS);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        }
                        return super.putIfVersion(ns, key, value, version);
                    }
                };
        return new LocalTeamClient(controlled)
                .sessionMembership("app", Map.of("sessions", fixture.states()));
    }

    private static CompletableFuture<Object> attempt(Callable<Object> action) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return action.call();
                    } catch (Exception error) {
                        return error;
                    }
                });
    }

    private record Fixture(
            JdbcDataSource ds,
            JdbcStore store,
            JdbcAgentStateStore states,
            LocalTeamClient client,
            TeamSessionMembership membership) {}

    private static class DelegatingStore implements VersionedBaseStore {
        private final JdbcStore delegate;

        DelegatingStore(JdbcStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public StoreItem get(List<String> ns, String key) {
            return delegate.get(ns, key);
        }

        @Override
        public void put(List<String> ns, String key, Map<String, Object> value) {
            delegate.put(ns, key, value);
        }

        @Override
        public List<StoreItem> search(List<String> ns, int limit, int offset) {
            return delegate.search(ns, limit, offset);
        }

        @Override
        public void delete(List<String> ns, String key) {
            delegate.delete(ns, key);
        }

        @Override
        public boolean putIfVersion(
                List<String> ns, String key, Map<String, Object> value, long version) {
            return delegate.putIfVersion(ns, key, value, version);
        }
    }

    /** Preexisting legacy Session state, kept intact by membership operations. */
    public record SavedState(String value) implements State {}
}
