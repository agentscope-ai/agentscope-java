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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.remote.store.VersionedBaseStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Explicit, persistent Team associations for existing Leader and BYO member Sessions.
 *
 * <p>Team metadata and declared members remain in LocalTeamClient's existing records. Only
 * associations and adoption receipts are stored here, in one CAS item per relationship domain and
 * owner. The same physical Session store must have the same domain ID on every client. Independent
 * stores must have different IDs. The application is responsible for this mapping and definition
 * access authorization; state existence is not authorization.
 *
 * <p>Session owner must equal Team owner. Unbinding does not delete state, stop runs, revoke tools or
 * unregister legacy notifications. All operations are lazy and run store I/O on boundedElastic.
 */
public final class TeamSessionMembership {

    private static final String MARKER = "sessionMembership";
    private static final int MAX_ATTEMPTS = 10;
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    private final VersionedBaseStore store;
    private final String domain;
    private final Map<String, AgentStateStore> stateStores;

    TeamSessionMembership(
            BaseStore store, String domain, Map<String, AgentStateStore> stateStores) {
        if (!(store instanceof VersionedBaseStore versioned)) {
            throw new IllegalArgumentException("Session membership requires VersionedBaseStore");
        }
        this.store = versioned;
        this.domain = text(domain, "relationship domain");
        this.stateStores = Map.copyOf(stateStores);
        this.stateStores.keySet().forEach(id -> text(id, "state store domain"));
    }

    /** Declared responsibility, independent of how a member's resources were obtained. */
    public enum Role {
        LEADER,
        WORKER
    }

    /** This capability only associates resources supplied by the application. */
    public enum Source {
        BYO
    }

    /** Adoption is active only after the relationship item contains its commit receipt. */
    public enum Status {
        PENDING,
        ACTIVE
    }

    /**
     * Existing Team storage address. Null/blank namespaces are not reinterpreted on adoption.
     * @param namespace existing namespace, preserved verbatim
     * @param teamName existing storage/routing name, not the display name
     */
    public record TeamAddress(String namespace, String teamName) {
        /** Validates an explicit existing address. */
        public TeamAddress {
            text(namespace, "namespace");
            text(teamName, "team name");
        }
    }

    /**
     * Exact Session slot in a configured state store domain. Null owner means anonymous.
     * @param stateStoreDomain stable identifier for the actual state store partition
     * @param owner nullable Session owner
     * @param sessionId Session identifier within that partition and owner
     */
    public record SessionKey(String stateStoreDomain, String owner, String sessionId) {
        /** Validates the slot, rejecting reserved anonymous aliases. */
        public SessionKey {
            text(stateStoreDomain, "state store domain");
            validOwner(owner);
            text(sessionId, "session ID");
        }
    }

    /**
     * Application-supplied existing Session and its declared member identity.
     * @param memberName alias in the existing Team member directory
     * @param definitionOwner nullable definition owner, separate from Session owner
     * @param agentRef declared Agent reference
     * @param role declared Leader or Worker role
     * @param session exact existing Session
     */
    public record MemberSession(
            String memberName,
            String definitionOwner,
            String agentRef,
            Role role,
            SessionKey session) {
        /** Validates the complete identity. */
        public MemberSession {
            text(memberName, "member name");
            validOwner(definitionOwner);
            text(agentRef, "agent reference");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(session, "session");
        }
    }

    /**
     * Immutable current association. Token identifies this particular binding, not authorization.
     * @param teamOwner nullable Team owner
     * @param teamId stable logical Team ID
     * @param address legacy Team address
     * @param member complete member and Session identity
     * @param source resource origin, currently BYO
     * @param token persistent expected token for safe removal
     */
    public record Membership(
            String teamOwner,
            String teamId,
            TeamAddress address,
            MemberSession member,
            Source source,
            String token) {
        /** Validates an association. */
        public Membership {
            validOwner(teamOwner);
            text(teamId, "team ID");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(source, "source");
            text(token, "binding token");
            sameOwner(teamOwner, member.session());
        }
    }

