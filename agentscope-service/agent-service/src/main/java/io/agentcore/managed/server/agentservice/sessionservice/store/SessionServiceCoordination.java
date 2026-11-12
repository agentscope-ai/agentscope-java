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
package io.agentcore.managed.server.agentservice.sessionservice.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ExecutionOwner;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedProcessOwnership;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionQueue;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.managed.ManagedSuspensions;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerLeaseStore;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerLeaseStore.Lease;
import io.agentcore.managed.server.runtime.managed.RocketMqTurnReceipt;
import io.agentcore.managed.server.runtime.managed.SessionTurnAckLedger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service-owned metadata operations for the AgentScope prepared-commit execution path. */
@Component
public final class SessionServiceCoordination {
    private final DataSource source;
    private final ObjectMapper mapper;
    private final ManagedAdmissionStore admission;
    private final ManagedSubagents children;
    private final ManagedMcpCatalog catalogs;
    private final ManagedWorkerLeaseStore leases;
    private final SessionTurnAckLedger acknowledgements;
    private final ProtocolSchemas schemas = new ProtocolSchemas();
    private final Duration fragment;
    private final Duration tool;
    @Autowired private HarnessRunConfiguration configurations;

    public SessionServiceCoordination(
            DataSource source,
            ObjectMapper mapper,
            ManagedAdmissionStore admission,
            ManagedSubagents children,
            ManagedMcpCatalog catalogs,
            @Value("${managed-agents.runtime.fragment-deadline-seconds:600}") long fragmentSeconds,
            @Value("${managed-agents.runtime.tool-collector-deadline-seconds:3600}")
                    long toolSeconds) {
        this.source = source;
        this.mapper = mapper;
        this.admission = admission;
        this.children = children;
        this.catalogs = catalogs;
        leases = new ManagedWorkerLeaseStore(source);
        acknowledgements = new SessionTurnAckLedger(source, mapper);
        fragment = Duration.ofSeconds(fragmentSeconds);
        tool = Duration.ofSeconds(toolSeconds);
        if (fragment.isNegative() || fragment.isZero() || tool.isNegative() || tool.isZero())
            throw new IllegalArgumentException("Execution deadlines must be positive");
    }

