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
package io.agentscope.durable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableTypes.CheckpointMetadata;
import io.agentscope.durable.DurableTypes.CheckpointView;
import io.agentscope.durable.DurableTypes.CommandReceipt;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.ControlKind;
import io.agentscope.durable.DurableTypes.DelegationLink;
import io.agentscope.durable.DurableTypes.DelegationParent;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.DelegationView;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.HistoryPage;
import io.agentscope.durable.DurableTypes.Interaction;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.Lifecycle;
import io.agentscope.durable.DurableTypes.ModelCallPage;
import io.agentscope.durable.DurableTypes.ModelCallView;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.RecoveryView;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.RunPage;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionPage;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.StepPage;
import io.agentscope.durable.DurableTypes.StepView;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadPage;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.ThreadView;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.ToolExecution;
import io.agentscope.durable.DurableTypes.TurnPage;
import io.agentscope.durable.DurableTypes.TurnReceipt;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import io.agentscope.durable.store.RecordObjectStore;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.store.TransactionMap;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Shared admission/ownership/commit state machine. SQL and remote access do not duplicate it.
 * A session lock covers independently stored turns, receipts, tools, events and checkpoint heads.
 */
public final class TransactionalSessionBackend implements SessionBackend {
    private final RecordStore store;
    private final ObjectStore objects;
    private final CommitPolicy policy;

    /** Local transactional policy; no network I/O. Throwing rolls back every associated fact. */
    @FunctionalInterface
    public interface CommitPolicy {
        void check(
                RecordStore.Transaction transaction,
                ThreadSpec definition,
                Lease lease,
                Commit commit);
    }

    public TransactionalSessionBackend(RecordStore store) {
        this(store, new RecordObjectStore(store));
    }

    public TransactionalSessionBackend(RecordStore store, ObjectStore objects) {
        this(store, objects, (tx, definition, lease, commit) -> {});
    }

    public TransactionalSessionBackend(
            RecordStore store, ObjectStore objects, CommitPolicy policy) {
        this.store = Objects.requireNonNull(store);
        this.objects = Objects.requireNonNull(objects);
        this.policy = Objects.requireNonNull(policy);
    }

    public record StoredContent(JsonNode inline, Reference reference) {}

    @Override
    public Optional<LocalStorage> localStorage() {
        return Optional.of(new LocalStorage(store, objects));
    }

    private JsonNode storeContent(SessionKey session, JsonNode value) {
        byte[] bytes = DurableJson.value(value).toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length > 16384
                ? DurableJson.value(
                        new StoredContent(null, objects.put(objectScope(session), bytes)))
                : DurableJson.value(new StoredContent(DurableJson.copy(value), null));
    }

    private JsonNode readContent(SessionKey session, JsonNode stored) {
        if (stored == null || stored.isNull()) return DurableJson.value(null);
        var value = DurableJson.convert(stored, StoredContent.class);
        return value.reference() == null
                ? DurableJson.copy(value.inline())
                : DurableJson.parse(
                        new String(readObject(session, value.reference()), StandardCharsets.UTF_8));
    }

    private static String objectScope(SessionKey key) {
        return DurableJson.hash(key);
    }

    @Override
    public Reference putObject(SessionKey session, byte[] bytes) {
        change(session, (s, n) -> null);
        return objects.put(objectScope(session), bytes);
    }

    @Override
    public byte[] readObject(SessionKey session, Reference reference) {
        if (!objectScope(session).equals(reference.scope()))
            throw error(DurableException.Code.INVALID, "Object belongs to another session");
        return objects.read(reference);
    }

    /** Closes the supplied record store; resource ownership is defined by its implementation. */
    @Override
    public void close() {
        store.close();
    }

    @Override
    public Capabilities capabilities() {
        var c = store.capabilities();
        return new Capabilities(
                "durable.v1",
                c.implementation(),
                c.durable(),
                c.sharedAcrossHosts(),
                true,
                true,
                true,
                true);
    }

    public static final class State {
        public int format = 14;
        public SessionView session;
        public String creationDigest;
        public long admitted;
        public long sequence;
        public long retainedAfter;
        @JsonIgnore public RecordStore.Transaction tx;
        @JsonIgnore public RecordStore.Transaction threadTx;
        @JsonIgnore public ThreadData thread;
        @JsonIgnore public Map<String, ThreadData> threads;
        @JsonIgnore public List<TransactionMap<?>> details = new ArrayList<>();
        @JsonIgnore public Map<String, TurnData> turns;
        @JsonIgnore public Map<String, Admission> admissions;
        @JsonIgnore public Map<String, String> commands;
        @JsonIgnore public TransactionMap<String> executions;
        @JsonIgnore public TransactionMap<JsonNode> messages;
        @JsonIgnore public TransactionMap<JsonNode> projectedTools;
        @JsonIgnore public TransactionMap<JsonNode> artifacts;
        @JsonIgnore public TransactionMap<JsonNode> interactions;
        @JsonIgnore public TransactionMap<JsonNode> projectedSignals;
        @JsonIgnore public Map<String, String> eventDigests;
    }

    public static final class ThreadData {
        public ThreadSpec definition;
        public Lifecycle status = Lifecycle.OPEN;
        public long admitted;
        public long settled;
        public CheckpointMetadata checkpointMetadata;
        public long checkpointPosition;
        public long journalPosition;
        public long resourcePosition;
        public String journalBranch = "initial";
        public long epoch;

        public ThreadView view() {
            return new ThreadView(definition, status);
        }
    }

    public record Admission(String digest, TurnReceipt receipt) {}

    public static final class TurnData {
        public String id;
        public String threadId;
        public String lastRunId;
        public DelegationParent parent;
        public long invocationCount;
        public JsonNode input;
        public TurnStatus status = TurnStatus.QUEUED;
        public long revision;
        public String owner;
        public long epoch;
        public long expiresAt;
        public long nextAttemptAt;
        public boolean cancel;
        public JsonNode output;
        public String error;
        public WaitCondition waiting;
        public RecoveryView recovery;
        public long failedEpoch;
        @JsonIgnore public Map<String, ToolData> tools = new LinkedHashMap<>();
        @JsonIgnore public Map<String, Signal> signals = new LinkedHashMap<>();
        public List<String> consumed = new ArrayList<>();
        public List<String> withdrawn = new ArrayList<>();
        @JsonIgnore public Map<String, CommitReceipt> receipts = new LinkedHashMap<>();
    }

    public static final class ToolData {
        public ExecutionRequest request;
        public boolean attempted;
        public long resultRevision;
        public long sourceSequence;
        public long outputOffset;
        public boolean hasOutput;
        public boolean outputSealed;
        public ExecutionResult result =
                new ExecutionResult(ExecutionState.PENDING, null, null, null);
        public boolean applied;
        public RunStepRef appliedAt;
        public DelegationData delegation;

        public ToolExecution view() {
            return new ToolExecution(
                    request,
                    attempted,
                    result,
                    applied,
                    appliedAt,
                    delegation == null ? null : delegation.child);
        }
    }

    public static final class DelegationData {
        public DelegationTarget target;
        public DelegationLink child;
        public boolean cancelRequested;

        DelegationView view(ExecutionRequest parent) {
            return new DelegationView(parent, child, cancelRequested);
        }
    }

    private static String positionKey(long value) {
        return String.format(Locale.ROOT, "%020d", value);
    }

    private State load(RecordStore.Transaction tx) {
        State state = decode(tx.get(""));
        if (state == null) throw error(DurableException.Code.NOT_FOUND, "Session not found");
        return attach(state, tx);
    }

    private static State attach(State state, RecordStore.Transaction tx) {
        state.tx = tx;
        state.threads = new TransactionMap<>(tx, "thread:", ThreadData.class);
        state.turns = new TransactionMap<>(tx, "turn:", TurnData.class);
        state.admissions = new TransactionMap<>(tx, "admission:", Admission.class);
        state.commands = new TransactionMap<>(tx, "command:", String.class);
        state.eventDigests = new TransactionMap<>(tx, "event-id:", String.class);
        state.executions = new TransactionMap<>(tx, "execution:", String.class);
        state.messages = new TransactionMap<>(tx, "message:", JsonNode.class);
        state.projectedTools = new TransactionMap<>(tx, "tool-view:", JsonNode.class);
        state.artifacts = new TransactionMap<>(tx, "artifact:", JsonNode.class);
        state.interactions = new TransactionMap<>(tx, "interaction-view:", JsonNode.class);
        state.projectedSignals = new TransactionMap<>(tx, "signal-view:", JsonNode.class);
        return state;
    }

    private static TurnData current(State s) {
        while (s.thread.settled < s.thread.admitted) {
            var id = s.threadTx.get("queue:" + positionKey(s.thread.settled + 1));
            var turn = requireTurn(s, id.asText());
            if (!turn.status.terminal()) return turn;
            s.thread.settled++;
        }
        return null;
    }

    private static long readyAt(State s, long now) {
        TurnData turn = current(s);
        if (turn == null) return -1;
        if (turn.status == TurnStatus.PAUSED) return -1;
        if (turn.owner != null && turn.expiresAt > now) return turn.expiresAt;
        if (turn.cancel || turn.status == TurnStatus.PAUSING) return turn.nextAttemptAt;
        long ready = turn.nextAttemptAt;
        if (turn.recovery != null) {
            if (!turn.recovery.retryAllowed()) return ready;
            ready = Math.max(ready, turn.recovery.retryAt());
        }
        if (!turn.cancel
                && turn.status != TurnStatus.PAUSING
                && turn.waiting != null
                && turn.signals.values().stream()
                        .noneMatch(
                                v ->
                                        !turn.consumed.contains(v.id())
                                                && !turn.withdrawn.contains(v.id())
                                                && v.key().equals(turn.waiting.key()))) {
            return turn.waiting.deadline() == 0 ? -1 : Math.max(ready, turn.waiting.deadline());
        }
        return ready;
    }

    private <T> T change(SessionKey key, BiFunction<State, Long, T> action) {
        return store.transaction(
                key.storageKey(),
                tx -> {
                    State state = load(tx);
                    if (!key.equals(state.session.key()))
                        throw error(DurableException.Code.CONFLICT, "Session key mismatch");
                    T result = action.apply(state, tx.now());
                    save(state);
                    return result;
                });
    }

    private static void save(State state) {
        var tx = state.tx;
        if (state.thread != null) {
            var definition = state.thread.definition;
            long next = state.thread.status == Lifecycle.OPEN ? readyAt(state, tx.now()) : -1;
            tx.schedule(
                    readyQueue(HarnessSelector.all(definition.harness())),
                    definition.key().threadId(),
                    next,
                    DurableJson.value(definition));
            tx.schedule(
                    readyQueue(HarnessSelector.exact(definition)),
                    definition.key().threadId(),
                    next,
                    DurableJson.value(definition));
        }
        for (var map :
                List.of(
                        state.threads,
                        state.turns,
                        state.admissions,
                        state.commands,
                        state.eventDigests,
                        state.executions,
                        state.messages,
                        state.projectedTools,
                        state.artifacts,
                        state.interactions,
                        state.projectedSignals)) ((TransactionMap<?>) map).flush();
        state.details.forEach(TransactionMap::flush);
        tx.put("", DurableJson.value(state));
    }