    /**
     * Team metadata from the original record and current Session associations from one authority.
     * @param teamOwner nullable Team owner
     * @param teamId stable logical ID, distinct from address and display name
     * @param address legacy storage/routing address
     * @param displayName display name, allowed to repeat across Teams
     * @param info original Team metadata; its phase is not a runtime status
     * @param status adoption commit status
     * @param memberships currently associated Sessions; empty while PENDING
     */
    public record TeamView(
            String teamOwner,
            String teamId,
            TeamAddress address,
            String displayName,
            TeamInfo info,
            Status status,
            List<Membership> memberships) {
        /** Makes the returned association list immutable. */
        public TeamView {
            memberships = List.copyOf(memberships);
        }
    }

    /**
     * Adopts an existing Team using a complete initial request with exactly one declared Leader.
     *
     * <p>The meta marker is written first; a single owner-item CAS commits all initial associations
     * and the receipt. Failure between these writes leaves an observable PENDING adoption. Retry the
     * same request to finish it. A committed request replay returns current associations, without
     * restoring subsequently unbound members. Different adoption requests cannot replace the marker.
     *
     * @param owner nullable Team owner, also the owner of every supplied Session
     * @param address existing Team address
     * @param displayName human-readable name
     * @param initialMembers existing Leader and optional declared BYO members
     * @return current Team view after commit; errors may leave a retryable PENDING marker
     */
    public Mono<TeamView> adoptTeam(
            String owner,
            TeamAddress address,
            String displayName,
            List<MemberSession> initialMembers) {
        return io(
                () -> {
                    validOwner(owner);
                    text(displayName, "display name");
                    List<MemberSession> initial = canonical(initialMembers);
                    if (initial.stream().filter(m -> m.role() == Role.LEADER).count() != 1) {
                        throw new IllegalArgumentException("Adoption requires exactly one Leader");
                    }
                    initial.forEach(m -> sameOwner(owner, m.session()));
                    Marker marker = null;
                    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                        StoreItem meta = requireMeta(address);
                        Marker existing = marker(meta);
                        if (existing != null) {
                            requireScope(existing, owner);
                            if (!existing.displayName().equals(displayName)
                                    || !existing.initial().equals(initial)) {
                                throw new TeamConflictException(
                                        "Team has a different adoption request");
                            }
                            marker = existing;
                            if (committed(readOwner(owner).state(), marker)) {
                                return view(address, meta, marker);
                            }
                            break;
                        }
                        validateInitial(owner, address, initial);
                        Marker next =
                                new Marker(
                                        1,
                                        domain,
                                        owner,
                                        UUID.randomUUID().toString(),
                                        displayName,
                                        UUID.randomUUID().toString(),
                                        initial);
                        Map<String, Object> value = new LinkedHashMap<>(meta.value());
                        value.put(MARKER, encode(next));
                        if (store.putIfVersion(teamNs(address), "meta", value, version(meta))) {
                            marker = next;
                            break;
                        }
                    }
                    if (marker == null) {
                        throw contention();
                    }
                    validateInitial(owner, address, initial);
                    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                        OwnerItem current = readOwner(owner);
                        if (committed(current.state(), marker)) {
                            return get(owner, address);
                        }
                        List<Membership> next = new ArrayList<>(current.state().memberships());
                        for (MemberSession member : initial) {
                            add(
                                    next,
                                    new Membership(
                                            owner,
                                            marker.teamId(),
                                            address,
                                            member,
                                            Source.BYO,
                                            UUID.randomUUID().toString()));
                        }
                        Map<String, String> receipts = new HashMap<>(current.state().receipts());
                        receipts.put(marker.teamId(), marker.registrationToken());
                        if (writeOwner(owner, current, receipts, next)) {
                            return get(owner, address);
                        }
                    }
                    throw contention();
                });
    }

    /**
     * Binds an existing declared member to its persisted Session. Identical requests retain token.
     * @param owner nullable Team owner
     * @param address adopted Team address
     * @param member complete declared member and existing Session
     * @return committed association, or an error on a conflicting Session, alias or Leader
     */
    public Mono<Membership> bindMember(String owner, TeamAddress address, MemberSession member) {
        return io(
                () -> {
                    Marker marker = requireActive(owner, address);
                    validateMember(owner, address, member);
                    Membership proposed =
                            new Membership(
                                    owner,
                                    marker.teamId(),
                                    address,
                                    member,
                                    Source.BYO,
                                    UUID.randomUUID().toString());
                    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                        OwnerItem current = readOwner(owner);
                        if (!committed(current.state(), marker)) {
                            throw corrupt();
                        }
                        List<Membership> next = new ArrayList<>(current.state().memberships());
                        Membership result = add(next, proposed);
                        if (result != proposed
                                || writeOwner(owner, current, current.state().receipts(), next)) {
                            return result;
                        }
                    }
                    throw contention();
                });
    }

    /**
     * Queries an adopted Team; PENDING is explicitly reported with no committed members.
     * @param owner nullable Team owner
     * @param address adopted Team address
     * @return current view, or error for an absent, unadopted or differently owned Team
     */
    public Mono<TeamView> getTeam(String owner, TeamAddress address) {
        return io(() -> get(owner, address));
    }

    /**
     * Finds the current association of a precisely identified Session, including across namespaces.
     * @param session Session identity in a configured state store domain
     * @return empty if unbound; does not require the Session still to exist
     */
    public Mono<Membership> findMembership(SessionKey session) {
        return io(
                () -> {
                    requireStateStore(session);
                    return readOwner(session.owner()).state().memberships().stream()
                            .filter(m -> m.member().session().equals(session))
                            .findFirst()
                            .orElse(null);
                });
    }

    /**
     * Lists currently associated Sessions, independent of the old declared member directory.
     * @param owner nullable Team owner
     * @param address adopted Team address
     * @return immutable list; PENDING adoption fails explicitly and can be retried
     */
    public Mono<List<Membership>> listMemberships(String owner, TeamAddress address) {
        return io(
                () -> {
                    TeamView result = get(owner, address);
                    active(result.status());
                    return result.memberships();
                });
    }

    /**
     * Removes only the expected current association. Repeated removal of an absent slot is a no-op.
     * @param owner nullable Team owner
     * @param address adopted Team address
     * @param session exact Session to unbind
     * @param expectedToken token returned by the binding being removed
     * @return true if removed, false if already absent; wrong Team or stale token fails
     */
    public Mono<Boolean> unbindMember(
            String owner, TeamAddress address, SessionKey session, String expectedToken) {
        return io(
                () -> {
                    sameOwner(owner, session);
                    text(expectedToken, "expected binding token");
                    Marker marker = requireActive(owner, address);
                    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                        OwnerItem current = readOwner(owner);
                        List<Membership> next = new ArrayList<>(current.state().memberships());
                        Membership bound =
                                next.stream()
                                        .filter(m -> m.member().session().equals(session))
                                        .findFirst()
                                        .orElse(null);
                        if (bound == null) {
                            return false;
                        }
                        if (!bound.teamId().equals(marker.teamId())
                                || !bound.address().equals(address)
                                || !bound.token().equals(expectedToken)) {
                            throw new TeamConflictException("Team or binding token does not match");
                        }
                        next.remove(bound);
                        if (writeOwner(owner, current, current.state().receipts(), next)) {
                            return true;
                        }
                    }
                    throw contention();
                });
    }

    private TeamView get(String owner, TeamAddress address) {
        StoreItem meta = requireMeta(address);
        Marker marker = marker(meta);
        if (marker == null) {
            throw new IllegalArgumentException("Team has not been adopted");
        }
        requireScope(marker, owner);
        return view(address, meta, marker);
    }

    private TeamView view(TeamAddress address, StoreItem meta, Marker marker) {
        OwnerState state = readOwner(marker.owner()).state();
        boolean committed = committed(state, marker);
        TeamInfo info =
                new TeamInfo(
                        address.teamName(),
                        address.namespace(),
                        string(meta.value().get("objective")),
                        string(meta.value().get("phase")),
                        string(meta.value().get("leadRef")));
        return new TeamView(
                marker.owner(),
                marker.teamId(),
                address,
                marker.displayName(),
                info,
                committed ? Status.ACTIVE : Status.PENDING,
                committed ? members(state, marker, address) : List.of());
    }

    private Marker requireActive(String owner, TeamAddress address) {
        Marker marker = marker(requireMeta(address));
        if (marker == null) {
            throw new IllegalArgumentException("Team has not been adopted");
        }
        requireScope(marker, owner);
        active(committed(readOwner(owner).state(), marker) ? Status.ACTIVE : Status.PENDING);
        return marker;
    }

    private void requireScope(Marker marker, String owner) {
        validOwner(owner);
        if (!marker.domain().equals(domain) || !Objects.equals(marker.owner(), owner)) {
            throw new TeamConflictException("Team belongs to another owner or relationship domain");
        }
    }

    private void validateInitial(String owner, TeamAddress address, List<MemberSession> initial) {
        Set<String> aliases = new HashSet<>();
        Set<SessionKey> sessions = new HashSet<>();
        for (MemberSession member : initial) {
            if (!aliases.add(member.memberName()) || !sessions.add(member.session())) {
                throw new IllegalArgumentException("Duplicate initial member or Session");
            }
            validateMember(owner, address, member);
        }
        for (TeamMemberInfo declared : declaredMembers(store, address)) {
            if (declared.sessionId() != null
                    && !declared.sessionId().isEmpty()
                    && initial.stream()
                            .noneMatch(
                                    m ->
                                            m.memberName().equals(declared.memberName())
                                                    && m.session()
                                                            .sessionId()
                                                            .equals(declared.sessionId()))) {
                throw new IllegalArgumentException(
                        "Supply complete identities for legacy Session bindings");
            }
        }
    }

    private void validateMember(String owner, TeamAddress address, MemberSession member) {
        sameOwner(owner, member.session());
        StoreItem item = store.get(memberNs(address), member.memberName());
        if (item == null || item.value() == null) {
            throw new IllegalArgumentException("Member is not declared in the existing Team");
        }
        TeamMemberInfo declared = TeamMemberInfo.fromMap(item.value());
        if (!declared.memberName().equals(member.memberName())
                || !declared.agentRef().equals(member.agentRef())
                || declared.isLead() != (member.role() == Role.LEADER)) {
            throw new TeamConflictException("Member definition or declared role does not match");
        }
        if (!requireStateStore(member.session()).exists(owner, member.session().sessionId())) {
            throw new IllegalArgumentException(
                    "Session has no persisted state in the configured domain");
        }
    }

    private AgentStateStore requireStateStore(SessionKey session) {
        AgentStateStore result = stateStores.get(session.stateStoreDomain());
        if (result == null) {
            throw new IllegalArgumentException("Unknown state store domain");
        }
        return result;
    }

    private StoreItem requireMeta(TeamAddress address) {
        StoreItem item = store.get(teamNs(address), "meta");
        if (item == null || item.value() == null) {
            throw new IllegalArgumentException("Existing Team not found");
        }
        version(item);
        return item;
    }

    private OwnerItem readOwner(String owner) {
        return readOwner(store, domain, owner);
    }

    private static OwnerItem readOwner(BaseStore store, String domain, String owner) {
        StoreItem item = store.get(relationNs(domain), ownerKey(owner));
        if (item == null) {
            return new OwnerItem(0, new OwnerState(1, Map.of(), List.of()));
        }
        OwnerState state = decode(item.value(), OwnerState.class);
        if (state.schema() != 1) {
            throw corrupt();
        }
        Set<SessionKey> sessions = new HashSet<>();
        Map<String, Set<String>> aliases = new HashMap<>();
        Set<String> leaders = new HashSet<>();
        Map<String, TeamAddress> addresses = new HashMap<>();
        for (Membership m : state.memberships()) {
            TeamAddress previous = addresses.putIfAbsent(m.teamId(), m.address());
            if (!Objects.equals(owner, m.teamOwner())
                    || !state.receipts().containsKey(m.teamId())
                    || !sessions.add(m.member().session())
                    || !aliases.computeIfAbsent(m.teamId(), k -> new HashSet<>())
                            .add(m.member().memberName())
                    || (m.member().role() == Role.LEADER && !leaders.add(m.teamId()))
                    || (previous != null && !previous.equals(m.address()))) {
                throw corrupt();
            }
        }
        state.receipts()
                .forEach(
                        (id, token) -> {
                            text(id, "team ID");
                            text(token, "registration token");
                        });
        return new OwnerItem(version(item), state);
    }

    private boolean writeOwner(
            String owner,
            OwnerItem current,
            Map<String, String> receipts,
            List<Membership> memberships) {
        return store.putIfVersion(
                relationNs(domain),
                ownerKey(owner),
                encode(new OwnerState(1, receipts, memberships)),
                current.version());
    }

    private static Membership add(List<Membership> memberships, Membership proposed) {
        for (Membership m : memberships) {
            if (m.member().session().equals(proposed.member().session())) {
                if (m.teamId().equals(proposed.teamId())
                        && m.address().equals(proposed.address())
                        && m.member().equals(proposed.member())) {
                    return m;
                }
                throw new TeamConflictException("Session is already associated");
            }
            if (m.teamId().equals(proposed.teamId())
                    && (m.member().memberName().equals(proposed.member().memberName())
                            || (m.member().role() == Role.LEADER
                                    && proposed.member().role() == Role.LEADER))) {
                throw new TeamConflictException("Member alias or Leader is already associated");
            }
        }
        memberships.add(proposed);
        return proposed;
    }

    private static boolean committed(OwnerState state, Marker marker) {
        String receipt = state.receipts().get(marker.teamId());
        if (receipt != null && !receipt.equals(marker.registrationToken())) {
            throw corrupt();
        }
        return receipt != null;
    }

    private static List<Membership> members(OwnerState state, Marker marker, TeamAddress address) {
        List<Membership> result =
                state.memberships().stream()
                        .filter(m -> m.teamId().equals(marker.teamId()))
                        .toList();
        if (result.stream().anyMatch(m -> !m.address().equals(address))) {
            throw corrupt();
        }
        return result;
    }

    static List<TeamMemberInfo> projectMembers(BaseStore store, String namespace, String teamName) {
        StoreItem meta = store.get(List.of("teams", namespace, teamName), "meta");
        Marker marker = marker(meta);
        if (marker == null) {
            return null;
        }
        if (!(store instanceof VersionedBaseStore)) {
            throw new IllegalStateException("Adopted Teams require VersionedBaseStore");
        }
        TeamAddress address = new TeamAddress(namespace, teamName);
        OwnerState state = readOwner(store, marker.domain(), marker.owner()).state();
        active(committed(state, marker) ? Status.ACTIVE : Status.PENDING);
        Map<String, String> sessions = new HashMap<>();
        members(state, marker, address)
                .forEach(
                        m ->
                                sessions.put(
                                        m.member().memberName(), m.member().session().sessionId()));
        return declaredMembers(store, address).stream()
                .map(
                        m ->
                                new TeamMemberInfo(
                                        m.memberName(),
                                        m.agentRef(),
                                        m.phase(),
                                        sessions.getOrDefault(m.memberName(), ""),
                                        m.deployMode(),
                                        m.isLead()))
                .toList();
    }

    private static List<TeamMemberInfo> declaredMembers(BaseStore store, TeamAddress address) {
        List<TeamMemberInfo> result = new ArrayList<>();
        for (int offset = 0; ; offset += 100) {
            List<StoreItem> page = store.search(memberNs(address), 100, offset);
            for (StoreItem item : page) {
                if (item == null || item.value() == null) {
                    throw corrupt();
                }
                result.add(TeamMemberInfo.fromMap(item.value()));
            }
            if (page.size() < 100) {
                return List.copyOf(result);
            }
        }
    }

    private static Marker marker(StoreItem meta) {
        if (meta == null || meta.value() == null || !meta.value().containsKey(MARKER)) {
            return null;
        }
        Marker marker = decode(meta.value().get(MARKER), Marker.class);
        if (marker.schema() != 1) {
            throw corrupt();
        }
        return marker;
    }

    private record Marker(
            int schema,
            String domain,
            String owner,
            String teamId,
            String displayName,
            String registrationToken,
            List<MemberSession> initial) {
        Marker {
            text(domain, "relationship domain");
            validOwner(owner);
            text(teamId, "team ID");
            text(displayName, "display name");
            text(registrationToken, "registration token");
            initial = canonical(initial);
            if (initial.stream().filter(m -> m.role() == Role.LEADER).count() != 1) {
                throw corrupt();
            }
            initial.forEach(m -> sameOwner(owner, m.session()));
        }
    }

    private record OwnerState(
            int schema, Map<String, String> receipts, List<Membership> memberships) {
        OwnerState {
            receipts = Map.copyOf(receipts);
            memberships = List.copyOf(memberships);
        }
    }

    private record OwnerItem(long version, OwnerState state) {}

    private static Map<String, Object> encode(Object value) {
        return JSON.convertValue(value, new TypeReference<>() {});
    }

    private static <T> T decode(Object value, Class<T> type) {
        try {
            return Objects.requireNonNull(JSON.convertValue(value, type));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid Session membership data", error);
        }
    }

    private static List<MemberSession> canonical(List<MemberSession> initial) {
        return List.copyOf(initial).stream()
                .sorted(Comparator.comparing(MemberSession::memberName))
                .toList();
    }

    private static List<String> teamNs(TeamAddress address) {
        return List.of("teams", address.namespace(), address.teamName());
    }

    private static List<String> memberNs(TeamAddress address) {
        return List.of("teams", address.namespace(), address.teamName(), "members");
    }

    private static List<String> relationNs(String domain) {
        return List.of("team-session-membership", encoded(domain));
    }

    private static String ownerKey(String owner) {
        return owner == null ? "anonymous" : "owner-" + encoded(owner);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and contain no control characters");
        }
        return value;
    }

    private static void validOwner(String owner) {
        if (owner != null) {
            text(owner, "owner");
            if (owner.equals("__anon__")) {
                throw new IllegalArgumentException("Reserved anonymous owner");
            }
        }
    }

    private static void sameOwner(String owner, SessionKey session) {
        validOwner(owner);
        if (!Objects.equals(owner, session.owner())) {
            throw new IllegalArgumentException("Session owner must equal Team owner");
        }
    }

    private static long version(StoreItem item) {
        if (item.version() <= 0) {
            throw new IllegalStateException("Store must return positive item versions");
        }
        return item.version();
    }

    private static void active(Status status) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException(
                    "Team adoption is PENDING; retry the same adoptTeam request");
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException("Invalid Session membership data");
    }

    private static TeamConflictException contention() {
        return new TeamConflictException("Session membership CAS retry limit exceeded");
    }

    private static <T> Mono<T> io(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
