/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore.Input;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore.Turn;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.PublicEvent;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources.Page;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionPreviews;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Command;
import io.agentcore.managed.server.runtime.managed.ManagedSessionQueue;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher;
import io.agentcore.managed.server.web.catalog.spec.EffectiveAgentDefinition;
import io.agentcore.managed.server.web.managed.AgentSessionInput;
import io.agentcore.managed.server.web.managed.SessionToolResultContent;
import io.lettuce.core.RedisException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Public request acceptance. It neither routes to a HarnessRuntime address nor waits for runtime readiness. */
@Service
public class ManagedSessionTurnsApi {
    @Value("${managed-agents.runtime.harness-type:agentscope-java}")
    private String harnessType = "agentscope-java";

    @Value("${managed-agents.user-files-enabled:${MANAGED_AGENTS_USER_FILES_ENABLED:true}}")
    private boolean userFilesEnabled = true;

    private final AgentServiceSessionRepository resources;
    private final AgentServiceSessionAccess access;
    private final AgentServiceTurnAdmission validation;
    private final ManagedAdmissionStore store;
    private final ManagedCatalog catalog;
    private final ManagedTurnPublisher publisher;
    private final ManagedInputDelivery inputs;
    private final ManagedJournalStore journal;
    private final ObjectMapper mapper;
    private final ManagedSessionEventStreams eventStreams;
    private final Semaphore sseConnections;
    private final Duration authorization;
    private final Duration heartbeat;

    public ManagedSessionTurnsApi(
            AgentServiceSessionRepository resources,
            AgentServiceSessionAccess access,
            AgentServiceTurnAdmission validation,
            ManagedAdmissionStore store,
            ManagedCatalog catalog,
            ManagedTurnPublisher publisher,
            ManagedInputDelivery inputs,
            ManagedJournalStore journal,
            ObjectMapper mapper,
            ManagedSessionEventStreams eventStreams,
            @Value("${managed-agents.agent-service.max-sse-connections:10000}") int sseConnections,
            @Value("${managed-agents.agent-service.sse-authorization-ms:15000}")
                    long authorizationMillis,
            @Value("${managed-agents.runtime.sse-heartbeat-ms:15000}") long heartbeatMillis) {
        if (sseConnections < 1 || authorizationMillis < 100 || heartbeatMillis < 1)
            throw new IllegalArgumentException("SSE limits must be positive");
        this.sseConnections = new Semaphore(sseConnections);
        this.authorization = Duration.ofMillis(authorizationMillis);
        this.heartbeat = Duration.ofMillis(heartbeatMillis);
        this.resources = resources;
        this.access = access;
        this.validation = validation;
        this.store = store;
        this.catalog = catalog;
        this.publisher = publisher;
        this.inputs = inputs;
        this.journal = journal;
        this.mapper = mapper;
        this.eventStreams = eventStreams;
    }

    private ManagedSessionPreviews previews;

    @Autowired(required = false)
    public void previews(ManagedSessionPreviews value) {
        previews = value;
    }

    ManagedSessionQueue queue() {
        return store.queue();
    }

