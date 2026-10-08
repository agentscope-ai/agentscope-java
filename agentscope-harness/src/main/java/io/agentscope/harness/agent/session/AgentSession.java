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
package io.agentscope.harness.agent.session;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.session.SessionViews;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A session's durable task operations and read views. Commands are accepted synchronously;
 * this runtime owns execution subscriptions, independently of observers. Storage calls block.
 * Obtaining a handle and reading views never starts inference. Close the owning HarnessAgent.
 */
public final class AgentSession implements AutoCloseable {
    /** Command receipt. A queued task may not yet have a runId. IDs are assigned by the framework. */
    public record Task(String commandId, String turnId, String runId, String status, String error) {
        public boolean settled() {
            return !status.equals("queued")
                    && !status.equals("running")
                    && !status.equals("starting");
        }
    }

    public record InputReceipt(String inputId, String turnId) {}

    private final HarnessAgent agent;
    private final RuntimeContext context;
    private final SessionKey key;
    private final SessionLog log;
    private final SessionInbox inbox;
    private Disposable dispatcher;
    private AgentRun<AgentEvent> active;
    private String activeCommand;
    private volatile String error;
    private volatile boolean closed;

    public AgentSession(HarnessAgent agent, RuntimeContext context) {
        this.agent = Objects.requireNonNull(agent);
        this.context = RuntimeContext.builder(context).build();
        this.context.put(SessionRecorder.TURN_ID_KEY, null);
        this.context.put(SessionExecution.CONTEXT_KEY, null);
        this.key = agent.sessionKey(this.context);
        this.log = agent.sessionLog(this.context);
        this.inbox = new SessionInbox(log);
    }

    public String sessionId() {
        return key.sessionId();
    }

    public SessionLog log() {
        return log;
    }

    public SessionViews.Transcript transcript() {
        return SessionViews.transcript(log);
    }

    public SessionProjection inspect() {
        return SessionProjection.read(log);
    }

    public Map<String, SessionEvent> pending() {
        return SessionInteractions.pending(log);
    }

    public String executionError() {
        return error;
    }

