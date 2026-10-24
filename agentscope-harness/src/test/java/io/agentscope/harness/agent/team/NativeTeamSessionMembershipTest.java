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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Membership;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.Status;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class NativeTeamSessionMembershipTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final List<String> RELATION_NS =
            List.of("team-session-membership", "cmVsYXRpb25z");
    @TempDir Path workspace;
    private final InMemorySessionLogStore logs = new InMemorySessionLogStore();
    private final TeamAddress a = new TeamAddress("ns", "a");
    private final TeamAddress b = new TeamAddress("other-ns", "b");

    @Test
    void existingNativeSessionsSupportAdoptionBindingQueriesAndTokenSafeRemoval() {
        var store = new InMemoryStore();
        var client = new LocalTeamClient(store);
        var service = client.sessionMembership("relations", Map.of(), Map.of("journal", logs));
        create(client, a);
        MemberSession leader =
                member("lead", "journal", "alice", "runtime-leader", "leader-session");
        MemberSession worker =
                member("worker", "journal", "alice", "runtime-worker", "worker-session");
        persist(leader, logs);
        persist(worker, logs);
        long committedSeq = log(worker.session(), logs).head().seq();

        var team = service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT);
        assertEquals(Status.ACTIVE, team.status());
        Membership binding = service.bindMember("alice", a, worker).block(WAIT);
        assertEquals(binding, service.findMembership(worker.session()).block(WAIT));
        assertEquals(2, service.getTeam("alice", a).block(WAIT).memberships().size());
        assertEquals(2, service.listMemberships("alice", a).block(WAIT).size());
        assertEquals(
                "worker-session",
                client.listMembers("ns", "a").block(WAIT).stream()
                        .filter(m -> m.memberName().equals("worker"))
                        .findFirst()
                        .orElseThrow()
                        .sessionId());
        assertEquals("worker-definition", binding.member().agentRef());
        assertEquals("runtime-worker", binding.member().session().agentId());

        var reopened =
                new LocalTeamClient(store)
                        .sessionMembership("relations", Map.of(), Map.of("journal", logs));
        assertEquals(binding, reopened.findMembership(worker.session()).block(WAIT));
        assertTrue(
                reopened.unbindMember("alice", a, worker.session(), binding.token()).block(WAIT));
        assertFalse(
                reopened.unbindMember("alice", a, worker.session(), binding.token()).block(WAIT));
        var rebound = reopened.bindMember("alice", a, worker).block(WAIT);
        assertNotEquals(binding.token(), rebound.token());
        assertThrows(
                TeamConflictException.class,
                () ->
                        reopened.unbindMember("alice", a, worker.session(), binding.token())
                                .block(WAIT));
        assertEquals(rebound, reopened.findMembership(worker.session()).block(WAIT));
        assertTrue(
                reopened.unbindMember("alice", a, worker.session(), rebound.token()).block(WAIT));
        assertNull(reopened.findMembership(worker.session()).block(WAIT));
        assertEquals(committedSeq, log(worker.session(), logs).head().seq());
        assertNotNull(SessionProjection.read(log(worker.session(), logs)).stateJson());
        assertEquals(
                "",
                client.listMembers("ns", "a").block(WAIT).stream()
                        .filter(m -> m.memberName().equals("worker"))
                        .findFirst()
                        .orElseThrow()
                        .sessionId());
        assertEquals(team.teamId(), reopened.getTeam("alice", a).block(WAIT).teamId());
    }

    @Test
    void fullNativeIdentityAndActualLogDomainsRemainIsolated() {
        var otherLogs = new InMemorySessionLogStore();
        var client = new LocalTeamClient(new InMemoryStore());
        var service =
                client.sessionMembership(
                        "relations", Map.of(), Map.of("journal", logs, "other-journal", otherLogs));
        List<MemberSession> identities =
                List.of(
                        member("lead", "journal", "alice", "agent", "session"),
                        member("lead", "journal", "bob", "agent", "session"),
                        member("lead", "journal", "alice", "other-agent", "session"),
                        member("lead", "journal", "alice", "agent", "other-session"),
                        member("lead", "journal", null, "agent", "session"),
                        member("lead", "other-journal", "alice", "agent", "session"));
        Set<String> teamIds = new HashSet<>();
        for (int i = 0; i < identities.size(); i++) {
            MemberSession member = identities.get(i);
            persist(
                    member,
                    member.session().stateStoreDomain().equals("journal") ? logs : otherLogs);
            var address = new TeamAddress("ns", "identity-" + i);
            create(client, address);
            var team =
                    service.adoptTeam(
                                    member.session().owner(),
                                    address,
                                    "Same display",
                                    List.of(member))
                            .block(WAIT);
            assertTrue(teamIds.add(team.teamId()));
            assertEquals(member, service.findMembership(member.session()).block(WAIT).member());
        }
        var first = identities.get(0);
        assertEquals(
                first.session(),
                SessionKey.nativeSession(
                        "journal",
                        new io.agentscope.core.session.SessionKey("alice", "agent", "session")));
        assertThrows(
                TeamConflictException.class,
                () ->
                        service.adoptTeam(
                                        "alice",
                                        new TeamAddress("ns", "identity-2"),
                                        "Same display",
                                        List.of(first))
                                .block(WAIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "__anon__", "alice\n"})
    void unsupportedNativeOwnersAreRejectedWithoutChangingLegacyValidation(String owner) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SessionKey("journal", owner, "session", "agent"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionKey.nativeSession(
                                "journal",
                                new io.agentscope.core.session.SessionKey(
                                        owner, "agent", "session")));
        assertThrows(
                IllegalArgumentException.class, () -> new SessionKey("legacy", owner, "session"));
    }

    @Test
    void nativeAndLegacyDomainMappingsCannotOverlapOrReinterpretSessionKeys() {
        var states = new InMemoryAgentStateStore();
        var client = new LocalTeamClient(new InMemoryStore());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.sessionMembership(
                                "relations", Map.of("same", states), Map.of("same", logs)));
        var service =
                client.sessionMembership(
                        "relations", Map.of("legacy", states), Map.of("journal", logs));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.findMembership(
                                        new SessionKey("legacy", "alice", "session", "agent"))
                                .block(WAIT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.findMembership(new SessionKey("journal", "alice", "session"))
                                .block(WAIT));
    }

    @Test
    void boundTenantRoutingUsesActualPartitionsOfTheSameLogBackend() {
        var backend = new InMemoryStore();
        var filesystem =
                new RemoteFilesystem(
                        backend, context -> List.of("tenant", (String) context.get("tenant")));
        var tenantA =
                new WorkspaceSessionLogStore(
                        new BakedContextFilesystem(
                                filesystem, RuntimeContext.builder().put("tenant", "a").build()));
        var tenantB =
                new WorkspaceSessionLogStore(
                        new BakedContextFilesystem(
                                filesystem, RuntimeContext.builder().put("tenant", "b").build()));
        var client = new LocalTeamClient(new InMemoryStore());
        var service =
                client.sessionMembership(
                        "relations", Map.of(), Map.of("tenant-a", tenantA, "tenant-b", tenantB));
        create(client, a);
        create(client, b);
        var first = member("lead", "tenant-a", "alice", "agent", "session");
        var second = member("lead", "tenant-b", "alice", "agent", "session");
        persist(first, tenantA);
        assertNotNull(SessionProjection.read(log(first.session(), tenantA)).stateJson());
        assertNull(SessionProjection.read(log(second.session(), tenantB)).stateJson());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", b, "Tenant B", List.of(second)).block(WAIT));
        var firstTeam = service.adoptTeam("alice", a, "Tenant A", List.of(first)).block(WAIT);
        persist(second, tenantB);
        var secondTeam = service.adoptTeam("alice", b, "Tenant B", List.of(second)).block(WAIT);
        assertNotEquals(firstTeam.teamId(), secondTeam.teamId());
        assertEquals(
                firstTeam.teamId(), service.findMembership(first.session()).block(WAIT).teamId());
        assertEquals(
                secondTeam.teamId(), service.findMembership(second.session()).block(WAIT).teamId());
        assertNotNull(SessionProjection.read(log(second.session(), tenantB)).stateJson());
    }

    @Test
    void openingAnEmptyLogOrPersistingInAnotherDomainDoesNotEstablishSessionExistence() {
        var store = new InMemoryStore();
        var client = new LocalTeamClient(store);
        var emptyLogs = new InMemorySessionLogStore();
        var states = new InMemoryAgentStateStore();
        states.save("alice", "session", "example", new Saved("legacy-only"));
        var service =
                client.sessionMembership(
                        "relations",
                        Map.of("legacy", states),
                        Map.of("journal", logs, "empty-journal", emptyLogs));
        create(client, a);
        var leader = member("lead", "journal", "alice", "agent", "session");
        assertEquals(0, log(leader.session(), logs).head().seq());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT));
        assertFalse(
                store.get(List.of("teams", "ns", "a"), "meta")
                        .value()
                        .containsKey("sessionMembership"));

        persist(leader, logs);
        var wrongDomain = member("lead", "empty-journal", "alice", "agent", "session");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(wrongDomain)).block(WAIT));
        var wrongAgent = member("lead", "journal", "alice", "other-agent", "session");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(wrongAgent)).block(WAIT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.findMembership(
                                        new SessionKey("unknown", "alice", "session", "agent"))
                                .block(WAIT));
        assertEquals(
                Status.ACTIVE,
                service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT).status());
    }

    @Test
    void nativeReadFailuresPropagateBeforeAdoptionWrites() {
        var store = new InMemoryStore();
        var client = new LocalTeamClient(store);
        var failure = new IllegalStateException("session backend unavailable");
        SessionLogStore unavailable =
                (key, context) -> {
                    throw failure;
                };
        var service =
                client.sessionMembership("relations", Map.of(), Map.of("journal", unavailable));
        create(client, a);
        var leader = member("lead", "journal", "alice", "agent", "session");
        assertEquals(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                service.adoptTeam("alice", a, "Display", List.of(leader))
                                        .block(WAIT)));
        assertFalse(
                store.get(List.of("teams", "ns", "a"), "meta")
                        .value()
                        .containsKey("sessionMembership"));
        assertNull(service.findMembership(leader.session()).block(WAIT));
    }

    @Test
    void committedEventsWithoutAStateBaselineDoNotMakeASessionRegistrable() {
        var store = new InMemoryStore();
        var client = new LocalTeamClient(store);
        var service = client.sessionMembership("relations", Map.of(), Map.of("journal", logs));
        create(client, a);
        var leader = member("lead", "journal", "alice", "agent", "session");
        var log = log(leader.session(), logs);
        var writer = log.acquire("incomplete-initialization", Duration.ofSeconds(30));
        try {
            log.commit(
                    writer,
                    "event-without-baseline",
                    0,
                    List.of(
                            new SessionEvent(
                                    1,
                                    "first-event",
                                    1,
                                    1,
                                    "run/start",
                                    "run",
                                    "turn",
                                    true,
                                    "{}")));
        } finally {
            log.release(writer);
        }
        assertEquals(1, log.head().seq());
        assertNull(SessionProjection.read(log).stateJson());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT));
        assertFalse(
                store.get(List.of("teams", "ns", "a"), "meta")
                        .value()
                        .containsKey("sessionMembership"));
    }

    @Test
    void pendingNativeAdoptionRecoversAndReplayDoesNotRestoreRemovedAssociations() {
        var failCommit = new AtomicBoolean(true);
        var store =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> ns, String key, Map<String, Object> value, long expected) {
                        if (ns.get(0).equals("team-session-membership")
                                && !((Map<?, ?>) value.get("receipts")).isEmpty()
                                && failCommit.compareAndSet(true, false)) {
                            throw new IllegalStateException("injected adoption commit failure");
                        }
                        return super.putIfVersion(ns, key, value, expected);
                    }
                };
        var client = new LocalTeamClient(store);
        var service = client.sessionMembership("relations", Map.of(), Map.of("journal", logs));
        create(client, a);
        var leader = member("lead", "journal", "alice", "agent", "session");
        persist(leader, logs);
        long committedSeq = log(leader.session(), logs).head().seq();
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT));
        var pending = service.getTeam("alice", a).block(WAIT);
        assertEquals(Status.PENDING, pending.status());
        assertTrue(pending.memberships().isEmpty());
        assertNull(service.findMembership(leader.session()).block(WAIT));
        assertThrows(
                IllegalStateException.class, () -> service.listMemberships("alice", a).block(WAIT));
        var declared = client.listMembers("ns", "a").block(WAIT);
        assertEquals(
                Set.of("lead", "worker"),
                declared.stream().map(TeamMemberInfo::memberName).collect(Collectors.toSet()));
        assertTrue(declared.stream().allMatch(m -> m.sessionId().isEmpty()));

        var reopened =
                new LocalTeamClient(store)
                        .sessionMembership("relations", Map.of(), Map.of("journal", logs));
        var active = reopened.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT);
        assertEquals(Status.ACTIVE, active.status());
        assertEquals(pending.teamId(), active.teamId());
        var binding = active.memberships().get(0);
        assertEquals(binding, reopened.findMembership(leader.session()).block(WAIT));
        assertTrue(
                reopened.unbindMember("alice", a, leader.session(), binding.token()).block(WAIT));
        var replay = reopened.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT);
        assertEquals(active.teamId(), replay.teamId());
        assertTrue(replay.memberships().isEmpty());
        assertNull(reopened.findMembership(leader.session()).block(WAIT));
        assertEquals(committedSeq, log(leader.session(), logs).head().seq());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schemaOneCannotSilentlyReinterpretNativeOwnerOrMarkerRecords(boolean corruptOwner) {
        var store = new InMemoryStore();
        var client = new LocalTeamClient(store);
        var service = client.sessionMembership("relations", Map.of(), Map.of("journal", logs));
        create(client, a);
        var leader = member("lead", "journal", "alice", "agent", "session");
        persist(leader, logs);
        var binding =
                service.adoptTeam("alice", a, "Display", List.of(leader))
                        .block(WAIT)
                        .memberships()
                        .get(0);
        var metaNs = List.of("teams", "ns", "a");
        if (corruptOwner) {
            var owner = new LinkedHashMap<>(store.get(RELATION_NS, "owner-YWxpY2U").value());
            owner.put("schema", 1);
            store.put(RELATION_NS, "owner-YWxpY2U", owner);
        } else {
            var meta = new LinkedHashMap<>(store.get(metaNs, "meta").value());
            var marker =
                    new LinkedHashMap<Object, Object>((Map<?, ?>) meta.get("sessionMembership"));
            marker.put("schema", 1);
            meta.put("sessionMembership", marker);
            store.put(metaNs, "meta", meta);
        }
        var ownerBefore = store.get(RELATION_NS, "owner-YWxpY2U");
        var metaBefore = store.get(metaNs, "meta");
        assertThrows(IllegalStateException.class, () -> service.getTeam("alice", a).block(WAIT));
        assertThrows(IllegalStateException.class, () -> client.listMembers("ns", "a").block(WAIT));
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "Display", List.of(leader)).block(WAIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.unbindMember("alice", a, leader.session(), binding.token())
                                .block(WAIT));
        assertEquals(ownerBefore, store.get(RELATION_NS, "owner-YWxpY2U"));
        assertEquals(metaBefore, store.get(metaNs, "meta"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentClientsEnforceOneNativeMembershipAndReplayIdenticalBindings(boolean sameTeam)
            throws Exception {
        var racing = new AtomicBoolean();
        var writes = new AtomicInteger();
        var barrier = new CyclicBarrier(2);
        var store =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> ns, String key, Map<String, Object> value, long expected) {
                        if (racing.get()
                                && ns.get(0).equals("team-session-membership")
                                && writes.incrementAndGet() <= 2) {
                            try {
                                barrier.await(10, TimeUnit.SECONDS);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        }
                        return super.putIfVersion(ns, key, value, expected);
                    }
                };
        var client = new LocalTeamClient(store);
        var left = client.sessionMembership("relations", Map.of(), Map.of("journal", logs));
        var right =
                new LocalTeamClient(store)
                        .sessionMembership("relations", Map.of(), Map.of("journal", logs));
        create(client, a);
        create(client, b);
        var firstLeader = member("lead", "journal", "alice", "leader", "one");
        var secondLeader = member("lead", "journal", "alice", "leader", "two");
        var worker = member("worker", "journal", "alice", "worker", "session");
        persist(firstLeader, logs);
        persist(secondLeader, logs);
        persist(worker, logs);
        left.adoptTeam("alice", a, "Display", List.of(firstLeader)).block(WAIT);
        right.adoptTeam("alice", b, "Display", List.of(secondLeader)).block(WAIT);

        racing.set(true);
        var x = attempt(() -> left.bindMember("alice", a, worker).block(WAIT));
        var y = attempt(() -> right.bindMember("alice", sameTeam ? a : b, worker).block(WAIT));
        List<Object> outcomes = List.of(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS));
        assertEquals(
                sameTeam ? 2 : 1, outcomes.stream().filter(Membership.class::isInstance).count());
        assertEquals(
                sameTeam ? 0 : 1,
                outcomes.stream().filter(TeamConflictException.class::isInstance).count());
        Membership winner = left.findMembership(worker.session()).block(WAIT);
        assertEquals(winner, right.findMembership(worker.session()).block(WAIT));
        if (sameTeam) assertEquals(outcomes.get(0), outcomes.get(1));
        assertEquals(
                1,
                left.listMemberships("alice", a).block(WAIT).stream()
                                .filter(m -> m.member().session().equals(worker.session()))
                                .count()
                        + left.listMemberships("alice", b).block(WAIT).stream()
                                .filter(m -> m.member().session().equals(worker.session()))
                                .count());
    }

    @Test
    void legacyJsonAndBindingsSurviveNativeSchemaUpgradeWithoutDowngrade() throws Exception {
        var store = new InMemoryStore();
        var states = new InMemoryAgentStateStore();
        var client = new LocalTeamClient(store);
        var service =
                client.sessionMembership(
                        "relations", Map.of("legacy", states), Map.of("journal", logs));
        create(client, a);
        create(client, b);
        var oldKey = new SessionKey("legacy", "alice", "session");
        var oldLeader =
                new MemberSession("lead", "definitions", "leader-definition", Role.LEADER, oldKey);
        states.save("alice", "session", "example", new Saved("original"));
        var oldBinding =
                service.adoptTeam("alice", a, "Legacy", List.of(oldLeader))
                        .block(WAIT)
                        .memberships()
                        .get(0);
        var mapper = new ObjectMapper();
        String oldJson =
                "{\"stateStoreDomain\":\"legacy\",\"owner\":\"alice\",\"sessionId\":\"session\"}";
        assertEquals(mapper.readTree(oldJson), mapper.readTree(mapper.writeValueAsString(oldKey)));
        assertEquals(oldKey, mapper.readValue(oldJson, SessionKey.class));
        assertEquals(1, ownerSchema(store));

        var nativeLeader = member("lead", "journal", "alice", "agent", "session");
        persist(nativeLeader, logs);
        var nativeBinding =
                service.adoptTeam("alice", b, "Native", List.of(nativeLeader))
                        .block(WAIT)
                        .memberships()
                        .get(0);
        assertEquals(2, ownerSchema(store));
        assertEquals(oldBinding, service.findMembership(oldKey).block(WAIT));
        assertNotEquals(oldBinding.teamId(), nativeBinding.teamId());
        assertEquals(
                nativeLeader.session(),
                mapper.readValue(
                        mapper.writeValueAsString(nativeLeader.session()), SessionKey.class));
        var legacyMarker =
                (Map<?, ?>)
                        store.get(List.of("teams", "ns", "a"), "meta")
                                .value()
                                .get("sessionMembership");
        var nativeMarker =
                (Map<?, ?>)
                        store.get(List.of("teams", "other-ns", "b"), "meta")
                                .value()
                                .get("sessionMembership");
        assertEquals(1, ((Number) legacyMarker.get("schema")).intValue());
        assertEquals(2, ((Number) nativeMarker.get("schema")).intValue());

        var reopened =
                new LocalTeamClient(store)
                        .sessionMembership(
                                "relations", Map.of("legacy", states), Map.of("journal", logs));
        assertTrue(
                reopened.unbindMember("alice", b, nativeLeader.session(), nativeBinding.token())
                        .block(WAIT));
        assertEquals(2, ownerSchema(store));
        assertEquals(oldBinding, reopened.findMembership(oldKey).block(WAIT));
        assertTrue(reopened.unbindMember("alice", a, oldKey, oldBinding.token()).block(WAIT));
        assertEquals(2, ownerSchema(store));
        assertEquals(
                "original",
                states.get("alice", "session", "example", Saved.class).orElseThrow().text());
    }

    @Test
    void nativeWorkerCanJoinLegacyAdoptionWithoutChangingItsMarkerOrInitialBinding() {
        var store = new InMemoryStore();
        var states = new InMemoryAgentStateStore();
        states.save("alice", "legacy-session", "example", new Saved("original"));
        var client = new LocalTeamClient(store);
        var service =
                client.sessionMembership(
                        "relations", Map.of("legacy", states), Map.of("journal", logs));
        create(client, a);
        var leader =
                new MemberSession(
                        "lead",
                        "definitions",
                        "leader-definition",
                        Role.LEADER,
                        new SessionKey("legacy", "alice", "legacy-session"));
        var legacyTeam = service.adoptTeam("alice", a, "Mixed", List.of(leader)).block(WAIT);
        var metaBefore = store.get(List.of("teams", "ns", "a"), "meta");
        assertEquals(1, ownerSchema(store));
        var worker = member("worker", "journal", "alice", "runtime-worker", "native-session");
        persist(worker, logs);
        var binding = service.bindMember("alice", a, worker).block(WAIT);
        assertEquals(2, ownerSchema(store));
        assertEquals(metaBefore, store.get(List.of("teams", "ns", "a"), "meta"));
        assertEquals(legacyTeam.teamId(), binding.teamId());
        assertEquals(
                legacyTeam.memberships().get(0),
                service.findMembership(leader.session()).block(WAIT));
        assertEquals(2, service.listMemberships("alice", a).block(WAIT).size());
        assertEquals(
                "native-session",
                client.listMembers("ns", "a").block(WAIT).stream()
                        .filter(m -> m.memberName().equals("worker"))
                        .findFirst()
                        .orElseThrow()
                        .sessionId());

        var reopened =
                new LocalTeamClient(store)
                        .sessionMembership(
                                "relations", Map.of("legacy", states), Map.of("journal", logs));
        assertEquals(binding, reopened.findMembership(worker.session()).block(WAIT));
        assertTrue(
                reopened.unbindMember("alice", a, worker.session(), binding.token()).block(WAIT));
        assertEquals(2, ownerSchema(store));
        assertEquals(
                legacyTeam, reopened.adoptTeam("alice", a, "Mixed", List.of(leader)).block(WAIT));
        assertEquals(metaBefore, store.get(List.of("teams", "ns", "a"), "meta"));
        assertEquals(
                "",
                client.listMembers("ns", "a").block(WAIT).stream()
                        .filter(m -> m.memberName().equals("worker"))
                        .findFirst()
                        .orElseThrow()
                        .sessionId());
        assertNotNull(SessionProjection.read(log(worker.session(), logs)).stateJson());
    }

    private static int ownerSchema(InMemoryStore store) {
        return ((Number) store.get(RELATION_NS, "owner-YWxpY2U").value().get("schema")).intValue();
    }

    private static void create(LocalTeamClient client, TeamAddress address) {
        client.createTeam(
                        new TeamCreateSpec(
                                address.teamName(),
                                address.namespace(),
                                "objective",
                                "leader-definition",
                                "",
                                List.of(
                                        new TeamMemberSpec(
                                                "worker", "worker-definition", "", "byo"))))
                .block(WAIT);
    }

    private static MemberSession member(
            String alias, String domain, String user, String agent, String session) {
        return new MemberSession(
                alias,
                "definitions",
                alias.equals("lead") ? "leader-definition" : "worker-definition",
                alias.equals("lead") ? Role.LEADER : Role.WORKER,
                new SessionKey(domain, user, session, agent));
    }

    private static SessionLog log(SessionKey session, SessionLogStore store) {
        return store.open(
                new io.agentscope.core.session.SessionKey(
                        session.owner(), session.agentId(), session.sessionId()),
                RuntimeContext.builder()
                        .userId(session.owner())
                        .sessionId(session.sessionId())
                        .build());
    }

    private void persist(MemberSession member, SessionLogStore logs) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "baseline-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("initialized")
                                                                .build()))
                                        .build());
                    }
                };
        try (var agent =
                HarnessAgent.builder()
                        .agentId(member.session().agentId())
                        .workspace(workspace)
                        .model(model)
                        .sessionLogStore(logs)
                        .disableShellTool()
                        .disableFilesystemTools()
                        .disableWebTools()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableToolsConfig()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .build()) {
            agent.call(
                            "initialize",
                            RuntimeContext.builder()
                                    .userId(member.session().owner())
                                    .sessionId(member.session().sessionId())
                                    .build())
                    .block(WAIT);
        }
    }

    private static CompletableFuture<Object> attempt(Callable<Object> action) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return action.call();
                    } catch (Exception failure) {
                        return failure;
                    }
                });
    }

    record Saved(String text) implements State {}
}
