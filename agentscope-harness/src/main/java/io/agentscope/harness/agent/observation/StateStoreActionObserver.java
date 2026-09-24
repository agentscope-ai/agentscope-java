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
package io.agentscope.harness.agent.observation;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.observation.ActionObserver;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.util.JsonUtils;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Persists immutable start/settlement records separately from the compactable conversation.
 * A start without settlement is an unresolved attempt, never authorization to replay it.
 */
public final class StateStoreActionObserver implements ActionObserver {
    private final AgentStateStore store;

    public StateStoreActionObserver(AgentStateStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public static String key(String actionId, boolean started) {
        return "action_" + actionId + (started ? "_started" : "_settled");
    }

    public Optional<StoredActionObservation> load(
            String userId, String sessionId, String actionId, boolean started) {
        return store.get(userId, sessionId, key(actionId, started), StoredActionObservation.class);
    }

    @Override
    public Mono<Void> record(ActionObservation observation, ToolResultBlock result) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            if (observation.sessionId() == null
                                    || observation.sessionId().isBlank()) {
                                throw new IllegalArgumentException(
                                        "Action observation requires a session identity");
                            }
                            String key =
                                    key(
                                            observation.actionId(),
                                            observation.status()
                                                    == ActionObservation.Status.STARTED);
                            StoredActionObservation value =
                                    new StoredActionObservation(observation, result);
                            var previous =
                                    store.get(
                                            observation.userId(),
                                            observation.sessionId(),
                                            key,
                                            StoredActionObservation.class);
                            if (previous.isPresent()) {
                                requireIdentical(previous.get(), value);
                                return;
                            }
                            if (!store.supportsVersioning()) {
                                store.save(
                                        observation.userId(), observation.sessionId(), key, value);
                                return;
                            }
                            long version =
                                    store.saveIfVersion(
                                            observation.userId(),
                                            observation.sessionId(),
                                            key,
                                            value,
                                            0);
                            if (version == AgentStateStore.UNVERSIONED) {
                                requireIdentical(
                                        store.get(
                                                        observation.userId(),
                                                        observation.sessionId(),
                                                        key,
                                                        StoredActionObservation.class)
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "Observation commit was not"
                                                                                + " acknowledged")),
                                        value);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void requireIdentical(
            StoredActionObservation previous, StoredActionObservation next) {
        var codec = JsonUtils.getJsonCodec();
        if (!codec.toJson(previous).equals(codec.toJson(next))) {
            throw new IllegalStateException("Conflicting settlement for the same action");
        }
    }
}