    private <T> T change(ThreadKey key, BiFunction<State, Long, T> action) {
        return change(key, null, action);
    }

    private <T> T change(ThreadKey key, ThreadSpec initial, BiFunction<State, Long, T> action) {
        return change(
                key.session(),
                (s, now) -> {
                    selectThread(s, key, initial);
                    return action.apply(s, now);
                });
    }

    private static void selectThread(State s, ThreadKey key, ThreadSpec initial) {
        ThreadData thread = s.threads.get(key.threadId());
        boolean created = thread == null && initial != null;
        if (thread == null && initial != null) {
            requireOpen(s.session.status(), "Session");
            thread = new ThreadData();
            thread.definition = initial;
            s.threads.put(key.threadId(), thread);
        }
        if (thread == null) throw error(DurableException.Code.NOT_FOUND, "Thread not found");
        if (!key.equals(thread.definition.key()))
            throw error(DurableException.Code.CONFLICT, "Thread key mismatch");
        if (initial != null && !initial.equals(thread.definition))
            throw error(DurableException.Code.CONFLICT, "Thread binding is immutable");
        s.thread = thread;
        s.threadTx = threadTransaction(s.tx, key);
        if (created) appendThreadLifecycle(s, "created");
    }

    private static RecordStore.Transaction threadTransaction(
            RecordStore.Transaction tx, ThreadKey key) {
        String prefix = "thread-data:" + DurableJson.hash(key.threadId()) + ":";
        return new RecordStore.Transaction() {
            public long now() {
                return tx.now();
            }

            public JsonNode get(String part) {
                return tx.get(prefix + part);
            }

            public void put(String part, JsonNode value) {
                tx.put(prefix + part, value);
            }

            public List<String> keys(String part, String after, int limit) {
                return tx.keys(prefix + part, after == null ? null : prefix + after, limit).stream()
                        .map(value -> value.substring(prefix.length()))
                        .toList();
            }

            public void schedule(String queue, String workId, long readyAt, JsonNode value) {
                throw new UnsupportedOperationException("Schedule through the Session transaction");
            }
        };
    }

    private static void requireOpen(Lifecycle status, String kind) {
        if (status != Lifecycle.OPEN)
            throw error(DurableException.Code.CONFLICT, kind + " is closed");
    }