    public Mono<JsonNode> invoke(JsonNode request) {
        return Mono.defer(
                        () -> {
                            schemas.request("prepared/coordination", request);
                            String operation = request.required("operation").asText();
                            var data = request.required("data");
                            return Mono.fromCallable(() -> envelope(execute(operation, data)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Object execute(String operation, JsonNode data) {
        return switch (operation) {
            case "model-configuration" -> {
                var owner =
                        new ExecutionOwner(
                                scope(data),
                                text(data, "turnId"),
                                data.required("epoch").longValue(),
                                text(data, "token"));
                var ownership = new SessionServiceOwnership(source);
                ownership.whileCurrent(owner, () -> true);
                var configuration =
                        configurations.resolveModel(
                                owner.scope(), owner.turnId(), "agentscope-java");
                ownership.whileCurrent(owner, () -> true);
                yield configuration;
            }
            case "process-register" -> leases.register(process(data), duration(data, "ttlMillis"));
            case "process-renew" ->
                    leases.renew(process(data), duration(data, "ttlMillis"))
                            ? data.required("ttlMillis").longValue()
                            : 0L;
            case "process-retire" -> {
                leases.retire(process(data));
                yield true;
            }
            case "session-read" -> admission.session(scope(data));
            case "session-find" -> admission.sessionsById(text(data, "sessionId"));
            case "turn-read" -> admission.turn(scope(data), text(data, "turnId"));
            case "turn-current" -> {
                var turn = admission.session(scope(data)).currentTurnId();
                if (turn == null)
                    throw new ManagedStateException("turn_not_active", "No active Turn");
                yield turn;
            }
            case "turn-publication" ->
                    admission.confirmPublication(
                            scope(data), text(data, "turnId"), text(data, "brokerMessageId"));
            case "turn-claim" ->
                    owned(data)
                            .activate(
                                    scope(data),
                                    text(data, "turnId"),
                                    data.required("publicationSequence").longValue(),
                                    text(data, "token"));
            case "turn-resume" ->
                    owned(data)
                            .activateResume(
                                    read(data, "resume", ManagedSuspensions.Resume.class),
                                    text(data, "token"));
            case "turn-abandon" ->
                    admission.abandonExecution(
                            scope(data),
                            text(data, "turnId"),
                            data.required("epoch").longValue(),
                            text(data, "token"));
            case "turn-check" -> {
                admission.requireEpoch(
                        scope(data), text(data, "turnId"), data.required("epoch").longValue());
                yield true;
            }
            case "input-read" -> admission.input(scope(data), text(data, "inputId"));
            case "input-pending" ->
                    admission.pendingInputs(
                            scope(data), text(data, "turnId"), data.required("limit").intValue());
            case "input-delivered" -> {
                admission.confirmInputDelivery(scope(data), text(data, "inputId"));
                yield true;
            }
            case "input-close" ->
                    owned(data)
                            .closeInputs(
                                    scope(data),
                                    text(data, "turnId"),
                                    data.required("epoch").longValue());
            case "queue-enabled" -> admission.queue().enabled(scope(data));
            case "queue-candidate" -> admission.queue().candidate(scope(data));
            case "queue-due" -> admission.queue().due(data.required("limit").intValue());
            case "queue-acquire" -> admission.queue().acquire(scope(data));
            case "queue-release" -> {
                admission
                        .queue()
                        .release(read(data, "dispatch", ManagedSessionQueue.Dispatch.class));
                yield true;
            }
            case "queue-validate" -> {
                admission.queue().validate(read(data, "ready", ManagedSessionQueue.Ready.class));
                yield true;
            }
            case "queue-observe" -> {
                admission.queue().observe(read(data, "ready", ManagedSessionQueue.Ready.class));
                yield true;
            }
            case "queue-defer" -> {
                admission
                        .queue()
                        .defer(
                                read(data, "ready", ManagedSessionQueue.Ready.class),
                                duration(data, "delayMillis"));
                yield true;
            }
            case "ack-capture" -> {
                acknowledgements.capture(
                        text(data, "topic"),
                        text(data, "group"),
                        read(data, "command", Command.class),
                        read(data, "receipt", RocketMqTurnReceipt.class),
                        duration(data, "delayMillis"));
                yield true;
            }
            case "ack-claim" ->
                    acknowledgements.claim(
                            text(data, "topic"),
                            text(data, "group"),
                            data.required("limit").intValue());
            case "ack-complete" -> {
                acknowledgements.complete(
                        text(data, "topic"), text(data, "group"), text(data, "receiptId"));
                yield true;
            }
            case "mcp-lookup" ->
                    catalogs.lookup(
                            scope(data),
                            text(data, "turnId"),
                            text(data, "catalogId"),
                            strings(data, "requiredServers"));
            case "mcp-pin" ->
                    catalogs.pin(
                            scope(data),
                            text(data, "turnId"),
                            text(data, "catalogId"),
                            strings(data, "requiredServers"),
                            read(data, "description", ManagedMcpCatalog.Description.class));
            case "mcp-cancelled" ->
                    catalogs.cancelledBeforeInference(scope(data), text(data, "turnId"));
            case "child-find" -> children.find(scope(data), text(data, "taskId")).orElse(null);
            case "child-parent" -> children.parent(scope(data)).orElse(null);
            case "child-parent-turn" ->
                    children.parent(scope(data), text(data, "turnId")).orElse(null);
            default -> throw new IllegalArgumentException("Unknown coordination operation");
        };
    }

    private ManagedAdmissionStore owned(JsonNode data) {
        return admission.ownedBy(
                ManagedProcessOwnership.attached(source, process(data), fragment, tool));
    }

    private List<String> strings(JsonNode data, String key) {
        return mapper.convertValue(
                data.required(key),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private Lease process(JsonNode data) {
        return read(data, "process", Lease.class);
    }

    private ManagedScope scope(JsonNode data) {
        return read(data, "scope", ManagedScope.class);
    }

    private String text(JsonNode data, String key) {
        return data.required(key).asText();
    }

    private Duration duration(JsonNode data, String key) {
        return Duration.ofMillis(data.required(key).longValue());
    }

    private <T> T read(JsonNode data, String key, Class<T> type) {
        return mapper.convertValue(data.required(key), type);
    }

    private JsonNode envelope(Object value) {
        return mapper.valueToTree(
                Map.of("protocolVersion", "0.1.0", "result", mapper.valueToTree(value)));
    }
}
