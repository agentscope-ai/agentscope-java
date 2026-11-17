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

package io.agentscope.extensions.durable.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableProtocol.Endpoint;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionResources;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Host wires its authentication/authorization and a blocking-I/O executor explicitly. */
public final class SessionBackendHandler implements HttpHandler {
    @FunctionalInterface
    public interface Authorizer {
        /** Session is null for discovery/capabilities; restrict discovery to schedulers. */
        void authorize(HttpExchange exchange, String operation, SessionKey session);
    }

    private final SessionBackend backend;
    private final Authorizer authorizer;

    public SessionBackendHandler(SessionBackend backend, Authorizer authorizer) {
        this.backend = Objects.requireNonNull(backend);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    private static <T> T get(JsonNode payload, String field, Class<T> type) {
        return DurableJson.convert(payload.get(field), type);
    }

    private static String text(JsonNode payload, String field) {
        var value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    object(exchange);
                    return;
                }
                var request = DurableHttp.request(exchange, Endpoint.BACKEND);
                if ("invokeObject".equals(request.path("operation").asText())) {
                    var envelope = request.path("payload");
                    var scope = get(envelope, "session", SessionKey.class);
                    String operation = envelope.path("operation").asText();
                    authorizer.authorize(exchange, operation, scope);
                    byte[] content =
                            backend.readObject(scope, get(envelope, "reference", Reference.class));
                    request = DurableJson.parse(new String(content, StandardCharsets.UTF_8));
                    DurableProtocol.validateRequest(Endpoint.BACKEND, request);
                    if (!operation.equals(request.path("operation").asText())
                            || !scope.equals(DurableHttp.session(request.path("payload"))))
                        throw new DurableException(
                                DurableException.Code.INVALID,
                                "Large request scope or operation differs");
                }
                String op = request.path("operation").asText();
                var p = request.path("payload");
                var lease = p.has("lease") ? get(p, "lease", Lease.class) : null;
                var key = DurableHttp.session(p);
                var thread =
                        p.has("thread")
                                ? get(p, "thread", ThreadKey.class)
                                : lease != null ? lease.thread() : null;
                authorizer.authorize(exchange, op, key);
                Object result;
                switch (op) {
                    case "capabilities" -> result = backend.capabilities();
                    case "session" -> result = backend.session(key);
                    case "listSessions" ->
                            result =
                                    backend.listSessions(
                                            text(p, "namespace"),
                                            text(p, "cursor"),
                                            p.path("limit").asInt());
                    case "turns" ->
                            result =
                                    backend.turns(
                                            thread, text(p, "cursor"), p.path("limit").asInt());
                    case "history" ->
                            result =
                                    backend.history(
                                            thread, text(p, "cursor"), p.path("limit").asInt());
                    case "interactions" -> result = backend.interactions(thread, text(p, "turnId"));
                    case "control" ->
                            result =
                                    backend.control(
                                            thread, get(p, "command", ControlCommand.class));

                    case "putObject" ->
                            result =
                                    backend.putObject(
                                            key,
                                            Base64.getDecoder().decode(p.path("data").asText()));
                    case "readObject" ->
                            result =
                                    Base64.getEncoder()
                                            .encodeToString(
                                                    backend.readObject(
                                                            key,
                                                            get(p, "reference", Reference.class)));
                    case "create" ->
                            result = backend.create(get(p, "definition", SessionSpec.class));
                    case "createThread" ->
                            result = backend.createThread(get(p, "definition", ThreadSpec.class));
                    case "thread" -> result = backend.thread(thread);
                    case "threads" ->
                            result =
                                    backend.threads(
                                            key, text(p, "cursor"), p.path("limit").asInt());
                    case "closeSession" -> result = backend.closeSession(key);
                    case "closeThread" -> result = backend.closeThread(thread);
                    case "admission" ->
                            result = backend.admission(key, text(p, "requestId")).orElse(null);
                    case "submit" ->
                            result = backend.submit(thread, text(p, "requestId"), p.get("input"));
                    case "sessions" ->
                            result = backend.sessions(text(p, "after"), p.path("limit").asInt());
                    case "ready" ->
                            result =
                                    backend.ready(
                                            List.of(
                                                    DurableJson.convert(
                                                            p.get("harnesses"),
                                                            HarnessSelector[].class)),
                                            p.path("limit").asInt());
                    case "turn" -> result = backend.turn(thread, text(p, "turnId"));
                    case "prepareRun" -> result = backend.prepareRun(lease, text(p, "requestId"));
                    case "run" -> result = backend.run(thread, text(p, "turnId"), text(p, "runId"));
                    case "runs" ->
                            result =
                                    backend.runs(
                                            thread,
                                            text(p, "turnId"),
                                            text(p, "cursor"),
                                            p.path("limit").asInt());
                    case "step" ->
                            result =
                                    backend.step(
                                            thread,
                                            text(p, "turnId"),
                                            text(p, "runId"),
                                            text(p, "stepId"));
                    case "steps" ->
                            result =
                                    backend.steps(
                                            thread,
                                            text(p, "turnId"),
                                            text(p, "runId"),
                                            text(p, "cursor"),
                                            p.path("limit").asInt());
                    case "modelCall" ->
                            result =
                                    backend.modelCall(
                                            thread,
                                            text(p, "turnId"),
                                            text(p, "runId"),
                                            text(p, "modelCallId"));
                    case "modelCalls" ->
                            result =
                                    backend.modelCalls(
                                            thread,
                                            text(p, "turnId"),
                                            text(p, "runId"),
                                            text(p, "stepId"),
                                            text(p, "cursor"),
                                            p.path("limit").asInt());
                    case "claim" ->
                            result =
                                    backend.claim(
                                                    thread,
                                                    text(p, "owner"),
                                                    Duration.ofMillis(p.path("duration").asLong()))
                                            .orElse(null);
                    case "renew" -> {
                        backend.renew(lease, Duration.ofMillis(p.path("duration").asLong()));
                        result = null;
                    }
                    case "release" -> {
                        backend.release(lease, Duration.ofMillis(p.path("delay").asLong()));
                        result = null;
                    }
                    case "frame" -> result = backend.frame(lease);
                    case "recordFailure" ->
                            result = backend.recordFailure(lease, get(p, "failure", Failure.class));
                    case "commit" -> result = backend.commit(lease, get(p, "commit", Commit.class));
                    case "journal" ->
                            result =
                                    backend.journal(
                                            lease,
                                            p.path("after").asLong(),
                                            p.path("limit").asInt());
                    case "receipt" ->
                            result =
                                    backend.receipt(thread, text(p, "turnId"), text(p, "commitId"))
                                            .orElse(null);
                    case "execution" -> result = backend.execution(thread, text(p, "executionId"));
                    case "delegate", "delegation", "cancelDelegation" -> {
                        var parent = get(p, "request", ExecutionRequest.class);
                        if (!thread.equals(parent.thread()))
                            throw new DurableException(
                                    DurableException.Code.INVALID,
                                    "Delegation request scope differs");
                        result =
                                switch (op) {
                                    case "delegate" ->
                                            backend.delegate(
                                                    parent,
                                                    get(p, "target", DelegationTarget.class));
                                    case "delegation" -> backend.delegation(parent).orElse(null);
                                    default ->
                                            backend.cancelDelegation(
                                                    parent, text(p, "childTurnId"));
                                };
                    }
                    case "beginDispatch" ->
                            result = backend.beginDispatch(lease, text(p, "executionId"));
                    case "resource" ->
                            result =
                                    backend.resource(
                                            lease,
                                            get(p, "request", SessionResources.Request.class));
                    case "checkpointResources" ->
                            result =
                                    backend.checkpointResources(
                                            thread, p.path("position").asLong());
                    case "acceptExecutionEvent" -> {
                        var event = get(p, "event", ExecutionMessages.Event.class);
                        if (!thread.equals(event.request().thread()))
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Execution event scope differs");
                        backend.acceptExecutionEvent(event);
                        result = null;
                    }
                    case "acceptResult" -> {
                        backend.acceptResult(
                                thread,
                                text(p, "executionId"),
                                get(p, "result", ExecutionResult.class));
                        result = null;
                    }
                    case "signal" -> {
                        backend.signal(thread, text(p, "turnId"), get(p, "signal", Signal.class));
                        result = null;
                    }
                    case "withdraw" -> {
                        backend.withdraw(
                                thread,
                                text(p, "turnId"),
                                text(p, "signalId"),
                                text(p, "requestId"));
                        result = null;
                    }
                    case "cancel" -> {
                        backend.cancel(thread, text(p, "turnId"), text(p, "requestId"));
                        result = null;
                    }
                    case "snapshot" -> result = backend.snapshot(key);
                    case "events" ->
                            result =
                                    backend.events(key, text(p, "cursor"), p.path("limit").asInt());
                    case "checkpoints" ->
                            result =
                                    backend.checkpoints(
                                            thread,
                                            p.path("after").asLong(),
                                            p.path("limit").asInt());
                    case "checkpoint" ->
                            result = backend.checkpoint(thread, p.path("position").asLong());
                    case "checkpointJournal" ->
                            result =
                                    backend.checkpointJournal(
                                            thread,
                                            p.path("position").asLong(),
                                            p.path("after").asLong(),
                                            p.path("limit").asInt());
                    case "restore" -> {
                        var restoration = get(p, "restoration", Restoration.class);
                        if (!thread.equals(restoration.target().key()))
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Restoration scope differs");
                        result = backend.restore(restoration);
                    }
                    case "pruneEvents" -> {
                        backend.pruneEvents(key, text(p, "throughCursor"));
                        result = null;
                    }
                    default ->
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Unknown operation");
                }
                DurableHttp.result(exchange, result);
            } catch (RuntimeException error) {
                DurableHttp.error(exchange, error);
            }
        }
    }

    private void object(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!Set.of("PUT", "GET").contains(method)
                || !DurableProtocol.VERSION.equals(
                        exchange.getRequestHeaders().getFirst("X-Durable-Version")))
            throw new DurableException(DurableException.Code.INVALID, "Invalid object request");
        String header = exchange.getRequestHeaders().getFirst("X-Durable-Object");
        if (header == null || header.length() > 4096)
            throw new DurableException(DurableException.Code.INVALID, "Invalid object metadata");
        var metadata =
                DurableJson.parse(
                        new String(Base64.getUrlDecoder().decode(header), StandardCharsets.UTF_8));
        DurableProtocol.validate("objectRequest", metadata);
        var key = get(metadata, "session", SessionKey.class);
        String operation = method.equals("PUT") ? "putObject" : "readObject";
        authorizer.authorize(exchange, operation, key);
        if (method.equals("PUT")) {
            byte[] bytes = exchange.getRequestBody().readNBytes(ObjectStore.MAX_BYTES + 1);
            if (bytes.length > ObjectStore.MAX_BYTES)
                throw new DurableException(
                        DurableException.Code.INVALID, "Object exceeds size limit");
            DurableHttp.result(exchange, backend.putObject(key, bytes));
        } else {
            var reference = get(metadata, "reference", Reference.class);
            byte[] bytes = backend.readObject(key, reference);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length != 0) exchange.getResponseBody().write(bytes);
        }
    }
}