    public Map<String, Object> sendEvents(
            String id, Map<String, Object> body, HttpHeaders headers) {
        boolean extended = extensions(headers);
        fields(body, Set.of("events"));
        if (!(body.get("events") instanceof List<?> values)
                || values.isEmpty()
                || values.size() > 50) throw invalid("events requires 1..50 entries");
        if (json(body).getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large");
        boolean queueCommand =
                values.stream()
                        .anyMatch(
                                value ->
                                        value instanceof Map<?, ?> event
                                                && String.valueOf(event.get("type"))
                                                        .startsWith("user.queue."));
        if (queueCommand) return sendQueueEvent(id, values, headers);
        var commands = new ArrayList<Command>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?>)) throw invalid("Event must be an object");
            var event = cast(raw);
            String type = String.valueOf(event.get("type"));
            if (!Set.of("user.message", "user.interrupt").contains(type))
                throw invalid("unsupported_capability: Unsupported input event");
            fields(
                    event,
                    "user.message".equals(type)
                            ? Set.of("type", "content", "managed_agents")
                            : Set.of("type", "managed_agents"));
            boolean explicit = event.containsKey("managed_agents");
            String intent = "user.message".equals(type) ? "message" : "interrupt";
            String target = null;
            if (explicit) {
                if (!(event.get("managed_agents") instanceof Map<?, ?>))
                    throw invalid("Invalid managed_agents extension");
                var ext = cast(event.get("managed_agents"));
                fields(ext, Set.of("intent", "turn_id"));
                intent = String.valueOf(ext.get("intent"));
                if (!("user.message".equals(type)
                                ? Set.of("new_turn", "steer", "inject")
                                : Set.of("cancel"))
                        .contains(intent)) throw invalid("Invalid interaction intent");
                if (!"new_turn".equals(intent)) {
                    if (!(ext.get("turn_id") instanceof String text)
                            || text.isBlank()
                            || Set.of("current", "latest").contains(text))
                        throw invalid("Explicit target turn_id is required");
                    target = (String) ext.get("turn_id");
                } else if (ext.containsKey("turn_id"))
                    throw invalid("new_turn must not supply turn_id");
            }
            var content = new ArrayList<Map<String, Object>>();
            if ("user.message".equals(type)) {
                if (!(event.get("content") instanceof List<?> blocks) || blocks.isEmpty())
                    throw invalid("content requires text blocks");
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> value)
                            || !"text".equals(value.get("type"))
                            || !(value.get("text") instanceof String text)
                            || text.isEmpty()
                            || !Set.of("type", "text").containsAll(value.keySet()))
                        throw invalid("unsupported_capability: Only text content is supported");
                    content.add(cast(block));
                }
            }
            commands.add(new Command(type, content, intent, target, explicit));
        }
        long ordinary = commands.stream().filter(value -> "message".equals(value.intent())).count();
        if (ordinary > 1
                || commands.stream().filter(value -> "interrupt".equals(value.intent())).count() > 1
                || ordinary == 1
                        && commands.size() > 1
                        && !(commands.size() == 2
                                && "interrupt".equals(commands.get(0).intent())
                                && "message".equals(commands.get(1).intent())))
            throw invalid("unsupported_capability: Unsupported ordinary event batch");
        String key = headers.getFirst("Idempotency-Key");
        if (key == null && commands.stream().anyMatch(Command::explicit))
            throw new ManagedStateException(
                    "idempotency_key_required", "Explicit intents require Idempotency-Key");
        if (key == null) key = UUID.randomUUID().toString();
        if (key.isBlank() || key.length() > 256) throw invalid("Invalid Idempotency-Key");
        var caller = access.authenticate(id, headers);
        var row =
                resources
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind))
            throw invalid("This API requires a Managed Session");
        for (var command : commands)
            access.authorizeActor(
                    row,
                    caller,
                    Set.of("cancel", "interrupt").contains(command.intent()) ? "cancel" : "invoke");
        var scope = scope(row);
        var protocol = store.protocol();
        var acceptedBatch =
                protocol.accept(
                        scope,
                        actor(id, headers),
                        key,
                        commands,
                        () -> {
                            if (!"active".equals(row.lifecycle))
                                throw new ManagedStateException(
                                        "session_inactive", "Session is not active");
                            var snapshot = agentSnapshot(row);
                            if (commands.stream()
                                    .anyMatch(command -> "user.message".equals(command.type())))
                                validateAgent(snapshot);
                            else
                                // Tightened work admission must not prevent cancelling old Turns.
                                ManagedSessionProtocol.validateAgent(
                                        Map.of("agentSnapshot", snapshot));
                            var policy = access.policy(row);
                            if (policy.get("application") instanceof Map<?, ?> app)
                                store.applicationPolicy(scope, cast(app));
                            for (var command : commands)
                                if ("user.message".equals(command.type()))
                                    validation.validateInput(
                                            policy,
                                            Map.of(
                                                    "input",
                                                    List.of(
                                                            Map.of(
                                                                    "role",
                                                                    "user",
                                                                    "content",
                                                                    command.content()))));
                        });
        var receipt = acceptedBatch.receipt();
        // Only committed identities are delivered. Recovery reuses these same outbox records.
        for (Object raw : acceptedBatch.replayed() ? List.of() : (List<?>) receipt.get("data")) {
            var ext = cast(cast(raw).get("managed_agents"));
            try {
                if (ext.get("input_id") instanceof String input)
                    inputs.deliver(scope, store.input(scope, input));
                else if (ext.get("turn_id") instanceof String turn) publisher.publish(scope, turn);
            } catch (ManagedTransportException | RedisException unavailable) {
                // Durable delivery retries the original operation.
            } catch (ManagedStateException pending) {
                if (!"publication_pending".equals(pending.code())) throw pending;
            }
        }
        return protocol.receipt(receipt, commands, extended);
    }

    public Map<String, Object> history(
            String id, Map<String, List<String>> query, HttpHeaders headers) {
        var scope = scope(resource(id, headers, "read"));
        return store.protocol().history(scope, actor(id, headers), query, extensions(headers));
    }

    public Map<String, Object> output(
            String id, String turn, String after, int limit, HttpHeaders headers) {
        return store.protocol().output(scope(resource(id, headers, "read")), turn, after, limit);
    }

    public static boolean extensions(HttpHeaders headers) {
        String value = headers.getFirst("Managed-Agents-Extensions");
        if (value == null) return false;
        var values = Set.of(value.split(",\\s*"));
        if (!Set.of("turn-inputs-v1", "session-queue-v1", "session-budgets-v1").containsAll(values))
            throw invalid("unsupported_capability: Unknown output extension");
        return values.contains("turn-inputs-v1") || values.contains("session-queue-v1");
    }

    public static boolean queueExtension(HttpHeaders headers) {
        extensions(headers);
        String value = headers.getFirst("Managed-Agents-Extensions");
        return value != null && List.of(value.split(",\\s*")).contains("session-queue-v1");
    }

    private Map<String, Object> sendQueueEvent(String id, List<?> values, HttpHeaders headers) {
        if (!queueExtension(headers))
            throw invalid("unsupported_capability: session-queue-v1 is required");
        if (values.size() != 1) throw invalid("Queue mutations require a single-event batch");
        var event = cast(values.get(0));
        fields(event, Set.of("type", "managed_agents"));
        String type = String.valueOf(event.get("type"));
        if (!Set.of("user.queue.convert_to_steer", "user.queue.withdraw").contains(type))
            throw invalid("Unsupported queue command");
        if (!(event.get("managed_agents") instanceof Map<?, ?>))
            throw invalid("Queue command extension is required");
        var ext = cast(event.get("managed_agents"));
        boolean convert = "user.queue.convert_to_steer".equals(type);
        fields(
                ext,
                convert
                        ? Set.of("queue_entry_id", "expected_entry_version", "target_turn_id")
                        : Set.of("queue_entry_id", "expected_entry_version"));
        if (!(ext.get("queue_entry_id") instanceof String entry)
                || entry.isBlank()
                || entry.length() > 128) throw invalid("queue_entry_id is required");
        if (!(ext.get("expected_entry_version") instanceof Number version)
                || version.longValue() < 1
                || version.doubleValue() != version.longValue())
            throw invalid("expected_entry_version must be a positive integer");
        String target = null;
        if (convert) {
            if (!(ext.get("target_turn_id") instanceof String text)
                    || text.isBlank()
                    || text.length() > 128
                    || Set.of("current", "latest").contains(text))
                throw invalid("A concrete target_turn_id is required");
            target = text;
        }
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 256)
            throw invalid("Idempotency-Key is required");
        var scope = scope(resource(id, headers, convert ? "invoke" : "cancel"));
        var accepted =
                store.protocol()
                        .acceptQueue(
                                scope,
                                actor(id, headers),
                                key,
                                new ManagedSessionQueue.Mutation(
                                        entry, version.longValue(), target, convert),
                                () -> {});
        if (!accepted.replayed()) {
            var response =
                    cast(
                            cast(((List<?>) accepted.receipt().get("data")).get(0))
                                    .get("managed_agents"));
            if (response.get("input_id") instanceof String input) {
                try {
                    inputs.deliver(scope, store.input(scope, input));
                } catch (ManagedTransportException | RedisException pending) {
                    /* durable Input delivery retries */
                }
            }
        }
        return accepted.receipt();
    }

    public Map<String, Object> queue(
            String id, boolean commands, String after, int limit, HttpHeaders headers) {
        if (!queueExtension(headers))
            throw invalid("unsupported_capability: session-queue-v1 is required");
        return store.queue().page(scope(resource(id, headers, "read")), commands, after, limit);
    }

    public Map<String, Object> queueItem(
            String id, String item, boolean command, HttpHeaders headers) {
        if (!queueExtension(headers))
            throw invalid("unsupported_capability: session-queue-v1 is required");
        var scope = scope(resource(id, headers, "read"));
        return command ? store.queue().command(scope, item) : store.queue().entry(scope, item);
    }

    public Map<String, Object> projection(String id, long minimum, HttpHeaders headers) {
        if (minimum < 0) throw invalid("Projection sequence must be nonnegative");
        extensions(headers);
        var scope = scope(resource(id, headers, "read"));
        var progress = journal.projections().progress(scope);
        return Map.of(
                "session_id",
                id,
                "generation",
                1,
                "asynchronous",
                true,
                "accepted_through",
                progress.acceptedThrough(),
                "projected_through",
                progress.projectedThrough(),
                "lag",
                progress.acceptedThrough() - progress.projectedThrough(),
                "ready",
                progress.projectedThrough() >= minimum);
    }

    public Map<String, Object> submit(String id, Map<String, Object> body, HttpHeaders headers) {
        fields(body, Set.of("input"));
        var row = resource(id, headers, "invoke");
        validateAgent(agentSnapshot(row));
        var input = mapper.convertValue(body.get("input"), AgentSessionInput.class);
        if (input == null) throw invalid("Turn requires input");
        var normalized = input.normalized();
        if (!"active".equals(row.lifecycle))
            throw new ManagedStateException("session_inactive", "Session is not active");
        validation.validateInput(access.policy(row), normalized);
        var scope = scope(row);
        journal.files().validateInput(scope, normalized);
        var policy = access.policy(row);
        if (policy.get("application") instanceof Map<?, ?> app)
            store.applicationPolicy(scope, cast(app));
        var turn = store.acceptTurn(scope, actor(id, headers), key(headers), json(normalized));
        try {
            publisher.publish(scope, turn.id());
        } catch (ManagedTransportException | RedisException unavailable) {
            /* Accepted DB outbox remains pending. */
        } catch (ManagedStateException pending) {
            if (!"publication_pending".equals(pending.code())) throw pending;
        }
        return turnView(
                id,
                store.turn(scope, turn.id()),
                "active".equals(store.session(scope).lifecycle()),
                headers);
    }

    public Map<String, Object> turn(String id, String turn, HttpHeaders headers) {
        var row = resource(id, headers, "read");
        var scope = scope(row);
        return turnView(
                id,
                store.turn(scope, turn),
                "active".equals(store.session(scope).lifecycle()),
                headers);
    }

    public Map<String, Object> turns(
            String id, String after, String order, String status, int limit, HttpHeaders headers) {
        return turns(id, after, order, status, limit, headers, null);
    }

    public Map<String, Object> turns(
            String id,
            String after,
            String order,
            String status,
            int limit,
            HttpHeaders headers,
            String eventId) {
        pageSize(limit);
        if (!Set.of("asc", "desc").contains(order)) throw invalid("order must be asc or desc");
        if (status != null
                && !Set.of(
                                "accepted",
                                "preparing",
                                "running",
                                "waiting",
                                "waiting_tool",
                                "completed",
                                "failed",
                                "cancelled")
                        .contains(status)) throw invalid("Invalid Turn status");
        var scope = scope(resource(id, headers, "read"));
        String kind =
                "turns:"
                        + order
                        + ":"
                        + (status == null ? "all" : status)
                        + (eventId == null ? "" : ":event:" + eventId);
        long head = store.turnHead(scope);
        boolean descending = "desc".equals(order);
        long cursor =
                after == null || after.isBlank()
                        ? descending ? Math.addExact(head, 1) : 0
                        : decodePage(scope, kind, after, head);
        var values =
                store.turns(
                        scope,
                        cursor,
                        descending,
                        "completed".equals(status) ? "succeeded" : status,
                        limit + 1,
                        eventId);
        boolean more = values.size() > limit;
        var page = values.subList(0, Math.min(limit, values.size()));
        boolean active = "active".equals(store.session(scope).lifecycle());
        return page(
                page.stream().map(turn -> turnView(id, turn, active, headers)).toList(),
                more,
                more
                        ? encodePage(scope, kind, page.get(page.size() - 1).admissionSequence())
                        : null);
    }

    public Map<String, Object> inputs(
            String id, String turn, String after, int limit, HttpHeaders headers) {
        return inputs(id, turn, after, limit, headers, null, null);
    }

    public Map<String, Object> inputs(
            String id,
            String turn,
            String after,
            int limit,
            HttpHeaders headers,
            String status,
            String eventId) {
        if (status != null && !Set.of("accepted", "applied", "rejected").contains(status))
            throw invalid("Invalid Input status");
        pageSize(limit);
        var scope = scope(resource(id, headers, "read"));
        var state = store.turn(scope, turn);
        String kind =
                "inputs:"
                        + turn
                        + (status == null ? "" : ":status:" + status)
                        + (eventId == null ? "" : ":event:" + eventId);
        long cursor = decodePage(scope, kind, after, state.inputSequence());
        var values = store.inputsAfter(scope, turn, cursor, limit + 1, status, eventId);
        boolean more = values.size() > limit;
        var page = values.subList(0, Math.min(limit, values.size()));
        return page(
                page.stream().map(input -> inputView(scope, input, headers)).toList(),
                more,
                more ? encodePage(scope, kind, page.get(page.size() - 1).sequence()) : null);
    }

    public Map<String, Object> input(
            String id, String turn, Map<String, Object> body, HttpHeaders headers) {
        fields(body, Set.of("type", "action_id", "payload"));
        Type type;
        try {
            type = Type.valueOf(String.valueOf(body.get("type")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("Input type must be steer, inject, reply, or cancel");
        }
        String action = body.get("action_id") instanceof String text ? text : null;
        var caller = access.authenticate(id, headers);
        var row =
                resources
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var scope = scope(row);
        var request =
                type == Type.REPLY
                        ? journal.action(scope, turn, action)
                        : Optional.<ManagedJournalStore.Action>empty();
        String permission =
                type == Type.CANCEL
                        ? "cancel"
                        : type == Type.REPLY
                                ? request.map(
                                                value ->
                                                        "confirmation".equals(value.kind())
                                                                ? "approve"
                                                                : "interact")
                                        .orElse("interact")
                                : "invoke";
        access.authorizeActor(row, caller, permission);
        if (!"managed".equals(row.runtimeKind))
            throw invalid("This API requires a Managed Session");
        if (!"active".equals(row.lifecycle))
            throw new ManagedStateException("session_inactive", "Session is not active");
        if (type == Type.REPLY && request.isEmpty()) throw invalid("Unknown required action");
        Object payload = body.getOrDefault("payload", Map.of());
        if (type == Type.REPLY) {
            if (!(payload instanceof Map<?, ?> answer))
                throw invalid("Reply payload must be an object");
            if ("confirmation".equals(request.orElseThrow().kind())) {
                if (!(answer.get("allow") instanceof Boolean))
                    throw invalid("Confirmation requires allow=true or false");
                if (answer.get("reason") != null && !(answer.get("reason") instanceof String))
                    throw invalid("Confirmation reason must be text");
                if (!Set.of("allow", "reason").containsAll(answer.keySet()))
                    throw invalid("Unknown confirmation reply fields");
            } else SessionToolResultContent.blocks(answer);
        }
        if (type == Type.STEER || type == Type.INJECT) {
            var input = mapper.convertValue(payload, AgentSessionInput.class);
            if (input == null) throw invalid("Input payload is required");
            payload = input.normalized();
            journal.files().validateInput(scope, cast(payload));
            validation.validateInput(access.policy(row), cast(payload));
        }
        var accepted =
                store.acceptInput(
                        scope, turn, actor(id, headers), key(headers), type, action, json(payload));
        try {
            inputs.deliver(scope, accepted);
        } catch (ManagedTransportException | RedisException unavailable) {
            /* HarnessRuntime refills the accepted journal after recovery. */
        }
        return inputView(store.input(scope, accepted.id()));
    }

    public Map<String, Object> input(String id, String turn, String input, HttpHeaders headers) {
        var row = resource(id, headers, "read");
        var value = store.input(scope(row), input);
        if (!turn.equals(value.turnId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return inputView(scope(row), value, headers);
    }

    public Map<String, Object> events(String id, String after, int limit, HttpHeaders headers) {
        pageSize(limit);
        var row = resource(id, headers, "read");
        var scope = scope(row);
        long cursor = decode(scope, after);
        var values = journal.publicEvents(scope, cursor, limit + 1);
        var page = values.subList(0, Math.min(limit, values.size()));
        long next = page.isEmpty() ? cursor : page.get(page.size() - 1).sequence();
        return page(
                page.stream().map(event -> eventView(scope, event)).toList(),
                values.size() > limit,
                encode(scope, next));
    }

    public Map<String, Object> snapshot(String id, int limit, HttpHeaders headers) {
        return snapshot(id, null, limit, headers);
    }

    public Map<String, Object> snapshot(String id, String turn, int limit, HttpHeaders headers) {
        pageSize(limit);
        var scope = scope(resource(id, headers, "read"));
        if (turn != null) store.turn(scope, turn);
        var snapshot = journal.resources().snapshot(scope, turn);
        var pages = new LinkedHashMap<String, Object>();
        for (String kind : ManagedPublicResources.KINDS)
            pages.put(
                    kind,
                    resourcePage(
                            scope,
                            journal.resources().page(scope, snapshot.id(), kind, null, limit)));
        return Map.of(
                "snapshot_id",
                snapshot.id(),
                "as_of",
                encode(scope, snapshot.sequence()),
                "resources",
                pages,
                "usage",
                snapshot.usage());
    }

    public Map<String, Object> usage(String id, String turn, HttpHeaders headers) {
        var scope = scope(resource(id, headers, "read"));
        var usage = journal.resources().usage(scope, turn);
        return Map.of("data", usage.data(), "as_of", encode(scope, usage.sequence()));
    }

    public Map<String, Object> tools(
            String id, String turn, String after, int limit, HttpHeaders headers) {
        pageSize(limit);
        var scope = scope(resource(id, headers, "read"));
        store.turn(scope, turn);
        String key = "";
        if (after != null && !after.isBlank()) {
            try {
                if (after.length() > 4096) throw invalid("Invalid tool cursor");
                var cursor =
                        mapper.readValue(
                                new String(
                                        Base64.getUrlDecoder().decode(after),
                                        StandardCharsets.UTF_8),
                                Map.class);
                if (!scope.messageGroup().equals(cursor.get("scope"))
                        || !turn.equals(cursor.get("turn"))
                        || !(cursor.get("task") instanceof String task)
                        || task.length() > 256) throw invalid("Invalid tool cursor scope");
                key = (String) cursor.get("task");
            } catch (IllegalArgumentException | JsonProcessingException malformed) {
                throw invalid("Invalid tool cursor");
            }
        }
        var result = journal.publicTools(scope, turn, key, limit);
        String next =
                result.next() == null
                        ? null
                        : Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        json(Map.of(
                                                        "scope",
                                                        scope.messageGroup(),
                                                        "turn",
                                                        turn,
                                                        "task",
                                                        result.next()))
                                                .getBytes(StandardCharsets.UTF_8));
        return page(result.data(), result.hasMore(), next);
    }

    public Map<String, Object> resources(
            String id, String snapshot, String kind, String after, int limit, HttpHeaders headers) {
        pageSize(limit);
        var scope = scope(resource(id, headers, "read"));
        return resourcePage(scope, journal.resources().page(scope, snapshot, kind, after, limit));
    }

    public Map<String, Object> resourcePage(
            String id,
            String turn,
            String snapshot,
            String kind,
            String after,
            int limit,
            HttpHeaders headers) {
        pageSize(limit);
        if (!ManagedPublicResources.KINDS.contains(kind)) throw invalid("Unknown resource kind");
        var scope = scope(resource(id, headers, "read"));
        if (turn != null) store.turn(scope, turn);
        if (snapshot == null) {
            if (after != null && !after.isBlank())
                throw invalid("Resource cursor requires snapshot_id");
            snapshot = journal.resources().snapshot(scope, turn).id();
        }
        var page = journal.resources().page(scope, snapshot, kind, after, limit);
        if (!Objects.equals(turn, page.snapshot().turnId()))
            throw invalid("Resource snapshot Turn differs");
        return resourcePage(scope, page);
    }

    public Map<String, Object> capabilities(String id, HttpHeaders headers) {
        var sessionResource = resource(id, headers, "read");
        var result =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "session_id",
                                id,
                                "runtime_kind",
                                "managed",
                                "protocol_revision",
                                ManagedSessionProtocol.REVISION,
                                "compatibility",
                                Map.of(
                                        "level",
                                        "candidate",
                                        "anthropic_version",
                                        "2023-06-01",
                                        "anthropic_beta",
                                        "managed-agents-2026-04-01",
                                        "verified_sdk_versions",
                                        List.of()),
                                "extensions",
                                Map.of(
                                        "turn-inputs-v1",
                                        true,
                                        "session-budgets-v1",
                                        true,
                                        "durable-events-v1",
                                        false,
                                        "session-queue-v1",
                                        store.queue().enabled(scope(sessionResource))),
                                "limits",
                                Map.of(
                                        "events_per_request",
                                        50,
                                        "request_bytes",
                                        1048576,
                                        "max_page_size",
                                        256),
                                "features",
                                Map.of(
                                        "turn_output",
                                        true,
                                        "snapshots",
                                        false,
                                        "session_list",
                                        false,
                                        "session_update",
                                        false,
                                        "session_lifecycle",
                                        false,
                                        "event_deltas",
                                        true,
                                        "tool_confirmation",
                                        false,
                                        "custom_tool_result",
                                        false,
                                        "threads",
                                        false,
                                        "plain_message_while_running",
                                        false),
                                "capabilities",
                                Map.of(
                                        "files",
                                        false,
                                        "file_input",
                                        false,
                                        "artifacts",
                                        false,
                                        "budget",
                                        true,
                                        "checkpoints",
                                        false,
                                        "webhooks",
                                        false,
                                        "checkpoint_restore",
                                        false,
                                        "fork",
                                        false)));
        result.put("input_event_types", List.of("user.message", "user.interrupt"));
        result.put("intents", List.of("new_turn", "steer", "inject", "cancel"));
        result.put("content_types", List.of("text"));
        return result;
    }

    public Map<String, Object> artifacts(
            String id, String turn, String snapshot, String after, int limit, HttpHeaders headers) {
        pageSize(limit);
        var scope = scope(resource(id, headers, "read"));
        if (turn != null) store.turn(scope, turn);
        if (snapshot == null) {
            if (after != null && !after.isBlank())
                throw invalid("Artifact cursor requires snapshot_id");
            snapshot = journal.resources().snapshot(scope, turn).id();
        }
        var page = journal.resources().page(scope, snapshot, "artifacts", after, limit);
        if (!Objects.equals(turn, page.snapshot().turnId()))
            throw invalid("Artifact snapshot Turn differs");
        return resourcePage(scope, page);
    }

    public Map<String, Object> capabilities(String id, String turn, HttpHeaders headers) {
        var row = resource(id, headers, "read");
        var state = store.turn(scope(row), turn);
        boolean open = "active".equals(store.session(scope(row)).lifecycle()) && state.inputsOpen();
        var commands = open ? List.of("steer", "inject", "cancel") : List.of();
        return Map.of(
                "runtime_kind",
                "managed",
                "configuration_revision",
                String.valueOf(row.revision),
                "available_commands",
                commands,
                "supported_commands",
                Map.of(
                        "steer", true, "inject", true, "cancel", true, "actions", false, "resume",
                        false),
                "checkpoint_restore",
                false,
                "action_scope",
                "turn",
                "files",
                false,
                "file_input",
                false,
                "artifacts",
                false);
    }

    ManagedSessionProtocol protocol() {
        return store.protocol();
    }

    void validateAgent(Map<String, Object> snapshot) {
        ManagedSessionProtocol.validateAgent(Map.of("agentSnapshot", snapshot), harnessType);
    }

    List<Map<String, Object>> agentToolsets(Map<String, Object> snapshot) {
        return ManagedSessionProtocol.agentToolsets(snapshot, harnessType);
    }

    Map<String, Object> agentSnapshot(AgentServiceSessionEntity row) {
        var resolved = catalog.resolve(scope(row));
        var snapshot = resolved.agentSnapshot();
        if (snapshot == null)
            throw new ManagedStateException(
                    "definition_missing", "Session has no pinned Agent configuration");
        try {
            return EffectiveAgentDefinition.resolve(
                    snapshot,
                    resolved.session() == null ? null : resolved.session().agentOverridesJson());
        } catch (IllegalArgumentException invalid) {
            throw new ManagedStateException("invalid_request", invalid.getMessage());
        }
    }

    Map<String, Object> runtime(AgentServiceSessionEntity row) {
        var scope = scope(row);
        var usage = journal.resources().usage(scope, null);
        String status = store.protocol().status(scope);
        return Map.of("status", status, "usage", usage.data(), "public_sequence", usage.sequence());
    }

    private static Map<String, Object> resourcePage(ManagedScope scope, Page page) {
        var view = new LinkedHashMap<String, Object>();
        view.put("snapshot_id", page.snapshot().id());
        view.put("as_of", encode(scope, page.snapshot().sequence()));
        view.put("expires_at", page.snapshot().expiresAt());
        view.put("data", page.data());
        view.put("next_cursor", page.nextCursor());
        view.put("has_more", page.hasMore());
        return view;
    }

    public Flux<ServerSentEvent<String>> stream(String id, String after, HttpHeaders headers) {
        return stream(id, after, headers, List.of());
    }

    public Flux<ServerSentEvent<String>> stream(
            String id, String after, HttpHeaders headers, List<String> deltas) {
        if (deltas.size() > 100
                || deltas.stream().anyMatch(value -> !"agent.message".equals(value)))
            throw invalid("unsupported_capability: only agent.message previews are supported");
        if (after != null) throw invalid("unsupported_capability: SSE resume is not supported");
        boolean extended = extensions(headers);
        return Flux.defer(
                () -> {
                    if (!sseConnections.tryAcquire())
                        return Flux.error(
                                new ManagedStateException(
                                        "admission_limited", "SSE connection capacity exceeded"));
                    return Mono.fromCallable(() -> scope(resource(id, headers, "read")))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(
                                    scope ->
                                            streamFacts(
                                                    id,
                                                    scope,
                                                    headers,
                                                    extended,
                                                    !deltas.isEmpty()))
                            .doFinally(signal -> sseConnections.release());
                });
    }

    private Flux<ServerSentEvent<String>> streamFacts(
            String id, ManagedScope scope, HttpHeaders headers, boolean extended, boolean live) {
        var protocol = store.protocol();
        return Mono.fromCallable(() -> protocol.head(scope))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        head -> {
                            var preview = live && previews != null ? previews.open(scope) : null;
                            var cursor = new AtomicLong(head);
                            Flux<ServerSentEvent<String>> facts =
                                    Flux.interval(Duration.ZERO, Duration.ofMillis(250))
                                            .onBackpressureLatest()
                                            .concatMap(
                                                    tick ->
                                                            Mono.fromCallable(
                                                                            () ->
                                                                                    protocol
                                                                                            .changes(
                                                                                                    scope,
                                                                                                    cursor
                                                                                                            .get(),
                                                                                                    256))
                                                                    .subscribeOn(
                                                                            Schedulers
                                                                                    .boundedElastic()),
                                                    1)
                                            .concatMapIterable(page -> page, 1)
                                            .doOnNext(
                                                    change -> {
                                                        cursor.set(change.sequence());
                                                        if (preview != null)
                                                            preview.reconcile(change.event());
                                                    })
                                            .filter(
                                                    change ->
                                                            change.event().get("processed_at")
                                                                    != null)
                                            .filter(
                                                    change ->
                                                            extended
                                                                    || !String.valueOf(
                                                                                    change.event()
                                                                                            .get(
                                                                                                    "type"))
                                                                            .startsWith(
                                                                                    "managed_agents."))
                                            .map(
                                                    change ->
                                                            ServerSentEvent.builder(
                                                                            json(
                                                                                    ManagedSessionProtocol
                                                                                            .represent(
                                                                                                    change
                                                                                                            .event(),
                                                                                                    extended)))
                                                                    .event(
                                                                            String.valueOf(
                                                                                    change.event()
                                                                                            .get(
                                                                                                    "type")))
                                                                    .build());
                            Flux<ServerSentEvent<String>> keepalive =
                                    Flux.interval(heartbeat)
                                            .onBackpressureDrop()
                                            .map(
                                                    tick ->
                                                            ServerSentEvent.<String>builder()
                                                                    .comment("keepalive")
                                                                    .build());
                            Flux<ServerSentEvent<String>> authorizationChecks =
                                    Flux.interval(authorization)
                                            .concatMap(tick -> authorizeStream(id, headers), 1);
                            // This first frame flushes headers only after the durable boundary has
                            // been captured.
                            return Flux.merge(
                                            facts,
                                            keepalive,
                                            authorizationChecks,
                                            preview == null ? Flux.empty() : preview.events())
                                    .filter(frame -> preview == null || preview.deliver(frame))
                                    .doFinally(
                                            signal -> {
                                                if (preview != null) preview.close();
                                            })
                                    .startWith(
                                            ServerSentEvent.<String>builder()
                                                    .comment("connected")
                                                    .build());
                        });
    }

    private Mono<ServerSentEvent<String>> authorizeStream(String id, HttpHeaders headers) {
        return Mono.fromRunnable(() -> access.authorize(id, headers, "read"))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    private ServerSentEvent<String> frame(ManagedScope scope, PublicEvent event) {
        return ServerSentEvent.builder(json(eventView(scope, event)))
                .id(encode(scope, event.sequence()))
                .event(event.type())
                .build();
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.runtime.publication-retry-ms:1000}",
            scheduler = "agentServiceTurnDeliveryScheduler")
    public void retryPublications() {
        publisher.retry(8);
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.runtime.snapshot-cleanup-ms:60000}",
            scheduler = "agentServiceTurnDeliveryScheduler")
    public void expireSnapshots() {
        journal.resources().expireSnapshots(64);
    }

    private AgentServiceSessionEntity resource(String id, HttpHeaders headers, String permission) {
        access.authorize(id, headers, permission);
        var row =
                resources
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind))
            throw invalid("This API requires a Managed Session");
        return row;
    }

    private String actor(String id, HttpHeaders headers) {
        try {
            return mapper.copy()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(access.authenticate(id, headers));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode caller identity", invalid);
        }
    }

    private static ManagedScope scope(AgentServiceSessionEntity row) {
        return new ManagedScope(row.tenant, row.namespace, row.id);
    }

    private static String key(HttpHeaders headers) {
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 256)
            throw invalid("Idempotency-Key is required and limited to 256 characters");
        return key;
    }

    private Map<String, Object> turnView(
            String session, Turn turn, boolean active, HttpHeaders headers) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", turn.id());
        view.put("type", "turn");
        view.put("session_id", session);
        view.put("created_at", Instant.ofEpochMilli(turn.createdAt()).toString());
        view.put("admission_sequence", turn.admissionSequence());
        view.put("status", ManagedJournalStore.publicTurnStatus(turn.status()));
        view.put("delivery_status", turn.deliveryStatus());
        view.put("inputs_open", active && turn.inputsOpen());
        if (turn.publicationSequence() != null)
            view.put("publication_sequence", turn.publicationSequence());
        if (AgentServiceSessionAccess.runtimeHeaders(headers)) return view;
        var scopes = store.sessionsById(session);
        if (scopes.size() == 1) {
            var scope = scopes.get(0).scope();
            var link = store.protocol().link(scope, turn.id(), null);
            view.putAll(store.protocol().turnInfo(scope, turn.id()));
            view.put("input_event_ids", link.isEmpty() ? List.of() : List.of(link.get("event_id")));
            view.put("execution_sequence", turn.publicationSequence());
            view.put("cancel_requested", journal.cancelRequested(scope, turn.id()));
            view.put(
                    "output_available",
                    store.protocol().output(scope, turn.id(), null, 1).get("output_available"));
            view.put("output_url", "/v1/sessions/" + session + "/turns/" + turn.id() + "/output");
        }
        return view;
    }

    private Map<String, Object> inputView(ManagedScope scope, Input input, HttpHeaders headers) {
        var view = inputView(input);
        if (AgentServiceSessionAccess.runtimeHeaders(headers)) return view;
        view.put("session_id", scope.sessionId());
        view.putAll(store.protocol().link(scope, input.turnId(), input.id()));
        if ("applied".equals(input.status()) && input.rejectionReason() != null) {
            view.remove("rejection_reason");
            view.put("resolution_reason", input.rejectionReason());
        }
        return view;
    }

    private static Map<String, Object> inputView(Input input) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", input.id());
        view.put("type", "input");
        view.put("input_type", input.type().name().toLowerCase(Locale.ROOT));
        view.put("turn_id", input.turnId());
        view.put("status", input.status());
        view.put("delivery_status", input.deliveryStatus());
        view.put("sequence", input.sequence());
        if (input.actionId() != null) view.put("action_id", input.actionId());
        if (input.rejectionReason() != null) view.put("rejection_reason", input.rejectionReason());
        return view;
    }

    private Map<String, Object> eventView(ManagedScope scope, PublicEvent event) {
        String cursor = encode(scope, event.sequence());
        var data = new LinkedHashMap<>(cast(parse(event.payload())));
        data.remove("source");
        return Map.of(
                "schema_version", 1,
                "id", event.sourceId(),
                "cursor", cursor,
                "session_id", scope.sessionId(),
                "type", event.type(),
                "created_at", event.createdAt(),
                "data", data);
    }

    private static Map<String, Object> page(List<?> data, boolean more, String next) {
        var view = new LinkedHashMap<String, Object>();
        view.put("data", data);
        view.put("has_more", more);
        view.put("next_cursor", next);
        return view;
    }

    private static void pageSize(int limit) {
        if (limit < 1 || limit > 256) throw invalid("limit must be 1..256");
    }

    private static String encodePage(ManagedScope scope, String kind, long sequence) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (scope.messageGroup() + ":" + kind + ":" + sequence)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static long decodePage(ManagedScope scope, String kind, String cursor, long head) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            if (cursor.length() > 2048) throw invalid("Invalid page cursor");
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = scope.messageGroup() + ":" + kind + ":";
            if (!decoded.startsWith(prefix))
                throw invalid("Page cursor has a different scope or query");
            long sequence = Long.parseLong(decoded.substring(prefix.length()));
            if (sequence < 0 || sequence > head)
                throw invalid("Page cursor is outside the admitted history");
            return sequence;
        } catch (IllegalArgumentException malformed) {
            throw invalid("Invalid page cursor");
        }
    }

    private static void fields(Map<String, Object> body, Set<String> allowed) {
        if (!allowed.containsAll(body.keySet())) throw invalid("Unknown request fields");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw invalid("Invalid input JSON");
        }
    }

    private Object parse(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored public event", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    static String encode(ManagedScope scope, long cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (scope.messageGroup() + ":" + cursor).getBytes(StandardCharsets.UTF_8));
    }

    private long decode(ManagedScope scope, String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String prefix = scope.messageGroup() + ":";
            if (!decoded.startsWith(prefix))
                throw invalid("Event cursor belongs to another Session");
            long cursor = Long.parseLong(decoded.substring(prefix.length()));
            if (cursor < 0 || cursor > journal.publicHead(scope))
                throw invalid("Event cursor is outside the committed history");
            return cursor;
        } catch (IllegalArgumentException malformed) {
            throw invalid("Invalid event cursor");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
