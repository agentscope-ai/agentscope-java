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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.CheckpointRestore;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import java.util.List;

/** One local implementation of the public operation contract, also used by HTTP handlers. */
final class DurableOperations {
    static final List<String> OPERATIONS =
            List.of(
                    "capabilities",
                    "createSession",
                    "getSession",
                    "listSessions",
                    "closeSession",
                    "createThread",
                    "getThread",
                    "listThreads",
                    "closeThread",
                    "listTurns",
                    "history",
                    "submit",
                    "getTurn",
                    "getRun",
                    "listRuns",
                    "getStep",
                    "listSteps",
                    "getModelCall",
                    "listModelCalls",
                    "control",
                    "interactions",
                    "snapshot",
                    "events",
                    "checkpoints",
                    "restore",
                    "fork");
    private final DurableRuntime runtime;
    private final SessionBackend backend;

    DurableOperations(DurableRuntime runtime, SessionBackend backend) {
        this.runtime = runtime;
        this.backend = backend;
    }

    JsonNode invoke(DurableProtocol.Request request) {
        var p = request.payload();
        SessionKey key =
                p.has("session") ? DurableJson.convert(p.get("session"), SessionKey.class) : null;
        ThreadKey thread =
                p.hasNonNull("threadId") ? new ThreadKey(key, p.path("threadId").asText()) : null;
        Object result =
                switch (request.operation()) {
                    case "capabilities" -> runtime.localUserCapabilities();
                    case "createSession" ->
                            backend.create(
                                    new SessionSpec(
                                            key,
                                            runtime.binding(key, p.path("definition").asText()),
                                            p.get("metadata")));
                    case "getSession" -> backend.session(key);
                    case "closeSession" -> backend.closeSession(key);
                    case "createThread" ->
                            backend.createThread(
                                    new ThreadSpec(
                                            thread,
                                            runtime.binding(key, p.path("definition").asText())));
                    case "getThread" -> backend.thread(thread);
                    case "listThreads" ->
                            backend.threads(key, text(p, "cursor"), p.path("limit").asInt());
                    case "closeThread" -> backend.closeThread(thread);
                    case "listSessions" ->
                            backend.listSessions(
                                    p.path("namespace").asText(),
                                    text(p, "cursor"),
                                    p.path("limit").asInt());
                    case "listTurns" ->
                            backend.turns(thread, text(p, "cursor"), p.path("limit").asInt());
                    case "history" ->
                            backend.history(thread, text(p, "cursor"), p.path("limit").asInt());
                    case "submit" ->
                            backend.submit(thread, p.path("requestId").asText(), p.get("input"));
                    case "getTurn" -> backend.turn(thread, p.path("turnId").asText());
                    case "getRun" -> backend.run(thread, text(p, "turnId"), text(p, "runId"));
                    case "listRuns" ->
                            backend.runs(
                                    thread,
                                    text(p, "turnId"),
                                    text(p, "cursor"),
                                    p.path("limit").asInt());
                    case "getStep" ->
                            backend.step(
                                    thread, text(p, "turnId"), text(p, "runId"), text(p, "stepId"));
                    case "listSteps" ->
                            backend.steps(
                                    thread,
                                    text(p, "turnId"),
                                    text(p, "runId"),
                                    text(p, "cursor"),
                                    p.path("limit").asInt());
                    case "getModelCall" ->
                            backend.modelCall(
                                    thread,
                                    text(p, "turnId"),
                                    text(p, "runId"),
                                    text(p, "modelCallId"));
                    case "listModelCalls" ->
                            backend.modelCalls(
                                    thread,
                                    text(p, "turnId"),
                                    text(p, "runId"),
                                    text(p, "stepId"),
                                    text(p, "cursor"),
                                    p.path("limit").asInt());
                    case "control" ->
                            backend.control(
                                    thread,
                                    DurableJson.convert(p.get("command"), ControlCommand.class));
                    case "interactions" -> backend.interactions(thread, p.path("turnId").asText());
                    case "snapshot" -> filter(key, p).select(backend.snapshot(key));
                    case "events" ->
                            filter(key, p)
                                    .select(
                                            backend.events(
                                                    key,
                                                    text(p, "cursor"),
                                                    p.path("limit").asInt()));
                    case "checkpoints" ->
                            backend.checkpoints(
                                    thread, p.path("after").asLong(), p.path("limit").asInt());
                    case "restore" ->
                            runtime.restore(
                                    new CheckpointRestore(
                                            thread,
                                            p.path("position").asLong(),
                                            backend.thread(thread).definition(),
                                            p.path("expectedPosition").asLong(),
                                            p.path("requestId").asText()));
                    case "fork" -> {
                        var source = backend.thread(thread).definition();
                        var targetKey = DurableJson.convert(p.get("target"), ThreadKey.class);
                        if (!key.namespace().equals(targetKey.session().namespace())
                                || thread.equals(targetKey))
                            throw new DurableException(
                                    DurableException.Code.INVALID,
                                    "Fork requires a distinct Thread in the same namespace");
                        var target = new ThreadSpec(targetKey, source.binding());
                        runtime.restore(
                                new CheckpointRestore(
                                        thread,
                                        p.path("position").asLong(),
                                        target,
                                        0,
                                        p.path("requestId").asText()));
                        yield backend.thread(targetKey);
                    }
                    default ->
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Unknown public operation");
                };
        return DurableJson.value(result);
    }

    private static String text(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asText() : null;
    }

    private EventFilter filter(SessionKey session, JsonNode payload) {
        var filter =
                payload.has("filter")
                        ? DurableJson.convert(payload.get("filter"), EventFilter.class)
                        : EventFilter.all();
        if (filter.threadId() != null) {
            var thread = new ThreadKey(session, filter.threadId());
            if (filter.runId() != null) backend.run(thread, filter.turnId(), filter.runId());
            else if (filter.turnId() != null) backend.turn(thread, filter.turnId());
            else backend.thread(thread);
        }
        return filter;
    }
}