    /** Start dispatching durable queued tasks, for example after application restart. */
    public synchronized void start() {
        ensureOpen();
        if (dispatcher == null)
            dispatcher =
                    Schedulers.boundedElastic()
                            .schedulePeriodically(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
    }

    public Task submit(String input) {
        return submit(UUID.randomUUID().toString(), input);
    }

    public Task submit(Msg input) {
        return submit(List.of(input));
    }

    public Task submit(List<Msg> input) {
        return submit(UUID.randomUUID().toString(), input);
    }

    /** Reuse the key and identical input when retrying a network submission. */
    public Task submit(String idempotencyKey, String input) {
        return submit(idempotencyKey, List.of(new UserMessage(Objects.requireNonNull(input))));
    }

    public Task submit(String idempotencyKey, List<Msg> input) {
        ensureOpen();
        String id = requireId(idempotencyKey);
        if (input.isEmpty()) throw new IllegalArgumentException("A new task requires input");
        String turn =
                UUID.nameUUIDFromBytes(("turn:" + id).getBytes(StandardCharsets.UTF_8)).toString();
        var command = new SessionInbox.Command(id, "submit", turn, freeze(id, input), List.of());
        inbox.locked(tx -> tx.accept(command));
        start();
        return receipt(command);
    }

    public Task resume(String turnId) {
        ensureOpen();
        var latest = SessionTurns.read(log).latest();
        if (latest == null || !latest.turnId().equals(turnId) || latest.ended())
            throw new IllegalStateException("Only the latest unfinished task can continue");
        if (!pending().isEmpty())
            throw new IllegalStateException("Answer pending interactions first");
        if (!inspect().uncertainToolCalls().isEmpty())
            throw new IllegalStateException("Reconcile uncertain tool outcomes first");
        requireIdle();
        var command =
                new SessionInbox.Command(
                        UUID.randomUUID().toString(), "resume", turnId, List.of(), List.of());
        inbox.locked(
                tx -> {
                    if (tx.snapshot().commands().stream()
                            .anyMatch(
                                    c ->
                                            c.kind().equals("resume")
                                                    && c.turnId().equals(turnId)
                                                    && !SessionTurns.read(log)
                                                            .started()
                                                            .contains(c.id())
                                                    && !tx.snapshot()
                                                            .rejected()
                                                            .containsKey(c.id())))
                        throw new IllegalStateException("Continuation already queued");
                    return tx.accept(command);
                });
        start();
        return receipt(command);
    }

    public Task respond(String requestId, String output) {
        return respond(requestId, SessionAnswer.text(output));
    }

    public Task respond(String requestId, boolean approved) {
        return respond(requestId, new SessionAnswer.Approval(approved, null));
    }

    public Task respond(String requestId, SessionAnswer answer) {
        return respond(Map.of(requestId, answer));
    }

    /** Resolve request ownership and encode existing Core HITL inputs; never accept invented tool IDs. */
    public Task respond(Map<String, SessionAnswer> answers) {
        ensureOpen();
        requireIdle();
        if (answers.isEmpty()) throw new IllegalArgumentException("An answer is required");
        var requests = pending();
        String turn = null;
        var results = new ArrayList<ToolResultBlock>();
        var confirmations = new ArrayList<ConfirmResult>();
        var ids = answers.keySet().stream().sorted().toList();
        for (String id : ids) {
            var request = requests.get(id);
            if (request == null)
                throw new IllegalArgumentException("No pending interaction: " + id);
            if (turn != null && !turn.equals(request.turnId()))
                throw new IllegalArgumentException("Answers belong to different tasks");
            turn = request.turnId();
            if (request.data().containsKey("protocol"))
                throw new IllegalArgumentException(
                        "Reply through the interaction's owning service");
            ToolUseBlock call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            SessionAnswer answer = answers.get(id);
            if (request.data().get("kind").equals("external_execution")
                    && answer instanceof SessionAnswer.Output output)
                results.add(ToolResultBlock.of(call.getId(), call.getName(), output.content()));
            else if (request.data().get("kind").equals("confirmation")
                    && answer instanceof SessionAnswer.Approval approval)
                confirmations.add(
                        new ConfirmResult(approval.approved(), call, null, approval.reason()));
            else if (request.data().get("kind").equals("confirmation")
                    && answer instanceof SessionAnswer.Confirmation confirmation) {
                if (!call.getId().equals(confirmation.result().getToolCall().getId()))
                    throw new IllegalArgumentException("Confirmation tool ID mismatch");
                confirmations.add(confirmation.result());
            } else
                throw new IllegalArgumentException("Answer type does not match interaction: " + id);
        }
        var messages = new ArrayList<Msg>();
        if (!results.isEmpty()) messages.add(ToolResultMessage.builder().results(results).build());
        if (!confirmations.isEmpty())
            messages.add(
                    UserMessage.builder()
                            .textContent("")
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                            .build());
        String id =
                "answer-"
                        + UUID.nameUUIDFromBytes(
                                String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
        var command = new SessionInbox.Command(id, "respond", turn, freeze(id, messages), ids);
        inbox.locked(tx -> tx.accept(command));
        start();
        return receipt(command);
    }

    public InputReceipt steer(String input) {
        return steer(List.of(new UserMessage(input)));
    }

    public InputReceipt steer(List<Msg> input) {
        return steer(null, input);
    }

    /** Bind steering to a known task, rejecting a stale operation after the session moves on. */
    public InputReceipt steer(Task task, String input) {
        return steer(task.turnId(), List.of(new UserMessage(input)));
    }

    private InputReceipt steer(String expectedTurn, List<Msg> input) {
        ensureOpen();
        return inbox.locked(
                tx -> {
                    var view = tx.snapshot();
                    var head = log.head();
                    if (view.runId() == null
                            || !view.runId().equals(head.owner())
                            || head.leaseUntil() <= System.currentTimeMillis())
                        throw new IllegalStateException(
                                "No running task accepts steering; submit or resume explicitly");
                    if (expectedTurn != null && !expectedTurn.equals(view.turnId()))
                        throw new IllegalStateException("Task is no longer running");
                    String id = UUID.randomUUID().toString();
                    tx.accept(
                            new SessionInbox.Command(
                                    id, "steer", view.turnId(), freeze(id, input), List.of()));
                    return new InputReceipt(id, view.turnId());
                });
    }

    public InputReceipt inject(String context) {
        return inject(List.of(new UserMessage(context)));
    }

    public InputReceipt inject(List<Msg> input) {
        ensureOpen();
        String id = UUID.randomUUID().toString();
        inbox.locked(
                tx ->
                        tx.accept(
                                new SessionInbox.Command(
                                        id, "inject", null, freeze(id, input), List.of())));
        return new InputReceipt(id, null);
    }

    /** Stop the current execution. Queued tasks remain parked until the interrupted task continues. */
    public boolean interrupt() {
        return interrupt(null);
    }

    /** Optional execution guard prevents a stale UI action from stopping a newer run. */
    public boolean interrupt(String expectedRunId) {
        ensureOpen();
        return inbox.locked(
                tx -> {
                    var view = tx.snapshot();
                    if (view.runId() == null) return false;
                    if (expectedRunId != null && !expectedRunId.equals(view.runId()))
                        throw new IllegalStateException("Execution is no longer active");
                    tx.accept(
                            new SessionInbox.Command(
                                    UUID.randomUUID().toString(),
                                    "interrupt",
                                    view.turnId(),
                                    List.of(),
                                    List.of(view.runId())));
                    tx.close(view.turnId(), view.runId());
                    return true;
                });
    }

    public List<Task> tasks() {
        var tasks = new LinkedHashMap<String, Task>();
        for (var command : inbox.snapshot().commands())
            if (command.execution()) tasks.put(command.turnId(), receipt(command));
        return List.copyOf(tasks.values());
    }

    /** Observe this accepted execution until it completes, suspends, is interrupted, or fails. */
    public Mono<Task> await(Task task) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(100))
                .onBackpressureLatest()
                .concatMap(
                        tick ->
                                Mono.fromCallable(() -> task(task.commandId()))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .filter(
                        value ->
                                value.settled()
                                        && (value.runId() == null
                                                || !Objects.equals(
                                                        value.runId(), log.head().owner())))
                .next();
    }

    public Task task(String commandId) {
        return inbox.snapshot().commands().stream()
                .filter(c -> c.id().equals(commandId) && c.execution())
                .findFirst()
                .map(this::receipt)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task submission"));
    }

    private Task receipt(SessionInbox.Command command) {
        String run = null, status = "queued";
        var head = log.head();
        String rejected = inbox.snapshot().rejected().get(command.id());
        if (rejected != null)
            return new Task(command.id(), command.turnId(), null, "rejected", rejected);
        for (var event : log.scan(0, head.seq())) {
            if (event.type().equals("inbox/started")
                    && command.id().equals(event.data().get("commandId"))) {
                run = event.executionRunId();
                status = "running";
            }
            if (run != null && run.equals(event.executionRunId()) && event.type().equals("run/end"))
                status = String.valueOf(event.data().get("status"));
        }
        synchronized (this) {
            if (command.id().equals(activeCommand) && active != null) {
                run = active.runId();
                if (status.equals("queued")) status = "starting";
            }
        }
        if (status.equals("running")) {
            if (!Objects.equals(run, head.owner())
                    || head.leaseUntil() <= System.currentTimeMillis()) status = "interrupted";
        }
        return new Task(command.id(), command.turnId(), run, status, null);
    }

    private synchronized void dispatch() {
        if (closed) return;
        try {
            if (active != null) {
                var snapshot = inbox.snapshot();
                for (var command : snapshot.commands()) {
                    if (!command.kind().equals("interrupt")
                            || snapshot.handled().contains(command.id())) continue;
                    if (command.requestIds().contains(active.runId())) active.interrupt();
                    inbox.locked(
                            tx -> {
                                tx.handled(command);
                                return null;
                            });
                }
                return;
            }
            var head = log.head();
            if (head.owner() != null && head.leaseUntil() > System.currentTimeMillis()) return;
            var view = SessionTurns.read(log);
            var snapshot = inbox.snapshot();
            var latest = view.latest();
            boolean queued = false;
            for (var command : snapshot.commands()) {
                if (!command.execution()
                        || view.started().contains(command.id())
                        || snapshot.rejected().containsKey(command.id())) continue;
                queued = true;
                if (latest != null && !latest.ended() && command.kind().equals("submit")) continue;
                launch(command);
                return;
            }
            if (!queued && dispatcher != null) {
                dispatcher.dispose();
                dispatcher = null;
            }
        } catch (Exception failure) {
            error = failure.getMessage();
        }
    }

    private void launch(SessionInbox.Command command) {
        error = null;
        RuntimeContext rc =
                RuntimeContext.builder(context)
                        .runId(null)
                        .put(SessionRecorder.TURN_ID_KEY, command.turnId())
                        .put(SessionExecution.CONTEXT_KEY, new SessionExecution(log, command))
                        .build();
        AgentRun<AgentEvent> run = agent.prepareRun(command.messages(), rc);
        active = run;
        activeCommand = command.id();
        run.stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(
                        signal -> {
                            synchronized (this) {
                                if (active == run) {
                                    active = null;
                                    activeCommand = null;
                                }
                            }
                        })
                .subscribe(
                        event -> {},
                        failure -> {
                            error = failure.getMessage();
                            if (!closed
                                    && !SessionTurns.read(log).started().contains(command.id())
                                    && (!(failure instanceof SessionLogException)
                                            || error != null
                                                    && error.contains("Turn already completed")))
                                inbox.locked(
                                        tx -> {
                                            tx.reject(
                                                    command,
                                                    error == null
                                                            ? failure.getClass().getSimpleName()
                                                            : error);
                                            return null;
                                        });
                        });
    }

    private void requireIdle() {
        var head = log.head();
        if (head.owner() != null && head.leaseUntil() > System.currentTimeMillis())
            throw new IllegalStateException("Wait for the current execution to stop");
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 200)
            throw new IllegalArgumentException("Invalid idempotency key");
        return id;
    }

    private static List<Msg> freeze(String commandId, List<Msg> input) {
        var result = new ArrayList<Msg>();
        for (int i = 0; i < input.size(); i++) {
            var json = new LinkedHashMap<String, Object>();
            Map<?, ?> encoded = JsonUtils.getJsonCodec().convertValue(input.get(i), Map.class);
            encoded.forEach((key, value) -> json.put((String) key, value));
            json.put("id", "input-" + commandId + "-" + i);
            result.add(JsonUtils.getJsonCodec().convertValue(json, Msg.class));
        }
        return List.copyOf(result);
    }

    private synchronized void ensureOpen() {
        if (closed) throw new IllegalStateException("Session runtime is closed");
    }

    @Override
    public void close() {
        AgentRun<AgentEvent> run;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (dispatcher != null) dispatcher.dispose();
            run = active;
        }
        if (run != null) {
            run.interrupt();
            try {
                run.termination().block(Duration.ofSeconds(10));
            } catch (RuntimeException timeout) {
                run.cancel();
            }
        }
    }
}
