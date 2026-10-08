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
package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Records the final adapter boundary, including auxiliary/fallback calls and cancellation. */
public final class SessionModels {
    private SessionModels() {}

    public interface ManagedModel extends Model {}

    public static Model wrap(Model model, RuntimeContext context, String purpose) {
        if (model instanceof ManagedModel || SessionRecorder.from(context) == null) return model;
        return new Recorded(model, context, purpose);
    }

    private record Recorded(Model delegate, RuntimeContext context, String purpose)
            implements ManagedModel {
        public String getModelName() {
            return delegate.getModelName();
        }

        public boolean supportsNativeStructuredOutput() {
            return delegate.supportsNativeStructuredOutput();
        }

        public boolean supportsNativeStructuredOutputWithTools() {
            return delegate.supportsNativeStructuredOutputWithTools();
        }

        public int getContextWindowSize() {
            return delegate.getContextWindowSize();
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        var recorder = SessionRecorder.from(context);
                        String callId = UUID.randomUUID().toString();
                        var last = new AtomicReference<ChatResponse>();
                        return Flux.usingWhen(
                                Mono.fromRunnable(
                                                () -> {
                                                    if (context.get(SessionModelPolicy.CONTEXT_KEY)
                                                            instanceof SessionModelPolicy policy)
                                                        policy.beforeCall(
                                                                callId,
                                                                delegate.getModelName(),
                                                                context);
                                                })
                                        .then(
                                                recorder.prepareModel(
                                                        new ModelCallInput(
                                                                messages, tools, options, delegate),
                                                        callId,
                                                        purpose,
                                                        context.getAgentState()))
                                        .thenReturn(callId),
                                id ->
                                        Flux.defer(() -> delegate.stream(messages, tools, options))
                                                .doOnNext(
                                                        chunk -> {
                                                            last.set(chunk);
                                                            recorder.append(
                                                                    "model/chunk",
                                                                    Map.of(
                                                                            "modelCallId",
                                                                            id,
                                                                            "purpose",
                                                                            purpose,
                                                                            "chunk",
                                                                            chunk));
                                                        }),
                                id -> end(recorder, id, "completed", last.get(), null),
                                (id, error) ->
                                        SessionLogException.causedBy(error)
                                                ? Mono.empty()
                                                : end(recorder, id, "failed", last.get(), error),
                                id -> end(recorder, id, "cancelled", last.get(), null));
                    });
        }

        private Mono<Void> end(
                SessionRecorder recorder,
                String id,
                String status,
                ChatResponse last,
                Throwable error) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("modelCallId", id);
            payload.put("status", status);
            payload.put("model", delegate.getModelName());
            payload.put("purpose", purpose);
            if (last != null) {
                payload.put("usage", last.getUsage());
                payload.put("finishReason", last.getFinishReason());
            }
            if (error != null) payload.put("errorType", error.getClass().getName());
            return recorder.record("model/end", payload)
                    .then(
                            Mono.fromRunnable(
                                    () -> {
                                        if (context.get(SessionModelPolicy.CONTEXT_KEY)
                                                instanceof SessionModelPolicy policy)
                                            policy.afterCall(
                                                    id,
                                                    delegate.getModelName(),
                                                    status,
                                                    last == null ? null : last.getUsage(),
                                                    context);
                                    }));
        }
    }
}