    private static State decode(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.path("format").asInt() != 14)
            throw error(DurableException.Code.INCOMPATIBLE, "Unsupported backend state version");
        return DurableJson.convert(value, State.class);
    }

    @Override
    public SessionView create(SessionSpec specification) {
        Objects.requireNonNull(specification);
        String digest = DurableJson.hash(specification);
        return store.transaction(
                specification.key().storageKey(),
                tx -> {
                    State existing = decode(tx.get(""));
                    if (existing != null) {
                        equal(
                                existing.creationDigest,
                                digest,
                                "Session creation specification changed");
                        return existing.session;
                    }
                    State state = attach(new State(), tx);
                    state.session =
                            new SessionView(
                                    specification.key(),
                                    "main",
                                    Lifecycle.OPEN,
                                    DurableJson.copy(specification.metadata()));
                    state.creationDigest = digest;
                    ThreadData primary = new ThreadData();
                    primary.definition =
                            new ThreadSpec(
                                    new ThreadKey(specification.key(), "main"),
                                    specification.primaryBinding());
                    state.threads.put("main", primary);
                    appendSessionLifecycle(state, "created");
                    state.thread = primary;
                    state.threadTx = threadTransaction(tx, primary.definition.key());
                    appendThreadLifecycle(state, "created");
                    save(state);
                    return state.session;
                });
    }

    @Override
    public ThreadView createThread(ThreadSpec specification) {
        Objects.requireNonNull(specification);
        return change(specification.key(), specification, (s, n) -> s.thread.view());
    }

    @Override
    public ThreadView thread(ThreadKey key) {
        return change(key, (s, n) -> s.thread.view());
    }

    @Override
    public ThreadPage threads(SessionKey key, String after, int count) {
        limit(count);
        return change(
                key,
                (s, n) -> {
                    String scope = key.storageKey() + ":threads";
                    var keys = s.tx.keys("thread:", pagePosition(after, scope), count);
                    return new ThreadPage(
                            keys.stream()
                                    .map(
                                            k ->
                                                    DurableJson.convert(
                                                                    s.tx.get(k).path("value"),
                                                                    ThreadData.class)
                                                            .view())
                                    .toList(),
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    @Override
    public ThreadView closeThread(ThreadKey key) {
        return change(
                key,
                (s, n) -> {
                    if (s.thread.status == Lifecycle.CLOSED) return s.thread.view();
                    if (current(s) != null)
                        throw error(DurableException.Code.CONFLICT, "Thread has unfinished Turns");
                    s.thread.status = Lifecycle.CLOSED;
                    appendThreadLifecycle(s, "closed");
                    return s.thread.view();
                });
    }

    @Override
    public SessionView closeSession(SessionKey key) {
        return change(
                key,
                (s, n) -> {
                    if (s.session.status() == Lifecycle.CLOSED) return s.session;
                    if (s.turns.values().stream().anyMatch(turn -> !turn.status.terminal()))
                        throw error(DurableException.Code.CONFLICT, "Session has unfinished Turns");
                    s.session =
                            new SessionView(
                                    key,
                                    s.session.primaryThreadId(),
                                    Lifecycle.CLOSED,
                                    s.session.metadata());
                    appendSessionLifecycle(s, "closed");
                    return s.session;
                });
    }

    @Override
    public TurnReceipt submit(ThreadKey key, String requestId, JsonNode input) {
        return change(key, (state, now) -> submit(state, key, requestId, input));
    }

    private static TurnReceipt submit(
            State state, ThreadKey key, String requestId, JsonNode input) {
        DurableJson.text(requestId, "requestId");
        JsonNode frozen = DurableJson.copy(input);
        String digest = DurableJson.hash(List.of(key, frozen));
        String id = "turn_" + UUID.randomUUID();
        if (state.commands.containsKey("control:" + requestId))
            throw error(
                    DurableException.Code.CONFLICT,
                    "Request identity belongs to a control operation");
        Admission prior = state.admissions.get(requestId);
        if (prior != null) {
            equal(prior.digest(), digest, "Request content changed");
            return prior.receipt();
        }
        requireOpen(state.session.status(), "Session");
        requireOpen(state.thread.status, "Thread");
        TurnData turn = new TurnData();
        turn.id = id;
        turn.threadId = key.threadId();
        turn.input = frozen;
        // Order new admissions by storage time, not lexical Session/Thread identity.
        turn.nextAttemptAt = state.tx.now();
        state.turns.put(id, turn);
        state.threadTx.put("queue:" + positionKey(++state.thread.admitted), DurableJson.value(id));
        state.tx.put("turn-order:" + positionKey(++state.admitted), DurableJson.value(id));
        TurnReceipt receipt = new TurnReceipt(key, requestId, id);
        state.admissions.put(requestId, new Admission(digest, receipt));
        append(
                state,
                turn,
                new EventInput(
                        "admitted:" + id,
                        "turn.queued",
                        DurableJson.value(Map.of("requestId", requestId))));
        if (frozen.has("schemaVersion") && frozen.has("content")) {
            DurableProtocol.validate("input", frozen);
            inputMessage(state, turn, requestId, "submit", frozen);
        }
        return receipt;
    }

    @Override
    public List<SessionView> sessions(String after, int limit) {
        limit(limit);
        var definitions = new ArrayList<SessionView>();
        for (String key : store.keys("session:", after, limit))
            store.transact(
                    key,
                    row -> {
                        State state = decode(row.value());
                        if (state != null) definitions.add(state.session);
                        return new RecordStore.Update<>(row.value(), null);
                    });
        return List.copyOf(definitions);
    }

    @Override
    public SessionView session(SessionKey key) {
        return change(key, (s, n) -> s.session);
    }

    @Override
    public SessionPage listSessions(String namespace, String after, int count) {
        DurableJson.text(namespace, "namespace");
        limit(count);
        String prefix = "session:" + DurableJson.hash(namespace) + ":";
        String position = pagePosition(after, prefix);
        var keys = store.keys(prefix, position, count);
        var result = new ArrayList<SessionView>();
        for (String key : keys)
            store.transaction(
                    key,
                    tx -> {
                        var state = decode(tx.get(""));
                        if (state != null && namespace.equals(state.session.key().namespace()))
                            result.add(state.session);
                        return null;
                    });
        return new SessionPage(
                List.copyOf(result),
                keys.size() == count ? pageCursor(prefix, keys.get(keys.size() - 1)) : null);
    }

    @Override
    public TurnPage turns(ThreadKey key, String after, int count) {
        limit(count);
        return change(
                key,
                (s, n) -> {
                    String scope = key.storageKey() + ":turns";
                    var keys = s.threadTx.keys("queue:", pagePosition(after, scope), count);
                    var items =
                            keys.stream()
                                    .map(k -> view(s, requireTurn(s, s.threadTx.get(k).asText())))
                                    .toList();
                    return new TurnPage(
                            items,
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    @Override
    public HistoryPage history(ThreadKey key, String after, int count) {
        limit(count);
        return change(
                key,
                (s, n) -> {
                    String scope = key.storageKey() + ":history";
                    var keys = s.threadTx.keys("message-order:", pagePosition(after, scope), count);
                    var items =
                            keys.stream()
                                    .map(
                                            k ->
                                                    DurableJson.copy(
                                                            s.messages.get(
                                                                    s.threadTx.get(k).asText())))
                                    .toList();
                    return new HistoryPage(
                            items,
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    private static String pageCursor(String scope, String position) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        DurableJson.value(List.of(scope, position))
                                .toString()
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static String pagePosition(String cursor, String scope) {
        if (cursor == null) return null;
        try {
            var decoded =
                    DurableJson.parse(
                            new String(
                                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (!decoded.isArray()
                    || decoded.size() != 2
                    || !scope.equals(decoded.get(0).asText())
                    || !decoded.get(1).isTextual()) throw new IllegalArgumentException();
            return decoded.get(1).asText();
        } catch (RuntimeException invalid) {
            throw error(
                    DurableException.Code.INVALID,
                    "Pagination cursor belongs to another query or is malformed");
        }
    }

    @Override
    public List<Interaction> interactions(ThreadKey key, String turnId) {
        return change(
                key,
                (s, n) -> {
                    requireTurn(s, turnId);
                    return s.interactions.values().stream()
                            .filter(
                                    v ->
                                            turnId.equals(v.path("turnId").asText())
                                                    && "pending".equals(v.path("status").asText()))
                            .map(v -> DurableJson.convert(v, Interaction.class))
                            .toList();
                });
    }

    @Override
    public CommandReceipt control(ThreadKey key, ControlCommand command) {
        DurableProtocol.validate("controlCommand", command);
        return change(
                key,
                (s, n) -> {
                    if (s.admissions.containsKey(command.requestId()))
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Request identity belongs to a submission");
                    String identity = "control:" + command.requestId();
                    String digest = DurableJson.hash(List.of(key, command));
                    String receiptKey = "control-receipt:" + DurableJson.hash(command.requestId());
                    if (s.commands.containsKey(identity)) {
                        equal(s.commands.get(identity), digest, "Command identity/content changed");
                        return DurableJson.convert(s.tx.get(receiptKey), CommandReceipt.class);
                    }
                    TurnData turn = requireTurn(s, command.turnId());
                    if (command.kind() == ControlKind.CANCEL) {
                        cancel(s, turn.id, command.requestId());
                    } else {
                        if (turn.status.terminal() || turn.cancel)
                            throw error(
                                    DurableException.Code.CONFLICT,
                                    "Turn is terminal or cancelling");
                        switch (command.kind()) {
                            case STEER, INJECT -> {
                                if (turn.epoch == 0 || current(s) != turn)
                                    throw error(
                                            DurableException.Code.CONFLICT,
                                            "Input adjustment requires the active Turn");
                                String kind =
                                        command.kind() == ControlKind.STEER ? "steer" : "inject";
                                acceptSignal(
                                        s,
                                        turn,
                                        new Signal(
                                                command.requestId(),
                                                "input:" + kind,
                                                command.value()));
                                inputMessage(s, turn, command.requestId(), kind, command.value());
                            }
                            case WITHDRAW -> {
                                if (!turn.signals.containsKey(command.target()))
                                    throw error(
                                            DurableException.Code.NOT_FOUND, "Signal not found");
                                withdraw(s, turn.id, command.target(), command.requestId());
                            }
                            case RESPOND -> {
                                var interaction = s.interactions.get(command.target());
                                if (interaction == null
                                        || !turn.id.equals(interaction.path("turnId").asText()))
                                    throw error(
                                            DurableException.Code.NOT_FOUND,
                                            "Interaction not found in Turn");
                                if (!"pending".equals(interaction.path("status").asText())
                                        || interaction.path("automatic").asBoolean())
                                    throw error(
                                            DurableException.Code.CONFLICT,
                                            "Interaction is resolved or managed by Worker");
                                String kind = interaction.path("kind").asText();
                                if ("confirmation".equals(kind) && !command.value().isBoolean())
                                    throw error(
                                            DurableException.Code.INVALID,
                                            "Confirmation requires a boolean");
                                if (!Set.of("confirmation", "external_execution").contains(kind))
                                    throw error(
                                            DurableException.Code.INCOMPATIBLE,
                                            "Unsupported interaction kind");
                                String waitKey = interaction.path("key").asText();
                                if (turn.signals.values().stream()
                                        .anyMatch(
                                                v ->
                                                        waitKey.equals(v.key())
                                                                && !turn.withdrawn.contains(
                                                                        v.id())))
                                    throw error(
                                            DurableException.Code.CONFLICT,
                                            "Interaction already has a response");
                                acceptSignal(
                                        s,
                                        turn,
                                        new Signal(command.requestId(), waitKey, command.value()));
                                var input =
                                        DurableMessages.Input.text(
                                                command.value().isTextual()
                                                        ? command.value().asText()
                                                        : command.value().toString());
                                inputMessage(
                                        s,
                                        turn,
                                        command.requestId(),
                                        "respond",
                                        DurableJson.value(input),
                                        command.target());
                            }
                            case PAUSE -> pause(s, turn, command.requestId(), n);
                            case RESUME -> resume(s, turn, command, n);
                            case SIGNAL -> {
                                if (!command.target().startsWith("x."))
                                    throw error(
                                            DurableException.Code.INVALID,
                                            "Extension signals require x. namespace");
                                acceptSignal(
                                        s,
                                        turn,
                                        new Signal(
                                                command.requestId(),
                                                command.target(),
                                                command.value()));
                            }
                            default ->
                                    throw error(
                                            DurableException.Code.INVALID,
                                            "Invalid control operation");
                        }
                    }
                    var receipt =
                            new CommandReceipt(
                                    command.requestId(),
                                    turn.id,
                                    command.kind().name(),
                                    "accepted",
                                    cursor(s, s.sequence));
                    s.commands.put(identity, digest);
                    s.tx.put(receiptKey, DurableJson.value(receipt));
                    return receipt;
                });
    }

    private static void inputMessage(
            State s, TurnData turn, String requestId, String kind, JsonNode input) {
        inputMessage(s, turn, requestId, kind, input, null);
    }

    private static void inputMessage(
            State s,
            TurnData turn,
            String requestId,
            String kind,
            JsonNode input,
            String interactionId) {
        String id = "input:" + DurableJson.hash(List.of(turn.id, requestId, kind));
        var content = DurableJson.convert(input, DurableMessages.Input.class);
        var message = new DurableMessages.Message("1", id, "user", content.content());
        String text =
                content.content().stream()
                        .filter(v -> v instanceof DurableMessages.Text)
                        .map(v -> ((DurableMessages.Text) v).text())
                        .collect(Collectors.joining());
        var data =
                (ObjectNode)
                        DurableJson.value(
                                Map.of(
                                        "messageId",
                                        id,
                                        "attempt",
                                        0,
                                        "revision",
                                        0,
                                        "text",
                                        text,
                                        "message",
                                        message,
                                        "requestId",
                                        requestId,
                                        "inputKind",
                                        kind));
        if (interactionId != null) data.put("interactionId", interactionId);
        append(s, turn, new EventInput(id, "message.completed", data));
    }

    @Override
    public Optional<TurnReceipt> admission(SessionKey session, String requestId) {
        return change(
                session,
                (state, now) ->
                        Optional.ofNullable(state.admissions.get(requestId))
                                .map(Admission::receipt));
    }

    @Override
    public TurnView turn(ThreadKey key, String id) {
        return change(key, (s, n) -> view(s, requireTurn(s, id)));
    }

    private static InvocationRecords invocations(State state, TurnData turn) {
        return new InvocationRecords(
                state.threadTx,
                state.thread.definition.key(),
                turn,
                event -> append(state, turn, event));
    }

    @Override
    public RunView prepareRun(Lease lease, String requestId) {
        return change(
                lease.thread(),
                (s, now) -> invocations(s, owned(s, lease, now)).prepare(requestId, now));
    }

    @Override
    public RunView run(ThreadKey key, String turnId, String runId) {
        return change(key, (s, now) -> invocations(s, requireTurn(s, turnId)).run(runId));
    }

    @Override
    public RunPage runs(ThreadKey key, String turnId, String after, int count) {
        limit(count);
        return change(
                key,
                (s, now) -> {
                    var records = invocations(s, requireTurn(s, turnId));
                    String scope = key.storageKey() + ":runs:" + turnId;
                    var keys =
                            s.threadTx.keys(records.runOrder(), pagePosition(after, scope), count);
                    return new RunPage(
                            keys.stream()
                                    .map(k -> records.run(s.threadTx.get(k).asText()))
                                    .toList(),
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    @Override
    public StepView step(ThreadKey key, String turnId, String runId, String stepId) {
        return change(key, (s, now) -> invocations(s, requireTurn(s, turnId)).step(runId, stepId));
    }

    @Override
    public StepPage steps(ThreadKey key, String turnId, String runId, String after, int count) {
        limit(count);
        return change(
                key,
                (s, now) -> {
                    var records = invocations(s, requireTurn(s, turnId));
                    String scope = key.storageKey() + ":steps:" + turnId + ":" + runId;
                    var keys =
                            s.threadTx.keys(
                                    records.stepOrder(runId), pagePosition(after, scope), count);
                    return new StepPage(
                            keys.stream()
                                    .map(k -> records.step(runId, s.threadTx.get(k).asText()))
                                    .toList(),
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    @Override
    public ModelCallView modelCall(ThreadKey key, String turnId, String runId, String modelCallId) {
        DurableJson.text(modelCallId, "modelCallId");
        return change(
                key,
                (s, now) -> invocations(s, requireTurn(s, turnId)).modelCall(runId, modelCallId));
    }

    @Override
    public ModelCallPage modelCalls(
            ThreadKey key, String turnId, String runId, String stepId, String after, int count) {
        limit(count);
        return change(
                key,
                (s, now) -> {
                    var records = invocations(s, requireTurn(s, turnId));
                    String order = records.modelOrder(runId, stepId);
                    String scope = key.storageKey() + ":models:" + turnId + ":" + order;
                    var keys = s.threadTx.keys(order, pagePosition(after, scope), count);
                    return new ModelCallPage(
                            keys.stream()
                                    .map(k -> records.modelCall(runId, s.threadTx.get(k).asText()))
                                    .toList(),
                            keys.size() == count
                                    ? pageCursor(scope, keys.get(keys.size() - 1))
                                    : null);
                });
    }

    @Override
    public Optional<Lease> claim(ThreadKey key, String owner, Duration duration) {
        DurableJson.text(owner, "owner");
        long ttl = positive(duration);
        return change(
                key,
                (s, now) -> {
                    TurnData turn = current(s);
                    if (turn == null) return Optional.empty();
                    if (Objects.equals(turn.owner, owner) && turn.expiresAt > now)
                        return Optional.of(
                                new Lease(key, turn.id, owner, turn.epoch, turn.expiresAt));
                    long ready = readyAt(s, now);
                    if (ready < 0 || ready > now) return Optional.empty();
                    invocations(s, turn).abandon(now, true);
                    turn.owner = owner;
                    turn.epoch = ++s.thread.epoch;
                    turn.expiresAt = Math.addExact(now, ttl);
                    if (turn.status == TurnStatus.QUEUED) {
                        turn.status = TurnStatus.RUNNING;
                        append(
                                s,
                                turn,
                                new EventInput(
                                        "started:" + turn.id,
                                        "turn.started",
                                        DurableJson.value(Map.of())));
                    }
                    return Optional.of(new Lease(key, turn.id, owner, turn.epoch, turn.expiresAt));
                });
    }

    @Override
    public void renew(Lease lease, Duration duration) {
        long ttl = positive(duration);
        change(
                lease.thread(),
                (s, n) -> {
                    owned(s, lease, n).expiresAt = Math.addExact(n, ttl);
                    return null;
                });
    }

    @Override
    public void release(Lease lease, Duration delay) {
        if (delay.isNegative()) throw error(DurableException.Code.INVALID, "Negative delay");
        change(
                lease.thread(),
                (s, n) -> {
                    TurnData r = owned(s, lease, n);
                    if (r.status == TurnStatus.PAUSING) {
                        completePause(s, r, n, false);
                        return null;
                    }
                    invocations(s, r).abandon(n, false);
                    r.owner = null;
                    r.expiresAt = 0;
                    r.nextAttemptAt = Math.max(r.nextAttemptAt, Math.addExact(n, delay.toMillis()));
                    return null;
                });
    }

    public record FailureReceipt(String digest, RecoveryView recovery) {}

    @Override
    public RecoveryView recordFailure(Lease lease, Failure failure) {
        DurableProtocol.validate("failure", failure);
        String digest = DurableJson.hash(List.of(lease, failure));
        return change(
                lease.thread(),
                (s, now) -> {
                    TurnData turn = requireTurn(s, lease.turnId());
                    String key = "failure:" + DurableJson.hash(List.of(turn.id, lease.epoch()));
                    var stored = s.threadTx.get(key);
                    if (stored != null) {
                        var receipt = DurableJson.convert(stored, FailureReceipt.class);
                        equal(receipt.digest(), digest, "Failure report changed");
                        return receipt.recovery();
                    }
                    owned(s, lease, now);
                    var previous = turn.recovery;
                    turn.failedEpoch = lease.epoch();
                    if (previous == null || previous.retryAllowed()) {
                        var selected = previous == null ? failure.policy() : previous.policy();
                        int count = previous == null ? 1 : previous.failures() + 1;
                        boolean retry = failure.retryable() && count <= selected.maxRetries();
                        turn.recovery =
                                new RecoveryView(
                                        count,
                                        selected,
                                        failure.retryable(),
                                        retry ? Math.addExact(now, selected.delay(count)) : null,
                                        failure.message());
                        turn.error = failure.message();
                        turn.revision++;
                        invocations(s, turn).fail(now);
                        append(
                                s,
                                turn,
                                new EventInput(
                                        "recovery:" + turn.id + ":" + count,
                                        "turn.recovery",
                                        DurableJson.value(turn.recovery)));
                    }
                    if (turn.status == TurnStatus.PAUSING && !turn.cancel) {
                        completePause(s, turn, now, false);
                    } else if (!turn.cancel) {
                        turn.status = TurnStatus.WAITING;
                        // A failed invocation may still have issued tools. Only reconciliation can
                        // settle
                        // those effects; exhausting the retry budget never establishes their
                        // outcome.
                        append(
                                s,
                                turn,
                                new EventInput(
                                        "failure-wait:" + turn.id + ":" + lease.epoch(),
                                        "turn.waiting",
                                        lifecycle(turn)));
                    }
                    // On exhausted recovery, failed reconciliation also backs off without granting
                    // a new
                    // invocation budget. Signals and results cannot reset the persisted retry
                    // counter.
                    turn.nextAttemptAt =
                            turn.cancel
                                    ? 0
                                    : turn.recovery.retryAllowed()
                                            ? turn.recovery.retryAt()
                                            : previous != null && !previous.retryAllowed()
                                                    ? Math.addExact(
                                                            now, previous.policy().maxDelayMillis())
                                                    : now;
                    s.threadTx.put(
                            key, DurableJson.value(new FailureReceipt(digest, turn.recovery)));
                    return turn.recovery;
                });
    }

    @Override
    public Frame frame(Lease lease) {
        Frame stored =
                change(
                        lease.thread(),
                        (s, n) -> {
                            TurnData r = owned(s, lease, n);
                            return new Frame(
                                    s.thread.definition,
                                    view(s, r),
                                    DurableJson.copy(r.input),
                                    DurableJson.copy(s.threadTx.get("checkpoint")),
                                    s.thread.checkpointMetadata,
                                    r.tools.values().stream().map(ToolData::view).toList(),
                                    r.signals.values().stream()
                                            .filter(
                                                    x ->
                                                            !r.consumed.contains(x.id())
                                                                    && !r.withdrawn.contains(
                                                                            x.id()))
                                            .toList(),
                                    n);
                        });
        return new Frame(
                stored.definition(),
                stored.turn(),
                stored.input(),
                readContent(lease.session(), stored.checkpoint()),
                stored.checkpointMetadata(),
                stored.tools(),
                stored.signals(),
                stored.now());
    }

    @Override
    public CommitReceipt commit(Lease lease, Commit command) {
        DurableProtocol.validate("commit", command);
        String digest = DurableJson.hash(command);
        // Immutable uploads finish before taking the Session transaction lock.
        JsonNode checkpoint = storeContent(lease.session(), command.checkpoint());
        List<JsonNode> journal =
                command.journal() == null
                        ? List.of()
                        : command.journal().entries().stream()
                                .map(entry -> storeContent(lease.session(), entry))
                                .toList();
        return change(
                lease.thread(),
                (s, now) -> {
                    TurnData r = requireTurn(s, lease.turnId());
                    CommitReceipt old = r.receipts.get(command.commitId());
                    if (old != null) {
                        equal(old.digest(), digest, "Commit content changed");
                        return old;
                    }
                    owned(s, lease, now);
                    policy.check(s.tx, s.thread.definition, lease, command);
                    if (command.journal() != null) {
                        var append = command.journal();
                        if (append.expectedPosition() != s.thread.journalPosition)
                            throw error(
                                    DurableException.Code.CONFLICT,
                                    "Native journal position changed");
                        for (var entry : journal)
                            s.threadTx.put(
                                    "journal:"
                                            + s.thread.journalBranch
                                            + ":"
                                            + positionKey(++s.thread.journalPosition),
                                    entry);
                    }
                    if (r.revision != command.expectedRevision())
                        throw error(DurableException.Code.CONFLICT, "Revision changed");
                    var effectPositions = new ArrayList<RunStepRef>();
                    for (var call : command.tools())
                        if (!r.tools.containsKey(executionId(lease.thread(), r.id, call.callId())))
                            effectPositions.add(call.origin());
                    for (var application : command.appliedResults())
                        if (!requireTool(r, application.executionId()).applied)
                            effectPositions.add(application.appliedAt());
                    invocations(s, r)
                            .commit(
                                    command.run(),
                                    command.steps(),
                                    command.events().stream()
                                            .filter(
                                                    event ->
                                                            event.type().startsWith("model.")
                                                                    && !s.eventDigests.containsKey(
                                                                            event.eventId()))
                                            .distinct()
                                            .toList(),
                                    effectPositions,
                                    now);
                    if (!command.appliedResults().isEmpty() && command.checkpoint().isNull())
                        throw error(
                                DurableException.Code.INVALID,
                                "Result application requires a native checkpoint");
                    for (ToolCall call : command.tools()) {
                        String id = executionId(lease.thread(), r.id, call.callId());
                        ToolData previous = r.tools.get(id);
                        if (previous != null) {
                            equal(
                                    DurableJson.hash(previous.request.call()),
                                    DurableJson.hash(call),
                                    "Tool intent changed");
                            continue;
                        }
                        if (r.cancel)
                            throw error(
                                    DurableException.Code.CONFLICT,
                                    "Cannot add tools after cancellation");
                        ToolData tool = new ToolData();
                        tool.request = new ExecutionRequest(id, lease.thread(), r.id, call);
                        r.tools.put(id, tool);
                        s.executions.put(id, r.id);
                        append(
                                s,
                                r,
                                toolEvent(
                                        "requested:" + id,
                                        "tool.requested",
                                        DurableJson.value(tool.request),
                                        call.origin()));
                    }
                    for (var application : command.appliedResults()) {
                        String id = application.executionId();
                        ToolData tool = requireTool(r, id);
                        if (!tool.result.state().terminal())
                            throw error(
                                    DurableException.Code.INVALID,
                                    "Unsettled result cannot be applied");
                        if (tool.applied
                                && !Objects.equals(tool.appliedAt, application.appliedAt()))
                            throw error(
                                    DurableException.Code.CONFLICT,
                                    "Result application position changed");
                        if (!tool.applied) {
                            tool.applied = true;
                            tool.appliedAt = application.appliedAt();
                            append(
                                    s,
                                    r,
                                    toolEvent(
                                            "applied:" + id,
                                            "tool.result.applied",
                                            DurableJson.value(
                                                    Map.of(
                                                            "executionId",
                                                            id,
                                                            "callId",
                                                            tool.request.call().callId(),
                                                            "appliedAt",
                                                            DurableJson.value(
                                                                    application.appliedAt()))),
                                            application.appliedAt()));
                        }
                    }
                    for (String id : command.consumedSignals()) {
                        if (r.withdrawn.contains(id))
                            throw error(DurableException.Code.CONFLICT, "Input was withdrawn");
                        if (!r.signals.containsKey(id))
                            throw error(DurableException.Code.INVALID, "Unknown signal");
                        if (!r.consumed.contains(id)) {
                            r.consumed.add(id);
                            append(
                                    s,
                                    r,
                                    new EventInput(
                                            "signal-applied:" + r.id + ":" + id,
                                            "signal.applied",
                                            DurableJson.value(
                                                    Map.of(
                                                            "id",
                                                            DurableJson.hash(List.of(r.id, id)),
                                                            "turnId",
                                                            r.id,
                                                            "signalId",
                                                            id,
                                                            "status",
                                                            "applied"))));
                        }
                    }
                    TurnStatus next = command.status();
                    if (r.failedEpoch == lease.epoch()
                            && (!command.tools().isEmpty() || next == TurnStatus.SUCCEEDED))
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Failed ownership must be released before another invocation");
                    if (next == TurnStatus.SUCCEEDED
                            && r.recovery != null
                            && !r.recovery.retryAllowed()
                            && !r.cancel)
                        throw error(DurableException.Code.CONFLICT, "Recovery has stopped");
                    if (next == TurnStatus.PAUSED && r.status != TurnStatus.PAUSING && !r.cancel)
                        throw error(DurableException.Code.CONFLICT, "Pause was not requested");
                    if (r.status == TurnStatus.PAUSING && !r.cancel) {
                        if (next.terminal())
                            throw error(
                                    DurableException.Code.CONFLICT, "Pause must be acknowledged");
                        next = next == TurnStatus.RUNNING ? TurnStatus.PAUSING : TurnStatus.PAUSED;
                    }
                    if (r.recovery != null
                            && !r.recovery.retryAllowed()
                            && !r.cancel
                            && r.status != TurnStatus.PAUSING
                            && !next.terminal()) next = TurnStatus.WAITING;
                    if (next == TurnStatus.SUCCEEDED
                            && !r.cancel
                            && r.signals.values().stream()
                                    .anyMatch(
                                            signal ->
                                                    Set.of("input:steer", "input:inject")
                                                                    .contains(signal.key())
                                                            && !r.consumed.contains(signal.id())
                                                            && !r.withdrawn.contains(signal.id())))
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Accepted input must be applied before task completion");
                    boolean unsettled =
                            r.tools.values().stream().anyMatch(t -> !t.result.state().terminal());
                    if (next.terminal() && unsettled)
                        throw error(
                                DurableException.Code.INVALID,
                                "External operations are not settled");
                    if (next == TurnStatus.SUCCEEDED
                            && r.tools.values().stream().anyMatch(t -> !t.applied))
                        throw error(DurableException.Code.INVALID, "Results have not been applied");
                    if (next == TurnStatus.WAITING
                            && command.waiting() == null
                            && r.recovery == null
                            && r.tools.values().stream().noneMatch(t -> !t.applied))
                        throw error(
                                DurableException.Code.INVALID,
                                "Waiting requires a condition or unapplied tool");
                    if (s.thread.checkpointMetadata != null && command.checkpoint().isNull())
                        throw error(DurableException.Code.INVALID, "Cannot discard native state");
                    if (!command.checkpoint().isNull()
                            && (s.thread.checkpointMetadata == null
                                    || checkpointRecord(s, s.thread.checkpointPosition)
                                                    .resourcePosition()
                                            != s.thread.resourcePosition
                                    || !s.thread
                                            .checkpointMetadata
                                            .digest()
                                            .equals(DurableJson.hash(command.checkpoint())))) {
                        s.thread.checkpointMetadata =
                                new CheckpointMetadata(
                                        "1",
                                        s.thread.definition.harness(),
                                        s.thread.definition.definitionVersion(),
                                        s.thread.definition.stateVersion(),
                                        Long.toString(++s.thread.checkpointPosition),
                                        DurableJson.hash(command.checkpoint()));
                        s.threadTx.put("checkpoint", checkpoint);
                        s.threadTx.put(
                                "checkpoint-history:" + positionKey(s.thread.checkpointPosition),
                                DurableJson.value(
                                        new CheckpointRecord(
                                                s.thread.checkpointMetadata,
                                                checkpoint,
                                                s.thread.journalBranch,
                                                s.thread.journalPosition,
                                                s.thread.resourcePosition,
                                                command.status().terminal())));
                    }
                    TurnStatus previousStatus = r.status;
                    r.revision++;
                    r.waiting = command.waiting();
                    r.output = DurableJson.copy(command.output());
                    r.error = command.error();
                    if (r.cancel)
                        next = next.terminal() ? TurnStatus.CANCELLED : TurnStatus.CANCELLING;
                    r.status = next;
                    for (EventInput event : command.events()) {
                        if (event.type().startsWith("session.")
                                || event.type().startsWith("thread."))
                            throw error(
                                    DurableException.Code.INVALID,
                                    "Resource events belong to lifecycle operations");
                        if (event.type().startsWith("run.") || event.type().startsWith("step."))
                            throw error(
                                    DurableException.Code.INVALID,
                                    "Execution events require structured facts");
                        append(s, r, event);
                    }
                    if (previousStatus == TurnStatus.WAITING && next == TurnStatus.RUNNING)
                        append(
                                s,
                                r,
                                new EventInput(
                                        "resumed:" + r.id + ":" + r.revision,
                                        "turn.resumed",
                                        DurableJson.value(
                                                Map.of(
                                                        "revision",
                                                        r.revision,
                                                        "attempt",
                                                        r.epoch))));
                    if (next.terminal() && s.thread.checkpointMetadata != null) {
                        var record = checkpointRecord(s, s.thread.checkpointPosition);
                        s.threadTx.put(
                                "checkpoint-history:" + positionKey(s.thread.checkpointPosition),
                                DurableJson.value(
                                        new CheckpointRecord(
                                                record.metadata(),
                                                record.content(),
                                                record.branch(),
                                                record.journalPosition(),
                                                record.resourcePosition(),
                                                true)));
                    }
                    if (next.terminal()
                            || next == TurnStatus.WAITING
                            || next == TurnStatus.PAUSED) {
                        invocations(s, r).abandon(now, false);
                        r.owner = null;
                        r.expiresAt = 0;
                        append(
                                s,
                                r,
                                new EventInput(
                                        "state:" + r.id + ":" + r.revision,
                                        "turn." + next.name().toLowerCase(Locale.ROOT),
                                        lifecycle(r)));
                    }
                    CommitReceipt receipt =
                            new CommitReceipt(
                                    command.commitId(), digest, r.revision, cursor(s, s.sequence));
                    r.receipts.put(command.commitId(), receipt);
                    return receipt;
                });
    }

    @Override
    public List<JsonNode> journal(Lease lease, long after, int limit) {
        limit(limit);
        List<JsonNode> stored =
                change(
                        lease.thread(),
                        (s, now) -> {
                            owned(s, lease, now);
                            if (after < 0 || after > s.thread.journalPosition)
                                throw error(
                                        DurableException.Code.INVALID,
                                        "Invalid native journal position");
                            var entries = new ArrayList<JsonNode>();
                            for (long i = after + 1;
                                    i <= s.thread.journalPosition && entries.size() < limit;
                                    i++)
                                entries.add(
                                        s.threadTx.get(
                                                "journal:"
                                                        + s.thread.journalBranch
                                                        + ":"
                                                        + positionKey(i)));
                            return List.copyOf(entries);
                        });
        return stored.stream().map(entry -> readContent(lease.session(), entry)).toList();
    }

    @Override
    public Optional<CommitReceipt> receipt(ThreadKey key, String turnId, String commitId) {
        return change(
                key, (s, n) -> Optional.ofNullable(requireTurn(s, turnId).receipts.get(commitId)));
    }

    /** Returns the pre-transition value; false dispatchAttempted permits the first start. */
    @Override
    public ToolExecution execution(ThreadKey key, String id) {
        return change(
                key,
                (state, now) -> {
                    String turn = state.executions.get(id);
                    if (turn == null)
                        throw error(DurableException.Code.NOT_FOUND, "Execution not found");
                    return requireTool(requireTurn(state, turn), id).view();
                });
    }

    private static ToolData delegationParent(State state, ExecutionRequest parent) {
        var tool = requireTool(requireTurn(state, parent.turnId()), parent.executionId());
        equal(
                DurableJson.hash(tool.request),
                DurableJson.hash(parent),
                "Delegation parent changed");
        return tool;
    }

    @Override
    public Optional<DelegationView> delegation(ExecutionRequest parent) {
        return change(
                parent.thread(),
                (state, now) -> {
                    var tool = delegationParent(state, parent);
                    return Optional.ofNullable(tool.delegation).map(value -> value.view(parent));
                });
    }

    @Override
    public DelegationView delegate(ExecutionRequest parent, DelegationTarget target) {
        DurableProtocol.validate("delegationTarget", target);
        var child = target.child().key();
        if (!parent.session().equals(child.session()) || parent.thread().equals(child))
            throw error(
                    DurableException.Code.INVALID,
                    "Delegation requires another Thread in the same Session");
        return change(
                parent.thread(),
                (state, now) -> {
                    var tool = delegationParent(state, parent);
                    if (tool.delegation != null) {
                        if (tool.delegation.target != null)
                            equal(
                                    DurableJson.hash(tool.delegation.target),
                                    DurableJson.hash(target),
                                    "Delegation target changed");
                        return tool.delegation.view(parent);
                    }
                    var turn = requireTurn(state, parent.turnId());
                    if (turn.cancel || tool.result.state() == ExecutionState.CANCELLED)
                        return cancelDelegation(state, turn, tool, null);
                    if (!tool.attempted || turn.status.terminal() || tool.result.state().terminal())
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Delegation has not been dispatched or has ended");
                    String requestId =
                            "delegation:"
                                    + DurableJson.hash(
                                            List.of(parent.thread(), parent.executionId()));
                    if (state.admissions.containsKey(requestId))
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Delegation admission identity is already in use");
                    rejectDelegationCycle(state, parent.thread().threadId(), child.threadId());
                    selectThread(state, child, target.child());
                    var receipt = submit(state, child, requestId, target.input());
                    var childTurn = requireTurn(state, receipt.turnId());
                    childTurn.parent =
                            new DelegationParent(
                                    parent.thread(),
                                    parent.turnId(),
                                    parent.executionId(),
                                    parent.call().origin());
                    append(
                            state,
                            childTurn,
                            new EventInput(
                                    "delegation-parent:" + parent.executionId(),
                                    "turn.delegated",
                                    DurableJson.value(childTurn.parent)));
                    var data = new DelegationData();
                    data.target = target;
                    data.child = new DelegationLink(child, receipt.turnId());
                    tool.delegation = data;
                    // Both Thread schedules and the mapping remain inside the same Session
                    // transaction.
                    save(state);
                    selectThread(state, parent.thread(), null);
                    append(
                            state,
                            turn,
                            toolEvent(
                                    "delegated:" + parent.executionId(),
                                    "tool.delegated",
                                    DurableJson.value(
                                            Map.of(
                                                    "executionId",
                                                    parent.executionId(),
                                                    "callId",
                                                    parent.call().callId(),
                                                    "child",
                                                    data.child)),
                                    parent.call().origin()));
                    return data.view(parent);
                });
    }

    /** Only unfinished parent/child pairs contribute wait edges; settled history cannot block reuse. */
    private static void rejectDelegationCycle(
            State state, String parentThread, String childThread) {
        if (!state.threads.containsKey(childThread)) return;
        var edges = new LinkedHashMap<String, Set<String>>();
        for (var turn : state.turns.values()) {
            if (turn.status.terminal()) continue;
            var tools =
                    turn.tools instanceof TransactionMap
                            ? turn.tools
                            : new TransactionMap<>(
                                    state.tx,
                                    "turn-data:" + DurableJson.hash(turn.id) + ":tool:",
                                    ToolData.class);
            for (var tool : tools.values()) {
                if (tool.result.state().terminal()
                        || tool.delegation == null
                        || tool.delegation.child == null) continue;
                var child = tool.delegation.child;
                var task = state.turns.get(child.turnId());
                if (task == null)
                    throw error(DurableException.Code.CONFLICT, "Delegated child Turn is missing");
                if (!task.status.terminal())
                    edges.computeIfAbsent(turn.threadId, ignored -> new HashSet<>())
                            .add(child.thread().threadId());
            }
        }
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(childThread);
        while (!pending.isEmpty()) {
            var thread = pending.removeFirst();
            if (thread.equals(parentThread))
                throw error(
                        DurableException.Code.CONFLICT,
                        "Delegation would create a Thread dependency cycle");
            if (visited.add(thread)) pending.addAll(edges.getOrDefault(thread, Set.of()));
        }
    }

    @Override
    public DelegationView cancelDelegation(ExecutionRequest parent, String childTurnId) {
        return change(
                parent.thread(),
                (state, now) -> {
                    var tool = delegationParent(state, parent);
                    return cancelDelegation(
                            state, requireTurn(state, parent.turnId()), tool, childTurnId);
                });
    }

    private static DelegationView cancelDelegation(
            State state, TurnData turn, ToolData tool, String childTurnId) {
        var parent = tool.request;
        var data = tool.delegation;
        if (childTurnId != null
                && (data == null || data.child == null || !childTurnId.equals(data.child.turnId())))
            throw error(DurableException.Code.CONFLICT, "Child Turn handle changed");
        if (data == null) {
            if (tool.result.state().terminal() && tool.result.state() != ExecutionState.CANCELLED)
                throw error(
                        DurableException.Code.CONFLICT,
                        "Execution has already settled without delegation");
            data = new DelegationData();
            tool.delegation = data;
        }
        if (!data.cancelRequested) {
            data.cancelRequested = true;
            if (data.child != null) {
                selectThread(state, data.child.thread(), null);
                cancel(state, data.child.turnId(), "delegation-cancel:" + parent.executionId());
                save(state);
                selectThread(state, parent.thread(), null);
            }
            append(
                    state,
                    turn,
                    toolEvent(
                            "delegation-cancel:" + parent.executionId(),
                            "tool.delegation.cancel_requested",
                            DurableJson.value(
                                    Map.of(
                                            "executionId",
                                            parent.executionId(),
                                            "callId",
                                            parent.call().callId(),
                                            "child",
                                            DurableJson.value(data.child))),
                            parent.call().origin()));
        }
        return data.view(parent);
    }

    @Override
    public ToolExecution beginDispatch(Lease lease, String id) {
        return change(
                lease.thread(),
                (s, n) -> {
                    TurnData r = owned(s, lease, n);
                    ToolData t = requireTool(r, id);
                    if (r.cancel
                            || r.status == TurnStatus.PAUSING
                            || r.failedEpoch == lease.epoch()
                            || r.recovery != null && !r.recovery.retryAllowed())
                        throw error(DurableException.Code.CONFLICT, "Turn is stopping");
                    ToolExecution previous = t.view();
                    if (!t.attempted && !r.cancel) {
                        t.attempted = true;
                        append(
                                s,
                                r,
                                toolEvent(
                                        "dispatch:" + id,
                                        "tool.dispatching",
                                        DurableJson.value(
                                                Map.of(
                                                        "executionId",
                                                        id,
                                                        "callId",
                                                        t.request.call().callId())),
                                        t.request.call().origin()));
                    }
                    return previous;
                });
    }

    @Override
    public void acceptResult(ThreadKey key, String id, ExecutionResult result) {
        Objects.requireNonNull(result);
        if (result.state() == ExecutionState.NOT_FOUND)
            throw error(DurableException.Code.INVALID, "NOT_FOUND is not an accepted outcome");
        change(
                key,
                (s, n) -> {
                    String turnId = s.executions.get(id);
                    if (turnId == null)
                        throw error(DurableException.Code.NOT_FOUND, "Execution not found");
                    TurnData r = requireTurn(s, turnId);
                    ToolData t = requireTool(r, id);
                    acceptResult(s, r, t, id, result);
                    return null;
                });
    }

    private void acceptResult(State s, TurnData r, ToolData t, String id, ExecutionResult result) {
        if (!t.attempted && result.state() != ExecutionState.CANCELLED)
            throw error(DurableException.Code.CONFLICT, "Execution has not been dispatched");
        if (t.result.state().terminal()) {
            if (result.state().terminal())
                equal(
                        DurableJson.hash(t.result),
                        DurableJson.hash(result),
                        "Frozen result changed");
            return;
        }
        if (DurableJson.hash(t.result).equals(DurableJson.hash(result))) return;
        t.result = result;
        r.nextAttemptAt = 0; // Accepted result and durable recovery work are the same
        // transaction.
        append(
                s,
                r,
                toolEvent(
                        "outcome:" + id + ":" + (++t.resultRevision),
                        result.state().terminal() ? "tool.result.accepted" : "tool.progress",
                        DurableJson.value(
                                Map.of(
                                        "executionId",
                                        id,
                                        "callId",
                                        t.request.call().callId(),
                                        "result",
                                        result)),
                        t.request.call().origin()));
    }

    private static EventInput toolEvent(
            String id, String type, JsonNode data, RunStepRef position) {
        return new EventInput(
                id,
                type,
                data,
                position == null ? null : position.runId(),
                position == null ? null : position.stepId());
    }

    @Override
    public void acceptExecutionEvent(ExecutionMessages.Event event) {
        ExecutionMessages.validate(event);
        var request = event.request();
        change(
                request.thread(),
                (s, now) -> {
                    var r = requireTurn(s, request.turnId());
                    var t = requireTool(r, request.executionId());
                    equal(
                            DurableJson.hash(t.request),
                            event.requestDigest(),
                            "Execution source request differs");
                    String part =
                            "source:"
                                    + DurableJson.hash(request.executionId())
                                    + ":"
                                    + positionKey(event.sequence());
                    var old = s.tx.get(part);
                    if (old != null && !old.isNull()) {
                        equal(
                                old.asText(),
                                DurableJson.hash(event),
                                "Source event content changed");
                        return null;
                    }
                    if (event.sequence() != t.sourceSequence + 1)
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Source event prefix is incomplete");
                    if (!t.attempted || t.result.state().terminal())
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Source event outside active execution");
                    if (event.output() != null) {
                        var page = event.output();
                        if (t.outputSealed || page.offset() != t.outputOffset)
                            throw error(
                                    DurableException.Code.CONFLICT,
                                    "Output offset or seal differs");
                        t.hasOutput = true;
                        t.outputOffset = page.nextOffset();
                        t.outputSealed = page.terminal();
                        append(
                                s,
                                r,
                                toolEvent(
                                        event.eventId(),
                                        page.gap() != null ? "tool.output.gap" : "tool.output",
                                        DurableJson.value(
                                                Map.of(
                                                        "executionId",
                                                        request.executionId(),
                                                        "callId",
                                                        request.call().callId(),
                                                        "sourceSequence",
                                                        event.sequence(),
                                                        "output",
                                                        page)),
                                        request.call().origin()));
                    } else {
                        if (event.result().state().terminal() && t.hasOutput && !t.outputSealed)
                            throw error(DurableException.Code.CONFLICT, "Output is not sealed");
                        acceptResult(s, r, t, request.executionId(), event.result());
                    }
                    t.sourceSequence = event.sequence();
                    s.tx.put(part, DurableJson.value(DurableJson.hash(event)));
                    return null;
                });
    }

    private record ResourceReceipt(String digest, JsonNode result) {}

    private static String resourceKey(List<String> namespace, String key) {
        return "resource:" + DurableJson.hash(namespace) + ":" + DurableJson.hash(key);
    }

    private SessionResources.Value hydrateResource(
            SessionKey session, SessionResources.Value value) {
        return new SessionResources.Value(
                value.namespace(),
                value.key(),
                readContent(session, value.value()),
                value.version());
    }

    @Override
    public JsonNode resource(Lease lease, SessionResources.Request request) {
        String digest = DurableJson.hash(request);
        var content =
                request.value() == null || request.value().isNull()
                        ? null
                        : storeContent(lease.session(), request.value());
        var stored =
                change(
                        lease.thread(),
                        (s, now) -> {
                            owned(s, lease, now);
                            String receiptKey =
                                    "resource-receipt:"
                                            + DurableJson.hash(
                                                    List.of(lease.turnId(), request.requestId()));
                            var prior = s.threadTx.get(receiptKey);
                            if (prior != null) {
                                var receipt = DurableJson.convert(prior, ResourceReceipt.class);
                                equal(
                                        receipt.digest(),
                                        digest,
                                        "Resource operation identity changed");
                                return receipt.result();
                            }
                            JsonNode result;
                            String key =
                                    request.key() == null
                                            ? null
                                            : resourceKey(request.namespace(), request.key());
                            var value = key == null ? null : s.threadTx.get(key);
                            var current =
                                    value == null || value.isNull()
                                            ? null
                                            : DurableJson.convert(
                                                    value, SessionResources.Value.class);
                            switch (request.operation()) {
                                case GET -> result = DurableJson.value(current);
                                case SEARCH -> {
                                    var keys =
                                            s.threadTx.keys(
                                                    "resource:"
                                                            + DurableJson.hash(request.namespace())
                                                            + ":",
                                                    null,
                                                    request.offset() + request.limit());
                                    var values = new ArrayList<JsonNode>();
                                    for (int i = request.offset(); i < keys.size(); i++)
                                        values.add(s.threadTx.get(keys.get(i)));
                                    result = DurableJson.value(values);
                                }
                                case CAS, PUT, DELETE -> {
                                    boolean accepted =
                                            request.operation() != SessionResources.Operation.CAS
                                                    || request.expectedVersion()
                                                            == (current == null
                                                                    ? 0
                                                                    : current.version());
                                    if (accepted) {
                                        long version = ++s.thread.resourcePosition;
                                        var next =
                                                new SessionResources.Value(
                                                        request.namespace(),
                                                        request.key(),
                                                        request.operation()
                                                                        == SessionResources
                                                                                .Operation.DELETE
                                                                ? null
                                                                : content,
                                                        version);
                                        s.threadTx.put(
                                                key,
                                                request.operation()
                                                                == SessionResources.Operation.DELETE
                                                        ? null
                                                        : DurableJson.value(next));
                                        s.threadTx.put(
                                                "resource-history:"
                                                        + s.thread.journalBranch
                                                        + ":"
                                                        + positionKey(version),
                                                DurableJson.value(next));
                                    }
                                    result =
                                            request.operation() == SessionResources.Operation.CAS
                                                    ? DurableJson.value(accepted)
                                                    : DurableJson.value(null);
                                }
                                default ->
                                        throw new IllegalArgumentException(
                                                "Unknown resource operation");
                            }
                            s.threadTx.put(
                                    receiptKey,
                                    DurableJson.value(new ResourceReceipt(digest, result)));
                            return result;
                        });
        if (request.operation() == SessionResources.Operation.GET && !stored.isNull())
            return DurableJson.value(
                    hydrateResource(
                            lease.session(),
                            DurableJson.convert(stored, SessionResources.Value.class)));
        if (request.operation() == SessionResources.Operation.SEARCH) {
            var values = new ArrayList<SessionResources.Value>();
            for (var value : stored)
                values.add(
                        hydrateResource(
                                lease.session(),
                                DurableJson.convert(value, SessionResources.Value.class)));
            return DurableJson.value(values);
        }
        return stored;
    }

    @Override
    public List<SessionResources.Value> checkpointResources(ThreadKey session, long position) {
        var values =
                change(
                        session,
                        (s, now) -> {
                            var checkpoint = checkpointRecord(s, position);
                            var result = new LinkedHashMap<String, SessionResources.Value>();
                            for (long i = 1; i <= checkpoint.resourcePosition(); i++) {
                                var entry =
                                        DurableJson.convert(
                                                s.threadTx.get(
                                                        "resource-history:"
                                                                + checkpoint.branch()
                                                                + ":"
                                                                + positionKey(i)),
                                                SessionResources.Value.class);
                                String key = resourceKey(entry.namespace(), entry.key());
                                if (entry.value() == null || entry.value().isNull())
                                    result.remove(key);
                                else result.put(key, entry);
                            }
                            return List.copyOf(result.values());
                        });
        return values.stream().map(value -> hydrateResource(session.session(), value)).toList();
    }

    @Override
    public void signal(ThreadKey key, String turnId, Signal signal) {
        change(
                key,
                (s, n) -> {
                    acceptSignal(s, requireTurn(s, turnId), signal);
                    return null;
                });
    }

    private static void acceptSignal(State s, TurnData r, Signal signal) {
        Signal old = r.signals.get(signal.id());
        if (old != null) {
            equal(DurableJson.hash(old), DurableJson.hash(signal), "Signal content changed");
            return;
        }
        if (r.withdrawn.contains(signal.id()))
            throw error(DurableException.Code.CONFLICT, "Input was withdrawn");
        if (r.status.terminal()) throw error(DurableException.Code.CONFLICT, "Turn is terminal");
        r.signals.put(signal.id(), signal);
        r.nextAttemptAt = 0;
        append(
                s,
                r,
                new EventInput(
                        "signal:" + r.id + ":" + signal.id(),
                        "signal.accepted",
                        DurableJson.value(
                                Map.of(
                                        "id",
                                        DurableJson.hash(List.of(r.id, signal.id())),
                                        "turnId",
                                        r.id,
                                        "signalId",
                                        signal.id(),
                                        "key",
                                        signal.key(),
                                        "status",
                                        "accepted"))));
    }

    @Override
    public void withdraw(ThreadKey key, String turnId, String signalId, String requestId) {
        DurableJson.text(signalId, "signalId");
        DurableJson.text(requestId, "requestId");
        change(
                key,
                (s, n) -> {
                    withdraw(s, turnId, signalId, requestId);
                    return null;
                });
    }

    private static void withdraw(State s, String turnId, String signalId, String requestId) {
        String identity = "withdraw:" + requestId;
        String digest = DurableJson.hash(List.of(turnId, signalId));
        String prior = s.commands.get(identity);
        if (prior != null) {
            equal(prior, digest, "Withdrawal identity changed");
            return;
        }
        TurnData turn = requireTurn(s, turnId);
        if (turn.consumed.contains(signalId))
            throw error(DurableException.Code.CONFLICT, "Input already applied");
        if (turn.status.terminal()) throw error(DurableException.Code.CONFLICT, "Turn is terminal");
        if (!turn.withdrawn.contains(signalId)) turn.withdrawn.add(signalId);
        s.commands.put(identity, digest);
        append(
                s,
                turn,
                new EventInput(
                        "withdraw:" + turnId + ":" + signalId,
                        "signal.withdrawn",
                        DurableJson.value(
                                Map.of(
                                        "id",
                                        DurableJson.hash(List.of(turnId, signalId)),
                                        "turnId",
                                        turnId,
                                        "signalId",
                                        signalId,
                                        "status",
                                        "withdrawn"))));
    }

    @Override
    public void cancel(ThreadKey key, String turnId, String requestId) {
        DurableJson.text(requestId, "requestId");
        change(
                key,
                (s, n) -> {
                    cancel(s, turnId, requestId);
                    return null;
                });
    }

    private static void pause(State s, TurnData turn, String requestId, long now) {
        if (turn.status == TurnStatus.PAUSING || turn.status == TurnStatus.PAUSED) return;
        turn.status = TurnStatus.PAUSING;
        turn.revision++;
        append(
                s,
                turn,
                new EventInput(
                        "pause:" + turn.id + ":" + turn.revision,
                        "turn.pause_requested",
                        DurableJson.value(
                                Map.of("requestId", requestId, "revision", turn.revision))));
        if (turn.owner == null || turn.expiresAt <= now)
            completePause(s, turn, now, turn.owner != null);
    }

    private static void completePause(State s, TurnData turn, long now, boolean lost) {
        invocations(s, turn).abandon(now, lost);
        turn.owner = null;
        turn.expiresAt = 0;
        turn.status = TurnStatus.PAUSED;
        turn.revision++;
        append(
                s,
                turn,
                new EventInput(
                        "paused:" + turn.id + ":" + turn.revision, "turn.paused", lifecycle(turn)));
    }

    private static void resume(State s, TurnData turn, ControlCommand command, long now) {
        if (turn.status != TurnStatus.PAUSED || turn.revision != command.expectedRevision())
            throw error(DurableException.Code.CONFLICT, "Observed pause has changed");
        // An invocation can stop after recording an interaction but before choosing its wait.
        if (turn.waiting == null) {
            for (var interaction : s.interactions.values()) {
                if (turn.id.equals(interaction.path("turnId").asText())
                        && "pending".equals(interaction.path("status").asText())
                        && !interaction.path("automatic").asBoolean()) {
                    var condition = new WaitCondition(interaction.path("key").asText(), 0);
                    if (!waitSatisfied(turn, condition, now)) {
                        turn.waiting = condition;
                        break;
                    }
                }
            }
        }
        boolean waiting =
                turn.tools.values().stream().anyMatch(t -> !t.result.state().terminal())
                        || turn.waiting != null && !waitSatisfied(turn, turn.waiting, now);
        turn.status =
                waiting
                        ? TurnStatus.WAITING
                        : turn.epoch == 0 ? TurnStatus.QUEUED : TurnStatus.RUNNING;
        turn.revision++;
        append(
                s,
                turn,
                new EventInput(
                        "resumed:" + turn.id + ":" + turn.revision,
                        "turn.resumed",
                        lifecycle(turn)));
    }

    private static boolean waitSatisfied(TurnData turn, WaitCondition wait, long now) {
        return wait.deadline() > 0 && wait.deadline() <= now
                || turn.signals.values().stream()
                        .anyMatch(
                                signal ->
                                        wait.key().equals(signal.key())
                                                && !turn.consumed.contains(signal.id())
                                                && !turn.withdrawn.contains(signal.id()));
    }

    private static void cancel(State s, String turnId, String requestId) {
        String command = "cancel:" + requestId;
        String old = s.commands.get(command);
        if (old != null) {
            equal(old, turnId, "Cancel request belongs to another turn");
            return;
        }
        TurnData r = requireTurn(s, turnId);
        s.commands.put(command, turnId);
        if (!r.status.terminal()) {
            r.cancel = true;
            boolean queued = r.epoch == 0;
            r.status = queued ? TurnStatus.CANCELLED : TurnStatus.CANCELLING;
            r.nextAttemptAt = 0;
            append(
                    s,
                    r,
                    new EventInput(
                            "cancel:" + turnId,
                            "turn.cancel_requested",
                            DurableJson.value(Map.of("requestId", requestId))));
            if (queued)
                append(s, r, new EventInput("cancelled:" + r.id, "turn.cancelled", lifecycle(r)));
        }
    }

    @Override
    public Snapshot snapshot(SessionKey key) {
        return change(
                key,
                (s, n) ->
                        new Snapshot(
                                key,
                                LongStream.rangeClosed(1, s.admitted)
                                        .mapToObj(
                                                i ->
                                                        view(
                                                                s,
                                                                requireTurn(
                                                                        s,
                                                                        s.tx.get(
                                                                                        "turn-order:"
                                                                                                + positionKey(
                                                                                                        i))
                                                                                .asText())))
                                        .toList(),
                                projection(s),
                                Long.toString(s.sequence),
                                cursor(s, s.sequence)));
    }

    @Override
    public EventPage events(SessionKey key, String after, int limit) {
        limit(limit);
        return change(
                key,
                (s, n) -> {
                    long pos = position(s, after);
                    if (pos < s.retainedAfter)
                        throw error(
                                DurableException.Code.CURSOR_EXPIRED,
                                "Cursor expired; load a snapshot");
                    var page = new ArrayList<PublicEvent>();
                    for (long seq = pos + 1; seq <= s.sequence && page.size() < limit; seq++)
                        page.add(
                                DurableJson.convert(
                                        s.tx.get("event:" + positionKey(seq)), PublicEvent.class));
                    return new EventPage(
                            page,
                            page.isEmpty() ? cursor(s, pos) : page.get(page.size() - 1).cursor(),
                            cursor(s, s.sequence));
                });
    }

    @Override
    public void pruneEvents(SessionKey key, String through) {
        change(
                key,
                (s, n) -> {
                    long pos = position(s, through);
                    if (pos < s.retainedAfter) return null;
                    for (long seq = s.retainedAfter + 1; seq <= pos; seq++)
                        s.tx.put("event:" + positionKey(seq), null);
                    s.retainedAfter = pos;
                    return null;
                });
    }

    public record CheckpointRecord(
            CheckpointMetadata metadata,
            JsonNode content,
            String branch,
            long journalPosition,
            long resourcePosition,
            boolean settled) {}

    private static CheckpointRecord checkpointRecord(State state, long position) {
        if (position < 1 || position > state.thread.checkpointPosition)
            throw error(DurableException.Code.NOT_FOUND, "Checkpoint not found");
        return DurableJson.convert(
                state.threadTx.get("checkpoint-history:" + positionKey(position)),
                CheckpointRecord.class);
    }

    @Override
    public List<CheckpointMetadata> checkpoints(ThreadKey session, long after, int limit) {
        limit(limit);
        return change(
                session,
                (s, n) -> {
                    if (after < 0 || after > s.thread.checkpointPosition)
                        throw error(DurableException.Code.INVALID, "Invalid checkpoint position");
                    var page = new ArrayList<CheckpointMetadata>();
                    for (long i = after + 1;
                            i <= s.thread.checkpointPosition && page.size() < limit;
                            i++) page.add(checkpointRecord(s, i).metadata());
                    return List.copyOf(page);
                });
    }

    @Override
    public CheckpointView checkpoint(ThreadKey session, long position) {
        var view =
                change(
                        session,
                        (s, n) -> {
                            var record = checkpointRecord(s, position);
                            return new CheckpointView(
                                    s.thread.definition,
                                    record.metadata(),
                                    record.content(),
                                    record.journalPosition(),
                                    record.settled());
                        });
        return new CheckpointView(
                view.definition(),
                view.metadata(),
                readContent(session.session(), view.checkpoint()),
                view.journalPosition(),
                view.settled());
    }

    @Override
    public List<JsonNode> checkpointJournal(
            ThreadKey session, long position, long after, int limit) {
        limit(limit);
        var stored =
                change(
                        session,
                        (s, n) -> {
                            var record = checkpointRecord(s, position);
                            if (after < 0 || after > record.journalPosition())
                                throw error(
                                        DurableException.Code.INVALID, "Invalid journal position");
                            var page = new ArrayList<JsonNode>();
                            for (long i = after + 1;
                                    i <= record.journalPosition() && page.size() < limit;
                                    i++)
                                page.add(
                                        s.threadTx.get(
                                                "journal:"
                                                        + record.branch()
                                                        + ":"
                                                        + positionKey(i)));
                            return List.copyOf(page);
                        });
        return stored.stream().map(value -> readContent(session.session(), value)).toList();
    }

    @Override
    public CommitReceipt restore(Restoration restore) {
        DurableProtocol.validate("restoration", restore);
        var thread = restore.target().key();
        var session = thread.session();
        String digest = DurableJson.hash(restore);
        var content = storeContent(session, restore.checkpoint());
        var journal =
                restore.journal().stream().map(value -> storeContent(session, value)).toList();
        var resources =
                restore.resources().stream()
                        .map(
                                value ->
                                        new SessionResources.Value(
                                                value.namespace(),
                                                value.key(),
                                                storeContent(session, value.value()),
                                                value.version()))
                        .toList();
        return change(
                thread,
                restore.target(),
                (s, n) -> {
                    String receiptKey = "restore:" + DurableJson.hash(restore.requestId());
                    var old = s.threadTx.get(receiptKey);
                    if (old != null) {
                        var receipt = DurableJson.convert(old, CommitReceipt.class);
                        equal(receipt.digest(), digest, "Restoration identity changed");
                        return receipt;
                    }
                    requireOpen(s.session.status(), "Session");
                    requireOpen(s.thread.status, "Thread");
                    if (!s.thread.definition.equals(restore.target()))
                        throw error(
                                DurableException.Code.INCOMPATIBLE,
                                "Restoration definition differs");
                    if (s.thread.checkpointPosition != restore.expectedPosition()
                            || current(s) != null)
                        throw error(
                                DurableException.Code.CONFLICT,
                                "Restoration requires the expected idle target");
                    s.thread.epoch++;
                    s.thread.journalBranch = DurableJson.hash(List.of(restore.requestId(), digest));
                    s.thread.journalPosition = 0;
                    s.thread.resourcePosition = 0;
                    String resourceAfter = null;
                    while (true) {
                        var keys = s.threadTx.keys("resource:", resourceAfter, 1000);
                        for (var key : keys) {
                            resourceAfter = key;
                            s.threadTx.put(key, null);
                        }
                        if (keys.size() < 1000) break;
                    }
                    for (var resource : resources) {
                        var baseline =
                                new SessionResources.Value(
                                        resource.namespace(),
                                        resource.key(),
                                        resource.value(),
                                        ++s.thread.resourcePosition);
                        s.threadTx.put(
                                resourceKey(baseline.namespace(), baseline.key()),
                                DurableJson.value(baseline));
                        s.threadTx.put(
                                "resource-history:"
                                        + s.thread.journalBranch
                                        + ":"
                                        + positionKey(s.thread.resourcePosition),
                                DurableJson.value(baseline));
                    }
                    for (var entry : journal)
                        s.threadTx.put(
                                "journal:"
                                        + s.thread.journalBranch
                                        + ":"
                                        + positionKey(++s.thread.journalPosition),
                                entry);
                    s.thread.checkpointMetadata =
                            new CheckpointMetadata(
                                    "1",
                                    s.thread.definition.harness(),
                                    s.thread.definition.definitionVersion(),
                                    s.thread.definition.stateVersion(),
                                    Long.toString(++s.thread.checkpointPosition),
                                    DurableJson.hash(restore.checkpoint()));
                    s.threadTx.put("checkpoint", content);
                    s.threadTx.put(
                            "checkpoint-history:" + positionKey(s.thread.checkpointPosition),
                            DurableJson.value(
                                    new CheckpointRecord(
                                            s.thread.checkpointMetadata,
                                            content,
                                            s.thread.journalBranch,
                                            s.thread.journalPosition,
                                            s.thread.resourcePosition,
                                            true)));
                    append(
                            s,
                            null,
                            new EventInput(
                                    "restored:"
                                            + DurableJson.hash(
                                                    List.of(thread, restore.requestId())),
                                    "checkpoint.restored",
                                    DurableJson.value(
                                            Map.of(
                                                    "position",
                                                    s.thread.checkpointPosition,
                                                    "requestId",
                                                    restore.requestId()))));
                    var receipt =
                            new CommitReceipt(
                                    restore.requestId(),
                                    digest,
                                    s.thread.checkpointPosition,
                                    cursor(s, s.sequence));
                    s.threadTx.put(receiptKey, DurableJson.value(receipt));
                    return receipt;
                });
    }

    private static JsonNode lifecycle(TurnData turn) {
        ObjectNode data =
                (ObjectNode)
                        DurableJson.value(Map.of("revision", turn.revision, "attempt", turn.epoch));
        data.set("waiting", DurableJson.value(turn.waiting));
        data.put("error", turn.error);
        return data;
    }

    private static void appendSessionLifecycle(State state, String action) {
        String type = "session." + action;
        append(state, null, new EventInput(type, type, DurableJson.value(state.session)));
    }

    private static void appendThreadLifecycle(State state, String action) {
        String type = "thread." + action;
        append(
                state,
                null,
                new EventInput(
                        type + ":" + DurableJson.hash(state.thread.definition.key()),
                        type,
                        DurableJson.value(state.thread.view())));
    }

    private static void append(State s, TurnData r, EventInput input) {
        DurableProtocol.validate("eventInput", input);
        if (input.runId() != null) {
            if (r == null) throw error(DurableException.Code.INVALID, "Run event requires a Turn");
            var records = invocations(s, r);
            records.run(input.runId());
            if (input.stepId() != null) records.step(input.runId(), input.stepId());
        }
        String digest = DurableJson.hash(List.of(r == null ? "" : r.id, input));
        String old = s.eventDigests.get(input.eventId());
        if (old != null) {
            equal(old, digest, "Event identity changed");
            return;
        }
        s.eventDigests.put(input.eventId(), digest);
        long seq = ++s.sequence;
        s.tx.put(
                "event:" + positionKey(seq),
                DurableJson.value(
                        new PublicEvent(
                                "1",
                                input.eventId(),
                                s.session.key(),
                                r == null
                                        ? (s.thread == null
                                                ? null
                                                : s.thread.definition.key().threadId())
                                        : r.threadId,
                                r == null ? null : r.id,
                                input.runId(),
                                input.stepId(),
                                Long.toString(seq),
                                cursor(s, seq),
                                input.type(),
                                DurableJson.copy(input.data()))));
        project(s, input, r == null ? null : r.id, Long.toString(seq));
    }

    private static ObjectNode projection(State s) {
        var result = EventProjection.empty();
        result.set("session", DurableJson.value(s.session));
        s.threads.forEach(
                (k, v) -> result.withObject("/threads").set(k, DurableJson.value(v.view())));
        s.messages.forEach((k, v) -> result.withObject("/messages").set(k, v));
        s.projectedTools.forEach((k, v) -> result.withObject("/tools").set(k, v));
        s.artifacts.forEach((k, v) -> result.withObject("/artifacts").set(k, v));
        s.interactions.forEach((k, v) -> result.withObject("/interactions").set(k, v));
        s.projectedSignals.forEach((k, v) -> result.withObject("/signals").set(k, v));
        return result;
    }

    private static void project(State s, EventInput event, String turnId, String sequence) {
        var data = event.data();
        String category;
        String id;
        TransactionMap<JsonNode> records;
        if (event.type().equals("message.delta") || event.type().equals("message.completed")) {
            String messageId = event.data().path("messageId").asText();
            if (!s.messages.containsKey(messageId))
                s.tx.put(
                        "message-order:" + positionKey(Long.parseLong(sequence)),
                        DurableJson.value(messageId));
            category = "messages";
            id = data.path("messageId").asText();
            records = s.messages;
        } else if (event.type().startsWith("tool.")) {
            category = "tools";
            id = EventProjection.toolId(requireTurn(s, turnId).threadId, turnId, data);
            records = s.projectedTools;
        } else if (event.type().startsWith("signal.")) {
            category = "signals";
            id = data.path("id").asText();
            records = s.projectedSignals;
        } else if (event.type().startsWith("interaction.")) {
            category = "interactions";
            id = data.path("id").asText();
            records = s.interactions;
        } else if (event.type().equals("artifact.published")) {
            category = "artifacts";
            id = data.path("artifactId").asText();
            records = s.artifacts;
        } else return;
        var view = EventProjection.empty();
        var old = records.get(id);
        if (old != null) view.withObject("/" + category).set(id, old);
        String threadId =
                turnId == null
                        ? (s.thread == null ? null : s.thread.definition.key().threadId())
                        : requireTurn(s, turnId).threadId;
        EventProjection.apply(view, event, threadId, turnId, sequence);
        JsonNode projected = view.path(category).get(id);
        records.put(id, projected);
        if (category.equals("messages") && old == null && threadId != null)
            threadTransaction(s.tx, new ThreadKey(s.session.key(), threadId))
                    .put(
                            "message-order:" + positionKey(Long.parseLong(sequence)),
                            DurableJson.value(id));
    }

    @Override
    public List<ThreadSpec> ready(List<HarnessSelector> harnesses, int limit) {
        limit(limit);
        var candidates = new ArrayList<RecordStore.Work>();
        for (var selector : harnesses.stream().distinct().toList())
            candidates.addAll(store.ready(readyQueue(selector), limit));
        return candidates.stream()
                .sorted(Comparator.comparingLong(RecordStore.Work::readyAt))
                .map(work -> DurableJson.convert(work.value(), ThreadSpec.class))
                .distinct()
                .limit(limit)
                .toList();
    }

    private static String readyQueue(HarnessSelector selector) {
        return selector.definitionVersion() == null
                ? "thread:" + selector.harness()
                : "thread-version:" + DurableJson.hash(selector);
    }

    private static TurnView view(State s, TurnData r) {
        return new TurnView(
                new ThreadKey(s.session.key(), r.threadId),
                r.id,
                r.status,
                r.revision,
                r.cancel,
                DurableJson.copy(r.output),
                r.error,
                r.waiting,
                r.recovery,
                r.parent);
    }

    private static TurnData requireTurn(State s, String id) {
        TurnData r = s.turns.get(id);
        if (r == null
                || (s.thread != null && !s.thread.definition.key().threadId().equals(r.threadId)))
            throw error(DurableException.Code.NOT_FOUND, "Turn not found in this Thread");
        if (!(r.tools instanceof TransactionMap)) {
            String prefix = "turn-data:" + DurableJson.hash(id) + ":";
            var tools = new TransactionMap<>(s.tx, prefix + "tool:", ToolData.class);
            var signals = new TransactionMap<>(s.tx, prefix + "signal:", Signal.class);
            var receipts = new TransactionMap<>(s.tx, prefix + "receipt:", CommitReceipt.class);
            r.tools = tools;
            r.signals = signals;
            r.receipts = receipts;
            s.details.addAll(List.of(tools, signals, receipts));
        }
        return r;
    }

    private static ToolData requireTool(TurnData r, String id) {
        ToolData t = r.tools.get(id);
        if (t == null) throw error(DurableException.Code.NOT_FOUND, "Tool not found");
        return t;
    }

    private static TurnData owned(State s, Lease lease, long now) {
        TurnData r = requireTurn(s, lease.turnId());
        if (!Objects.equals(r.owner, lease.owner())
                || r.epoch != lease.epoch()
                || r.expiresAt <= now
                || r.status.terminal())
            throw error(DurableException.Code.FENCED, "Execution ownership expired or changed");
        return r;
    }

    public static String executionId(ThreadKey key, String turnId, String callId) {
        return "exec_" + DurableJson.hash(List.of(key, turnId, callId));
    }

    private static String cursor(State s, long seq) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ("1:" + s.session.key().storageKey() + ":" + seq)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static long position(State s, String cursor) {
        if (cursor == null || cursor.isEmpty()) return 0;
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = "1:" + s.session.key().storageKey() + ":";
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
            long pos = Long.parseLong(decoded.substring(prefix.length()));
            if (pos < 0 || pos > s.sequence) throw new IllegalArgumentException();
            return pos;
        } catch (IllegalArgumentException e) {
            throw error(DurableException.Code.INVALID, "Invalid or foreign event cursor");
        }
    }

    private static long positive(Duration d) {
        if (d == null || d.isNegative() || d.toMillis() <= 0)
            throw error(DurableException.Code.INVALID, "Positive lease required");
        return d.toMillis();
    }

    private static void limit(int n) {
        if (n < 1 || n > 1000) throw error(DurableException.Code.INVALID, "Limit must be 1..1000");
    }

    private static void equal(String a, String b, String message) {
        if (!Objects.equals(a, b)) throw error(DurableException.Code.CONFLICT, message);
    }

    private static DurableException error(DurableException.Code c, String m) {
        return new DurableException(c, m);
    }
}
