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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/** Final, asynchronous request preparation, after all model-call middleware transformations. */
@FunctionalInterface
public interface ModelRequestPreparer {
    String MANIFEST_ATTRIBUTE_PREFIX = "agentscope.model_request_manifest.";
    String BUILD_EVENT_NAME = "context_build";

    /** Emit one independent build outcome, including rejection before any provider call. */
    default Mono<ModelCallInput> prepareObserved(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            String callId,
            Purpose purpose,
            Consumer<AgentEvent> events) {
        return Mono.defer(
                () -> {
                    RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
                    String key = MANIFEST_ATTRIBUTE_PREFIX + callId;
                    rc.put(key, null);
                    return Mono.defer(() -> prepare(agent, rc, input, callId, purpose))
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Model request preparer returned no request")))
                            .doOnSuccess(result -> emitBuild(events, rc, callId, purpose, null))
                            .doOnError(
                                    error -> {
                                        emitBuild(events, rc, callId, purpose, error);
                                        rc.put(key, null);
                                    });
                });
    }

    private static void emitBuild(
            Consumer<AgentEvent> events,
            RuntimeContext rc,
            String callId,
            Purpose purpose,
            Throwable error) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model_call_id", callId);
        payload.put("purpose", purpose.name());
        payload.put("status", error == null ? "passed" : "failed");
        Object manifest = rc.get(MANIFEST_ATTRIBUTE_PREFIX + callId);
        if (manifest != null) payload.put("context_manifest", manifest);
        // Exception text can contain source content; record only its type.
        if (error != null) payload.put("error_type", error.getClass().getSimpleName());
        events.accept(new CustomEvent(BUILD_EVENT_NAME, payload));
    }

    default boolean handlesTaskProjection() {
        return false;
    }

    /** Called anew on subscription, including retries. Must not invoke the main model itself. */
    Mono<ModelCallInput> prepare(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            String callId,
            Purpose purpose);

    enum Purpose {
        REASONING,
        SUMMARY
    }
}
