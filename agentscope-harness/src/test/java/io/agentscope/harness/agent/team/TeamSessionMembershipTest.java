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
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Membership;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.Status;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamSessionMembershipTest {
    @TempDir Path temp;
    private InMemoryStore store;
    private InMemoryAgentStateStore states;
    private LocalTeamClient client;
    private TeamSessionMembership service;
    private final TeamAddress a = new TeamAddress("ns-a", "a");
    private final TeamAddress b = new TeamAddress("ns-b", "b");

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        states = new InMemoryAgentStateStore();
        client = new LocalTeamClient(store);
        service = client.sessionMembership("app", Map.of("state", states));
        create(a);
        create(b);
    }

    @Test
    void usableLifecycleKeepsLegacyMetadataStateAndWorkspace() throws Exception {
        JsonFileAgentStateStore files = new JsonFileAgentStateStore(temp.resolve("state"));
        service = client.sessionMembership("app", Map.of("state", files));
        MemberSession leader = member(files, "alice", "lead", "leader", "leader-agent");
        MemberSession worker = member(files, "alice", "worker", "worker", "worker-agent");
        Path workspace = temp.resolve("workspace.txt");
        Files.writeString(workspace, "existing files");
        TeamView view = service.adoptTeam("alice", a, "research", List.of(leader)).block();
        Membership bound = service.bindMember("alice", a, worker).block();
        assertEquals(bound, service.findMembership(worker.session()).block());
        assertEquals(2, service.listMemberships("alice", a).block().size());
        assertEquals("worker", legacy("worker").sessionId());
        assertEquals(bound, service.bindMember("alice", a, worker).block());
        client.completeTeam(a.namespace(), a.teamName()).block();
        assertEquals("Completed", service.getTeam("alice", a).block().info().phase());
        assertTrue(service.unbindMember("alice", a, worker.session(), bound.token()).block());
        assertFalse(service.unbindMember("alice", a, worker.session(), bound.token()).block());
        assertNull(service.findMembership(worker.session()).block());
        assertEquals("", legacy("worker").sessionId());
        assertEquals("worker-agent", legacy("worker").agentRef());
        assertEquals("Working", legacy("worker").phase());
        assertEquals("existing files", Files.readString(workspace));
        assertEquals(
                new SavedState("original"),
                files.get("alice", "worker", "legacy", SavedState.class).orElseThrow());
        TeamView replay = service.adoptTeam("alice", a, "research", List.of(leader)).block();
        assertEquals(view.teamId(), replay.teamId());
        assertEquals(1, replay.memberships().size());
        Membership newBinding = service.bindMember("alice", a, worker).block();
        assertNotEquals(bound.token(), newBinding.token());
        assertThrows(
                TeamConflictException.class,
                () -> service.unbindMember("alice", a, worker.session(), bound.token()).block());
        assertEquals(newBinding, service.findMembership(worker.session()).block());
    }

    @Test
    void sameAgentDifferentSessionsCanJoinDifferentTeamsAndSameDisplayName() {
        MemberSession la = member(states, "alice", "lead", "one", "leader-agent");
        MemberSession lb = member(states, "alice", "lead", "two", "leader-agent");
        TeamView va = service.adoptTeam("alice", a, "same name", List.of(la)).block();
        TeamView vb = service.adoptTeam("alice", b, "same name", List.of(lb)).block();
        assertNotEquals(va.teamId(), vb.teamId());
        assertEquals(va.displayName(), vb.displayName());
        assertEquals(va.teamId(), service.findMembership(la.session()).block().teamId());
        assertEquals(vb.teamId(), service.findMembership(lb.session()).block().teamId());
        MemberSession wa = member(states, "alice", "worker", "worker-one", "worker-agent");
        service.bindMember("alice", a, wa).block();
        assertThrows(TeamConflictException.class, () -> service.bindMember("alice", b, wa).block());
        assertThrows(
                TeamConflictException.class,
                () ->
                        service.bindMember(
                                        "alice",
                                        a,
                                        member(
                                                states,
                                                "alice",
                                                "worker",
                                                "worker-two",
                                                "worker-agent"))
                                .block());
        assertThrows(TeamConflictException.class, () -> service.bindMember("alice", a, lb).block());
    }

    @Test
    void releasingLeaderDoesNotReplayAdoptionOrPreventExplicitReplacement() {
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        TeamView view = service.adoptTeam("alice", a, "a", List.of(lead)).block();
        Membership binding = view.memberships().get(0);
        service.unbindMember("alice", a, lead.session(), binding.token()).block();
        assertTrue(
                service.adoptTeam("alice", a, "a", List.of(lead)).block().memberships().isEmpty());
        MemberSession next = member(states, "alice", "lead", "replacement", "leader-agent");
        assertEquals(next, service.bindMember("alice", a, next).block().member());
        assertThrows(
                TeamConflictException.class,
                () -> service.unbindMember("alice", a, next.session(), binding.token()).block());
    }

    @Test
    void ownerAndActualStateDomainsDistinguishIdenticalSessionStrings() {
        InMemoryAgentStateStore separate = new InMemoryAgentStateStore();
        service = client.sessionMembership("app", Map.of("state", states, "separate", separate));
        MemberSession la = member(states, "alice", "lead", "same", "leader-agent");
        MemberSession lb =
                new MemberSession(
                        "lead",
                        "definition-owner",
                        "leader-agent",
                        Role.LEADER,
                        new SessionKey("separate", "alice", "same"));
        separate.save("alice", "same", "legacy", new SavedState("original"));
        service.adoptTeam("alice", a, "a", List.of(la)).block();
        service.adoptTeam("alice", b, "b", List.of(lb)).block();
        assertNotEquals(
                service.findMembership(la.session()).block().teamId(),
                service.findMembership(lb.session()).block().teamId());
        TeamAddress c = new TeamAddress("ns-a", "c");
        create(c);
        MemberSession other = member(states, "bob", "lead", "same", "leader-agent");
        service.adoptTeam("bob", c, "a", List.of(other)).block();
        assertEquals("bob", service.findMembership(other.session()).block().teamOwner());
        assertThrows(TeamConflictException.class, () -> service.getTeam("bob", a).block());
        assertThrows(
                TeamConflictException.class,
                () ->
                        client.sessionMembership("other-app", Map.of("state", states))
                                .adoptTeam("alice", a, "a", List.of(la))
                                .block());
    }

    @Test
    void anonymousSessionsAreSupportedAndReservedAliasesRejected() {
        MemberSession anonymous = member(states, null, "lead", "same", "leader-agent");
        service.adoptTeam(null, a, "anonymous", List.of(anonymous)).block();
        assertNull(service.findMembership(anonymous.session()).block().teamOwner());
        assertThrows(
                IllegalArgumentException.class, () -> new SessionKey("state", "__anon__", "same"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKey("state", " ", "same"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKey("state", null, " "));
        assertThrows(IllegalArgumentException.class, () -> new TeamAddress("", "a"));
        assertThrows(IllegalArgumentException.class, () -> new TeamAddress("ns\0a", "a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", b, "b", List.of(anonymous)).block());
    }

    @Test
    @SuppressWarnings("unchecked")
    void storesOmittingNullIdentityFieldsRemainReadableAndRequiredFieldsStayEnforced() {
        // Mirrors a JdbcStore ObjectMapper configured with JsonInclude.Include.NON_NULL.
        store =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> ns, String key, Map<String, Object> value, long version) {
                        return super.putIfVersion(ns, key, strip(value), version);
                    }

                    @Override
                    public void put(List<String> ns, String key, Map<String, Object> value) {
                        super.put(ns, key, strip(value));
                    }
                };
        client = new LocalTeamClient(store);
        create(a);
        service = client.sessionMembership("app", Map.of("state", states));
        MemberSession anonymous =
                new MemberSession(
                        "lead",
                        null,
                        "leader-agent",
                        Role.LEADER,
                        new SessionKey("state", null, "anon"));
        states.save(null, "anon", "legacy", new SavedState("original"));
        TeamView view = service.adoptTeam(null, a, "anonymous", List.of(anonymous)).block();
        assertEquals(Status.ACTIVE, view.status());
        assertNull(view.teamOwner());
        assertNull(view.memberships().get(0).member().definitionOwner());
        assertEquals("anon", legacy("lead").sessionId());
        Membership bound = service.findMembership(anonymous.session()).block();
        assertEquals(anonymous, bound.member());
        assertTrue(service.unbindMember(null, a, anonymous.session(), bound.token()).block());

        List<String> ns = List.of("teams", "ns-a", "a");
        Map<String, Object> meta = new LinkedHashMap<>(store.get(ns, "meta").value());
        Map<String, Object> marker =
                new LinkedHashMap<>((Map<String, Object>) meta.get("sessionMembership"));
        marker.remove("teamId");
        meta.put("sessionMembership", marker);
        store.put(ns, "meta", meta);
        assertThrows(IllegalStateException.class, () -> service.getTeam(null, a).block());
    }

    @Test
    void requiresRealPersistedSessionsAndMatchingDeclaredDefinitions() {
        MemberSession missing =
                new MemberSession(
                        "lead",
                        "alice",
                        "leader-agent",
                        Role.LEADER,
                        new SessionKey("state", "alice", "missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(missing)).block());
        assertFalse(
                store.get(List.of("teams", "ns-a", "a"), "meta")
                        .value()
                        .containsKey("sessionMembership"));
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        service.adoptTeam("alice", a, "a", List.of(lead)).block();
        MemberSession wrongRef = member(states, "alice", "worker", "worker", "wrong-agent");
        assertThrows(
                TeamConflictException.class,
                () -> service.bindMember("alice", a, wrongRef).block());
        MemberSession absent = member(states, "alice", "missing", "worker", "worker-agent");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.bindMember("alice", a, absent).block());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.findMembership(new SessionKey("unknown", "alice", "one")).block());
    }

    @Test
    void adoptionRequiresExplicitIdentitiesForLegacyNonemptySessionFields() {
        store.put(
                List.of("teams", "ns-a", "a", "members"),
                "worker",
                new TeamMemberInfo("worker", "worker-agent", "Working", "old", "byo", false)
                        .toMap());
        MemberSession lead = member(states, "alice", "lead", "lead", "leader-agent");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        assertEquals("old", legacy("worker").sessionId());
        MemberSession old = member(states, "alice", "worker", "old", "worker-agent");
        TeamView view = service.adoptTeam("alice", a, "a", List.of(old, lead)).block();
        assertEquals(2, view.memberships().size());
        assertEquals(view, service.adoptTeam("alice", a, "a", List.of(lead, old)).block());
        assertThrows(
                TeamConflictException.class,
                () -> service.adoptTeam("alice", a, "renamed", List.of(lead, old)).block());
    }

    @Test
    void longOwnerAndDomainAddressesKeepDistinctSessionScopes() {
        String owner = "a".repeat(187), otherOwner = owner + "b";
        MemberSession first = member(states, owner, "lead", "same", "leader-agent");
        MemberSession second = member(states, otherOwner, "lead", "same", "leader-agent");
        Membership one =
                service.adoptTeam(owner, a, "a", List.of(first)).block().memberships().get(0);
        Membership two =
                service.adoptTeam(otherOwner, b, "b", List.of(second)).block().memberships().get(0);
        assertNotEquals(one.teamId(), two.teamId());
        assertEquals(one, service.findMembership(first.session()).block());
        assertEquals(two, service.findMembership(second.session()).block());

        String domain = "域".repeat(600);
        TeamSessionMembership left = client.sessionMembership(domain, Map.of("state", states));
        TeamSessionMembership right =
                client.sessionMembership(domain + "b", Map.of("state", states));
        TeamAddress c = new TeamAddress("ns-c", "c"), d = new TeamAddress("ns-d", "d");
        create(c);
        create(d);
        Membership x = left.adoptTeam(owner, c, "c", List.of(first)).block().memberships().get(0);
        Membership y = right.adoptTeam(owner, d, "d", List.of(first)).block().memberships().get(0);
        assertNotEquals(x.teamId(), y.teamId());
        assertEquals(x, left.findMembership(first.session()).block());
        assertEquals(y, right.findMembership(first.session()).block());
        assertTrue(left.unbindMember(owner, c, first.session(), x.token()).block());
        assertEquals(y, right.findMembership(first.session()).block());
    }

    @Test
    void ownerInitializationContentionDoesNotAdoptTeamOrBlockLegacyQueries() {
        FaultStore faulty = useFaultStore();
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        faulty.conflict = true;
        assertThrows(
                TeamConflictException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        assertEquals(10, faulty.failedCas.get());
        assertFalse(
                faulty.get(List.of("teams", "ns-a", "a"), "meta")
                        .value()
                        .containsKey("sessionMembership"));
        assertEquals("", legacy("lead").sessionId());
        assertNull(service.findMembership(lead.session()).block());
        faulty.conflict = false;
        assertEquals(
                Status.ACTIVE, service.adoptTeam("alice", a, "a", List.of(lead)).block().status());
    }

    @Test
    void pendingAdoptionAfterFailureIsObservableAndRecoverableByNewClient() {
        FaultStore faulty = useFaultStore();
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        faulty.beforeRelation = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        assertEquals(Status.PENDING, service.getTeam("alice", a).block().status());
        assertTrue(service.getTeam("alice", a).block().memberships().isEmpty());
        assertNull(service.findMembership(lead.session()).block());
        assertThrows(IllegalStateException.class, () -> client.listMembers("ns-a", "a").block());
        assertThrows(
                IllegalStateException.class, () -> service.listMemberships("alice", a).block());
        faulty.beforeRelation = false;
        TeamSessionMembership reopened =
                new LocalTeamClient(faulty).sessionMembership("app", Map.of("state", states));
        assertEquals(
                Status.ACTIVE, reopened.adoptTeam("alice", a, "a", List.of(lead)).block().status());
        assertEquals("one", legacy("lead").sessionId());
    }

    @Test
    void failedMetaWriteDoesNotEnableTeamAndCommittedResponseFailureCanBeRetried() {
        FaultStore faulty = useFaultStore();
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        faulty.beforeMeta = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        assertEquals("", legacy("lead").sessionId());
        faulty.beforeMeta = false;
        faulty.afterRelation = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        Membership committed = service.findMembership(lead.session()).block();
        assertNotNull(committed);
        faulty.afterRelation = false;
        assertEquals(
                committed,
                service.adoptTeam("alice", a, "a", List.of(lead)).block().memberships().get(0));
        MemberSession worker = member(states, "alice", "worker", "worker", "worker-agent");
        faulty.afterRelation = true;
        assertThrows(
                IllegalStateException.class, () -> service.bindMember("alice", a, worker).block());
        faulty.afterRelation = false;
        Membership bound = service.findMembership(worker.session()).block();
        assertEquals(bound, service.bindMember("alice", a, worker).block());
        faulty.afterRelation = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.unbindMember("alice", a, worker.session(), bound.token()).block());
        faulty.afterRelation = false;
        assertNull(service.findMembership(worker.session()).block());
        assertFalse(service.unbindMember("alice", a, worker.session(), bound.token()).block());
    }

    @Test
    void persistentCasConflictsDoNotMutateStoredReferencesOrFallBackToPut() throws Exception {
        FaultStore faulty = useFaultStore();
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        service.adoptTeam("alice", a, "a", List.of(lead)).block();
        String before =
                new ObjectMapper()
                        .writeValueAsString(
                                faulty.search(List.of("team-session-membership"), 100, 0));
        faulty.conflict = true;
        MemberSession worker = member(states, "alice", "worker", "worker", "worker-agent");
        assertThrows(
                TeamConflictException.class, () -> service.bindMember("alice", a, worker).block());
        assertEquals(10, faulty.failedCas.get());
        assertEquals(
                before,
                new ObjectMapper()
                        .writeValueAsString(
                                faulty.search(List.of("team-session-membership"), 100, 0)));
        assertNull(service.findMembership(worker.session()).block());
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.listMemberships("alice", a).block().clear());
    }

    @Test
    void twoIndependentClientsCompeteForOneSessionAndIdenticalRequestsAreIdempotent()
            throws Exception {
        RacingStore racing = new RacingStore();
        store = racing;
        client = new LocalTeamClient(store);
        service = client.sessionMembership("app", Map.of("state", states));
        create(a);
        create(b);
        service.adoptTeam(
                        "alice",
                        a,
                        "a",
                        List.of(member(states, "alice", "lead", "one", "leader-agent")))
                .block();
        service.adoptTeam(
                        "alice",
                        b,
                        "b",
                        List.of(member(states, "alice", "lead", "two", "leader-agent")))
                .block();
        TeamSessionMembership other =
                new LocalTeamClient(store).sessionMembership("app", Map.of("state", states));
        MemberSession worker = member(states, "alice", "worker", "worker", "worker-agent");
        racing.arm();
        CompletableFuture<Object> first =
                attempt(() -> service.bindMember("alice", a, worker).block());
        CompletableFuture<Object> second =
                attempt(() -> other.bindMember("alice", b, worker).block());
        Object x = first.get(10, TimeUnit.SECONDS), y = second.get(10, TimeUnit.SECONDS);
        assertEquals(1, List.of(x, y).stream().filter(Membership.class::isInstance).count());
        assertEquals(
                1, List.of(x, y).stream().filter(TeamConflictException.class::isInstance).count());
        Membership won = service.findMembership(worker.session()).block();
        service.unbindMember("alice", won.address(), worker.session(), won.token()).block();
        racing.arm();
        first = attempt(() -> service.bindMember("alice", a, worker).block());
        second = attempt(() -> other.bindMember("alice", a, worker).block());
        assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void completeTeamReadBeforeAdoptionCannotEraseItsMarker() throws Exception {
        CountDownLatch read = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        InMemoryStore concurrent =
                new InMemoryStore() {
                    @Override
                    public StoreItem get(List<String> ns, String key) {
                        StoreItem item = super.get(ns, key);
                        if (key.equals("meta") && pause.compareAndSet(true, false)) {
                            read.countDown();
                            await(release);
                        }
                        return item;
                    }
                };
        store = concurrent;
        client = new LocalTeamClient(store);
        create(a);
        service = client.sessionMembership("app", Map.of("state", states));
        CompletableFuture<Void> completing =
                CompletableFuture.runAsync(() -> client.completeTeam("ns-a", "a").block());
        try {
            assertTrue(read.await(5, TimeUnit.SECONDS));
            service.adoptTeam(
                            "alice",
                            a,
                            "a",
                            List.of(member(states, "alice", "lead", "one", "leader-agent")))
                    .block();
        } finally {
            release.countDown();
        }
        completing.get(10, TimeUnit.SECONDS);
        client.completeTeam("ns-a", "a").block();
        assertEquals("Completed", service.getTeam("alice", a).block().info().phase());
        assertEquals(Status.ACTIVE, service.getTeam("alice", a).block().status());
        assertEquals("one", legacy("lead").sessionId());
    }

    @Test
    void unenabledLegacyProviderAndRecordsKeepTheirContracts() throws Exception {
        BaseStore legacy =
                new BaseStore() {
                    final InMemoryStore delegate = new InMemoryStore();

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
                };
        LocalTeamClient old = new LocalTeamClient(legacy);
        old.createTeam(new TeamCreateSpec("old", "ns", "objective", "lead", "", List.of())).block();
        assertThrows(
                IllegalArgumentException.class,
                () -> old.sessionMembership("app", Map.of("state", states)));
        old.completeTeam("ns", "old").block();
        assertEquals(
                "Completed",
                legacy.get(List.of("teams", "ns", "old"), "meta").value().get("phase"));
        assertEquals("", old.listMembers("ns", "old").block().get(0).sessionId());
        TeamMemberInfo record = new TeamMemberInfo("worker", "ref", "Working", "s", "byo", false);
        assertEquals(record, TeamMemberInfo.fromMap(record.toMap()));
        ObjectMapper json = new ObjectMapper();
        assertEquals(record, json.readValue(json.writeValueAsString(record), TeamMemberInfo.class));
        assertEquals(6, record.toMap().size());
    }

    @Test
    void adoptedDirectoryIsNotTruncatedAtOneHundredMembers() {
        TeamAddress large = new TeamAddress("ns-a", "large");
        List<TeamMemberSpec> specs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            specs.add(new TeamMemberSpec("w" + i, "worker-agent", "", "byo"));
        }
        client.createTeam(
                        new TeamCreateSpec("large", "ns-a", "objective", "leader-agent", "", specs))
                .block();
        assertEquals(100, client.listMembers("ns-a", "large").block().size());
        service.adoptTeam(
                        "alice",
                        large,
                        "large",
                        List.of(member(states, "alice", "lead", "one", "leader-agent")))
                .block();
        assertEquals(121, client.listMembers("ns-a", "large").block().size());
    }

    @Test
    void corruptOrUnknownFormatsAndUnknownStoreVersionsAreNotOverwritten() {
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        List<String> ns = List.of("teams", "ns-a", "a");
        Map<String, Object> meta = new LinkedHashMap<>(store.get(ns, "meta").value());
        meta.put("sessionMembership", Map.of("schema", 99));
        store.put(ns, "meta", meta);
        long version = store.get(ns, "meta").version();
        assertThrows(
                IllegalStateException.class,
                () -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        assertEquals(version, store.get(ns, "meta").version());
        InMemoryStore unknown =
                new InMemoryStore() {
                    @Override
                    public StoreItem get(List<String> namespace, String key) {
                        StoreItem item = super.get(namespace, key);
                        return item == null ? null : new StoreItem(key, item.value());
                    }
                };
        LocalTeamClient old = new LocalTeamClient(unknown);
        old.createTeam(new TeamCreateSpec("a", "ns", "", "leader-agent", "", List.of())).block();
        assertThrows(
                IllegalStateException.class,
                () ->
                        old.sessionMembership("app", Map.of("state", states))
                                .adoptTeam("alice", new TeamAddress("ns", "a"), "a", List.of(lead))
                                .block());
    }

    @Test
    void conflictingPendingAdoptionCanFinishAfterPreviousTeamReleasesSession() {
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        Membership old =
                service.adoptTeam("alice", a, "a", List.of(lead)).block().memberships().get(0);
        assertThrows(
                TeamConflictException.class,
                () -> service.adoptTeam("alice", b, "b", List.of(lead)).block());
        assertEquals(Status.PENDING, service.getTeam("alice", b).block().status());
        service.unbindMember("alice", a, lead.session(), old.token()).block();
        Membership replacement =
                service.adoptTeam("alice", b, "b", List.of(lead)).block().memberships().get(0);
        assertNotEquals(old.token(), replacement.token());
        assertThrows(
                TeamConflictException.class,
                () -> service.unbindMember("alice", a, lead.session(), old.token()).block());
        assertEquals(replacement, service.findMembership(lead.session()).block());
    }

    @Test
    void concurrentSameAdoptionHasOneLogicalIdAndOnePersistentBinding() throws Exception {
        RacingStore racing = new RacingStore();
        store = racing;
        client = new LocalTeamClient(store);
        create(a);
        service = client.sessionMembership("app", Map.of("state", states));
        TeamSessionMembership other =
                new LocalTeamClient(store).sessionMembership("app", Map.of("state", states));
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        racing.arm();
        CompletableFuture<Object> first =
                attempt(() -> service.adoptTeam("alice", a, "a", List.of(lead)).block());
        CompletableFuture<Object> second =
                attempt(() -> other.adoptTeam("alice", a, "a", List.of(lead)).block());
        Object x = first.get(10, TimeUnit.SECONDS), y = second.get(10, TimeUnit.SECONDS);
        assertTrue(x instanceof TeamView);
        assertEquals(x, y);
        assertEquals(1, service.listMemberships("alice", a).block().size());
    }

    @Test
    void roleMismatchesAndUnknownTeamDoNotGrantAssociations() {
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        assertThrows(IllegalArgumentException.class, () -> service.getTeam("alice", a).block());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.adoptTeam(
                                        "alice",
                                        new TeamAddress("ns", "missing"),
                                        "a",
                                        List.of(lead))
                                .block());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.adoptTeam("alice", a, "a", List.of()).block());
        service.adoptTeam("alice", a, "a", List.of(lead)).block();
        MemberSession wrongRole =
                new MemberSession(
                        "worker",
                        "alice",
                        "worker-agent",
                        Role.LEADER,
                        member(states, "alice", "worker", "worker", "worker-agent").session());
        assertThrows(
                TeamConflictException.class,
                () -> service.bindMember("alice", a, wrongRole).block());
        assertNull(service.findMembership(wrongRole.session()).block());
        assertThrows(
                TeamConflictException.class,
                () ->
                        client.createTeam(
                                        new TeamCreateSpec(
                                                "a", "ns-a", "", "leader-agent", "", List.of()))
                                .block());
    }

    @Test
    void malformedRelationshipItemDoesNotBecomeAnEmptyWritableRegistry() {
        MemberSession lead = member(states, "alice", "lead", "one", "leader-agent");
        service.adoptTeam("alice", a, "a", List.of(lead)).block();
        List<String> ns = List.of("team-session-membership", "YXBw");
        String key = store.search(ns, 1, 0).get(0).key();
        store.put(ns, key, Map.of("schema", 99, "receipts", Map.of(), "memberships", List.of()));
        long version = store.get(ns, key).version();
        assertThrows(
                IllegalStateException.class, () -> service.findMembership(lead.session()).block());
        assertThrows(
                IllegalStateException.class, () -> service.bindMember("alice", a, lead).block());
        assertEquals(version, store.get(ns, key).version());
    }

    private FaultStore useFaultStore() {
        FaultStore faulty = new FaultStore();
        store = faulty;
        client = new LocalTeamClient(store);
        service = client.sessionMembership("app", Map.of("state", states));
        create(a);
        create(b);
        return faulty;
    }

    private void create(TeamAddress address) {
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

    private TeamMemberInfo legacy(String alias) {
        return client.listMembers("ns-a", "a").block().stream()
                .filter(m -> m.memberName().equals(alias))
                .findFirst()
                .orElseThrow();
    }

    private static MemberSession member(
            AgentStateStore states, String owner, String alias, String sid, String ref) {
        states.save(owner, sid, "legacy", new SavedState("original"));
        return new MemberSession(
                alias,
                "definition-owner",
                ref,
                alias.equals("lead") ? Role.LEADER : Role.WORKER,
                new SessionKey("state", owner, sid));
    }

    private static CompletableFuture<Object> attempt(Callable<Object> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.call();
                    } catch (Exception error) {
                        return error;
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> strip(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Map<?, ?> map) {
                out.put(entry.getKey(), strip((Map<String, Object>) map));
            } else if (v instanceof List<?> list) {
                List<Object> items = new ArrayList<>();
                for (Object item : list) {
                    items.add(
                            item instanceof Map<?, ?> map
                                    ? strip((Map<String, Object>) map)
                                    : item);
                }
                out.put(entry.getKey(), items);
            } else if (v != null) {
                out.put(entry.getKey(), v);
            }
        }
        return out;
    }

    private static class FaultStore extends InMemoryStore {
        boolean beforeMeta, beforeRelation, afterRelation, conflict;
        final AtomicInteger failedCas = new AtomicInteger();

        @Override
        public boolean putIfVersion(
                List<String> ns, String key, Map<String, Object> value, long version) {
            boolean relation = ns.get(0).equals("team-session-membership");
            boolean association = relation && !((Map<?, ?>) value.get("receipts")).isEmpty();
            if ((!relation && beforeMeta) || (association && beforeRelation)) {
                throw new IllegalStateException("Injected write failure");
            }
            if (relation && conflict) {
                failedCas.incrementAndGet();
                return false;
            }
            boolean committed = super.putIfVersion(ns, key, value, version);
            if (association && committed && afterRelation) {
                throw new IllegalStateException("Injected response failure after commit");
            }
            return committed;
        }
    }

    private static class RacingStore extends InMemoryStore {
        CyclicBarrier barrier;
        AtomicInteger writes;

        void arm() {
            barrier = new CyclicBarrier(2);
            writes = new AtomicInteger();
        }

        @Override
        public boolean putIfVersion(
                List<String> ns, String key, Map<String, Object> value, long version) {
            if (barrier != null
                    && ns.get(0).equals("team-session-membership")
                    && writes.incrementAndGet() <= 2) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }
            return super.putIfVersion(ns, key, value, version);
        }
    }

    /** Existing legacy state; membership must not rewrite it. */
    public record SavedState(String value) implements State {}
}
