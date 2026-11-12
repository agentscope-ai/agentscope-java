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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AgentScope model capture and native-log rewriting; no metadata writes. */
final class AgentScopeModelResults {
    record Prepared(
            SessionEvent end, ModelCallResult result, RuntimeObjectStore.Reference reference) {}

    private final ObjectMapper mapper;
    private final ManagedJournalStore journal;
    private final RuntimeObjectStore objects;

    AgentScopeModelResults(ObjectMapper mapper, ManagedJournalStore journal) {
        this.mapper = mapper;
        this.journal = journal;
        this.objects = journal.objects().backend();
    }

    List<Prepared> prepare(ManagedRuntimeBatch batch) {
        var prepared = new ArrayList<Prepared>();
        for (var end : batch.events()) {
            if (!"model/end".equals(end.type())) continue;
            String id = String.valueOf(end.data().get("modelCallId"));
            var position = journal.modelRequestPosition(batch.scope(), id);
            long start = position.requestSequence();
            long checkpoint = position.checkpointSequence();
            for (var event : batch.events()) {
                if (event.seq() >= end.seq()) break;
                if ("state/checkpoint".equals(event.type()) && (start == 0 || event.seq() < start))
                    checkpoint = event.seq();
                if ("request/prepared".equals(event.type())
                        && id.equals(event.data().get("modelCallId"))) start = event.seq();
            }
            if (checkpoint < 0) checkpoint = journal.head(batch.scope()).checkpointSequence();
            if (start == 0)
                throw new ManagedStateException(
                        "model_request_missing", "Model completion has no committed request");
            if (!(end.data().get(ManagedModelCapture.OUTCOME) instanceof Map<?, ?> outcome)
                    || !(outcome.get("request") instanceof Map<?, ?> frozenRequest))
                throw new ManagedStateException(
                        "model_outcome_missing",
                        "Model completion has no directly captured outcome");
            var request =
                    mapper.convertValue(frozenRequest, new TypeReference<Map<String, Object>>() {});
            var responses =
                    mapper.convertValue(
                            outcome.get("responses"),
                            new TypeReference<List<Map<String, Object>>>() {});
            byte[] bytes = bytes(request);
            var requestRef =
                    objects.put(
                            batch.scope().messageGroup(),
                            "model-request",
                            RuntimeObjectStore.sha256(bytes),
                            bytes);
            var completion = new LinkedHashMap<String, Object>(end.data());
            completion.remove(ManagedModelCapture.OUTCOME);
            for (String key : List.of("usage", "finishReason"))
                if (completion.get(key) == null && outcome.get(key) != null)
                    completion.put(key, outcome.get(key));
            completion.values().removeIf(value -> value == null);
            var result =
                    new ModelCallResult(
                            1,
                            batch.scope().messageGroup(),
                            batch.turnId(),
                            id,
                            String.valueOf(request.get("model")),
                            String.valueOf(request.get("purpose")),
                            RuntimeObjectStore.sha256(bytes),
                            requestRef,
                            start,
                            end.seq(),
                            checkpoint,
                            String.valueOf(end.data().get("status")),
                            responses,
                            completion);
            bytes = bytes(result);
            prepared.add(
                    new Prepared(
                            end,
                            result,
                            objects.put(
                                    batch.scope().messageGroup(),
                                    "model-result",
                                    RuntimeObjectStore.sha256(bytes),
                                    bytes)));
        }
        return List.copyOf(prepared);
    }

    ManagedRuntimeBatch references(ManagedRuntimeBatch batch, List<Prepared> prepared) {
        var refs = new LinkedHashMap<String, RuntimeObjectStore.Reference>();
        for (var value : prepared) refs.put(value.result().modelCallId(), value.reference());
        return batch.withEvents(
                batch.events().stream()
                        .map(
                                event -> {
                                    if (!"model/end".equals(event.type())) return event;
                                    var data = new LinkedHashMap<String, Object>(event.data());
                                    data.remove(ManagedModelCapture.OUTCOME);
                                    for (var value : prepared)
                                        if (value.result()
                                                .modelCallId()
                                                .equals(data.get("modelCallId")))
                                            data.putAll(value.result().completion());
                                    data.put(
                                            "modelResultRef",
                                            refs.get(String.valueOf(data.get("modelCallId"))));
                                    return new SessionEvent(
                                            event.schemaVersion(),
                                            event.eventId(),
                                            event.seq(),
                                            event.occurredAt(),
                                            event.type(),
                                            event.executionRunId(),
                                            event.turnId(),
                                            event.required(),
                                            JsonUtils.getJsonCodec().toJson(data));
                                })
                        .toList());
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode model result", invalid);
        }
    }
}
