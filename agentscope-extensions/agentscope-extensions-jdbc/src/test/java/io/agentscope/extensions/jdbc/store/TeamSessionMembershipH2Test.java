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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TeamSessionMembershipH2Test {
    @TempDir Path temp;
    private final TeamAddress a = new TeamAddress("ns-a", "a");
    private final TeamAddress b = new TeamAddress("ns-b", "b");

    @Test
    void historicalByoJsonSurvivesFileReopenAndOriginalTokenUnbindPreservesSession()
            throws Exception {
        // Literal schema=1 JSON from the shape used before the public Source field was removed.
        String historicalJson =
                """
                {
                  "meta": {
                    "objective": "historical objective", "phase": "Working", "leadRef": "leader-agent",
                    "sessionMembership": {
                      "schema": 1, "domain": "app", "owner": "alice", "teamId": "historical-team-id",
                      "displayName": "historical display", "registrationToken": "historical-registration",
                      "initial": [{
                        "memberName": "lead", "definitionOwner": "definition-owner", "agentRef": "leader-agent",
                        "role": "LEADER", "session": {
                          "stateStoreDomain": "sessions", "owner": "alice", "sessionId": "leader-session"
                        }
                      }]
                    }
                  },
                  "owner": {
                    "schema": 1, "receipts": {"historical-team-id": "historical-registration"},
                    "memberships": [{
                      "teamOwner": "alice", "teamId": "historical-team-id",
                      "address": {"namespace": "ns-a", "teamName": "a"},
                      "member": {
                        "memberName": "lead", "definitionOwner": "definition-owner", "agentRef": "leader-agent",
                        "role": "LEADER", "session": {
                          "stateStoreDomain": "sessions", "owner": "alice", "sessionId": "leader-session"
                        }
                      },
                      "source": "BYO", "token": "historical-leader-token"
                    }]
                  }
                }
                """;
        Map<String, Map<String, Object>> historical =
                new ObjectMapper().readValue(historicalJson, new TypeReference<>() {});
        Fixture fixture = open();
        try {
            create(fixture.client(), a);
            fixture.states().save("alice", "leader-session", "legacy", new SavedState("original"));
            fixture.store().put(List.of("teams", "ns-a", "a"), "meta", historical.get("meta"));
            fixture.store()
                    .put(
                            List.of("team-session-membership", "YXBw"),
                            "owner-YWxpY2U",
                            historical.get("owner"));
            shutdown(fixture.ds());

            fixture = open();
            SessionKey session = new SessionKey("sessions", "alice", "leader-session");
            Membership historicalBinding = fixture.membership().findMembership(session).block();
            assertEquals("historical-leader-token", historicalBinding.token());
            assertEquals("historical-team-id", historicalBinding.teamId());
            assertEquals(Status.ACTIVE, fixture.membership().getTeam("alice", a).block().status());
            assertEquals(1, fixture.membership().listMemberships("alice", a).block().size());
            assertEquals(
                    "leader-session",
                    fixture.client().listMembers("ns-a", "a").block().get(0).sessionId());
            assertTrue(
                    fixture.membership()
                            .unbindMember("alice", a, session, "historical-leader-token")
                            .block());
            shutdown(fixture.ds());

            fixture = open();
            assertNull(fixture.membership().findMembership(session).block());
            assertTrue(fixture.membership().listMemberships("alice", a).block().isEmpty());
            assertEquals("", fixture.client().listMembers("ns-a", "a").block().get(0).sessionId());
            assertEquals(
                    "historical-team-id",
                    fixture.membership().getTeam("alice", a).block().teamId());
            assertEquals(
                    new SavedState("original"),
                    fixture.states()
                            .get("alice", "leader-session", "legacy", SavedState.class)
                            .orElseThrow());
        } finally {
            shutdown(fixture.ds());
        }
    }

    @ParameterizedTest
    @MethodSource("ownerBoundaries")
    void longOwnersCanAdoptReopenQueryAndUnbindWithoutLosingSessionState(String owner)
            throws Exception {
        Fixture initial = open();
        try {
            create(initial.client(), a);
            MemberSession lead = member(initial, owner, "lead", "leader-session", "leader-agent");
            MemberSession worker =
                    member(initial, owner, "worker", "worker-session", "worker-agent");
            assertTrue(initial.states().exists(owner, lead.session().sessionId()));
            assertEquals(
                    Status.ACTIVE,
                    initial.membership()
                            .adoptTeam(owner, a, "display", List.of(lead))
                            .block()
                            .status());
            Membership binding = initial.membership().bindMember(owner, a, worker).block();
            shutdown(initial.ds());

            Fixture reopened = open();
            assertEquals(binding, reopened.membership().findMembership(worker.session()).block());
            assertEquals(2, reopened.membership().listMemberships(owner, a).block().size());
            assertEquals(
                    "worker-session",
                    reopened.client().listMembers("ns-a", "a").block().stream()
                            .filter(m -> m.memberName().equals("worker"))
                            .findFirst()
                            .orElseThrow()
                            .sessionId());
            assertTrue(
                    reopened.membership()
                            .unbindMember(owner, a, worker.session(), binding.token())
                            .block());
            assertNull(reopened.membership().findMembership(worker.session()).block());
            assertEquals(
                    new SavedState("original"),
                    reopened.states()
                            .get(owner, "worker-session", "legacy", SavedState.class)
                            .orElseThrow());
        } finally {
            shutdown(initial.ds());
        }
    }

    private static Stream<String> ownerBoundaries() {
        return Stream.of(
                "a".repeat(186),
                "a".repeat(187),
                "a".repeat(188),
                "中".repeat(62),
                "中".repeat(63),
                "中".repeat(64));
    }

    @Test
    void unstorableOwnerRecordFailsBeforeAdoptionAndLeavesLegacyQueriesAvailable()
            throws Exception {
        Fixture fixture = open();
        try {
            create(fixture.client(), a);
            String owner = "a".repeat(187);
            MemberSession lead = member(fixture, owner, "lead", "one", "leader-agent");
            String table = AbstractJdbcDialect.from(fixture.ds()).build().storeTableName();
            try (Connection connection = fixture.ds().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ALTER COLUMN item_key VARCHAR(32)");
            }
            for (int retry = 0; retry < 2; retry++) {
                assertThrows(
                        RuntimeException.class,
                        () ->
                                fixture.membership()
                                        .adoptTeam(owner, a, "display", List.of(lead))
                                        .block());
                assertFalse(
                        fixture.store()
                                .get(List.of("teams", "ns-a", "a"), "meta")
                                .value()
                                .containsKey("sessionMembership"));
                assertEquals(
                        "", fixture.client().listMembers("ns-a", "a").block().get(0).sessionId());
                assertTrue(fixture.states().exists(owner, "one"));
            }
            try (Connection connection = fixture.ds().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ALTER COLUMN item_key VARCHAR(255)");
            }
            assertEquals(
                    Status.ACTIVE,
                    fixture.membership()
                            .adoptTeam(owner, a, "display", List.of(lead))
                            .block()
                            .status());
        } finally {
            shutdown(fixture.ds());
        }
    }

    @Test
    void checkConstraintFailureIsNotRetriedAsContentionAndLeavesTeamUnadopted() throws Exception {
        Fixture fixture = open();
        try {
            create(fixture.client(), a);
            MemberSession lead = member(fixture, "lead", "one", "leader-agent");
            String table = AbstractJdbcDialect.from(fixture.ds()).build().storeTableName();
            try (Connection connection = fixture.ds().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE "
                                + table
                                + " ADD CONSTRAINT reject_relation CHECK (item_key NOT LIKE"
                                + " 'owner-%')");
            }
            AtomicInteger writes = new AtomicInteger();
            DelegatingStore counting =
                    new DelegatingStore(fixture.store()) {
                        @Override
                        public boolean putIfVersion(
                                List<String> ns,
                                String key,
                                Map<String, Object> value,
                                long version) {
                            if (ns.get(0).equals("team-session-membership")) {
                                writes.incrementAndGet();
                            }
                            return super.putIfVersion(ns, key, value, version);
                        }
                    };
            TeamSessionMembership membership =
                    new LocalTeamClient(counting)
                            .sessionMembership("app", Map.of("sessions", fixture.states()));
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    membership
                                            .adoptTeam("alice", a, "display", List.of(lead))
                                            .block());
            assertInstanceOf(SQLException.class, failure.getCause());
            assertEquals(1, writes.get());
            assertFalse(
                    fixture.store()
                            .get(List.of("teams", "ns-a", "a"), "meta")
                            .value()
                            .containsKey("sessionMembership"));
            assertEquals("", fixture.client().listMembers("ns-a", "a").block().get(0).sessionId());
            assertEquals(
                    new SavedState("original"),
                    fixture.states().get("alice", "one", "legacy", SavedState.class).orElseThrow());
            try (Connection connection = fixture.ds().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT reject_relation");
            }
            assertEquals(
                    Status.ACTIVE,
                    membership.adoptTeam("alice", a, "display", List.of(lead)).block().status());
        } finally {
            shutdown(fixture.ds());
        }
    }

    @Test
    void longRelationshipDomainFitsJdbcNamespaceAndSurvivesReopen() throws Exception {
        Fixture initial = open();
        String domain = "域".repeat(600);
        try {
            create(initial.client(), a);
            MemberSession lead = member(initial, "lead", "one", "leader-agent");
            TeamSessionMembership membership =
                    initial.client()
                            .sessionMembership(domain, Map.of("sessions", initial.states()));
            Membership binding =
                    membership
                            .adoptTeam("alice", a, "a", List.of(lead))
                            .block()
                            .memberships()
                            .get(0);
            shutdown(initial.ds());
            Fixture reopened = open();
            membership =
                    reopened.client()
                            .sessionMembership(domain, Map.of("sessions", reopened.states()));
            assertEquals(binding, membership.findMembership(lead.session()).block());
            assertEquals(
                    "one", reopened.client().listMembers("ns-a", "a").block().get(0).sessionId());
        } finally {
            shutdown(initial.ds());
        }
    }

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
                        if (ns.get(0).equals("team-session-membership")
                                && !((Map<?, ?>) value.get("receipts")).isEmpty()) {
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
        return member(fixture, "alice", name, sessionId, agentRef);
    }

    private static MemberSession member(
            Fixture fixture, String owner, String name, String sessionId, String agentRef) {
        fixture.states().save(owner, sessionId, "legacy", new SavedState("original"));
        return new MemberSession(
                name,
                "definition-owner",
                agentRef,
                name.equals("lead") ? Role.LEADER : Role.WORKER,
                new SessionKey("sessions", owner, sessionId));
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
