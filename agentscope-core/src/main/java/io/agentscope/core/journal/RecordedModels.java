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
package io.agentscope.core.journal;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRequestObserver;
import io.agentscope.core.model.ModelSchedulers;
import io.agentscope.core.model.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Records the final adapter boundary, including auxiliary/fallback calls and cancellation. */
public final class RecordedModels {
    private RecordedModels() {}

    public interface ManagedModel extends Model {}

    public static Model wrap(Model model, RuntimeContext context, String purpose) {
        if (model instanceof ManagedModel || InvocationRecorder.from(context) == null) return model;
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
                        var invocation = context.get(ModelInvocation.class);
                        return invocation == null
                                ? invoke(UUID.randomUUID().toString(), messages, tools, options)
                                : invocation.stream(
                                        delegate.getModelName(),
                                        purpose,
                                        messages,
                                        tools,
                                        options,
                                        id -> invoke(id, messages, tools, options));
                    });
        }

        private Flux<ChatResponse> invoke(
                String callId,
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        var recorder = InvocationRecorder.from(context);
                        var last = new AtomicReference<ChatResponse>();
                        var usage = new AtomicReference<ChatUsage>();
                        var requests = new RecordedModelRequests(recorder, callId, purpose);
                        return Flux.usingWhen(
                                before(callId)
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
                                                            if (chunk.getUsage() != null)
                                                                usage.set(chunk.getUsage());
                                                            recorder.append(
                                                                    "model/chunk",
                                                                    Map.of(
                                                                            "modelCallId",
                                                                            id,
                                                                            "purpose",
                                                                            purpose,
                                                                            "chunk",
                                                                            chunk));
                                                        })
                                                .contextWrite(
                                                        context ->
                                                                context.put(
                                                                        ModelRequestObserver.class,
                                                                        requests)),
                                id ->
                                        end(
                                                recorder,
                                                id,
                                                "completed",
                                                last.get(),
                                                requests.count() == 0
                                                        ? usage.get()
                                                        : requests.latestUsage(),
                                                requests.count(),
                                                null),
                                (id, error) ->
                                        JournalException.causedBy(error)
                                                ? Mono.empty()
                                                : end(
                                                        recorder,
                                                        id,
                                                        "failed",
                                                        last.get(),
                                                        requests.count() == 0
                                                                ? usage.get()
                                                                : requests.latestUsage(),
                                                        requests.count(),
                                                        error),
                                id ->
                                        end(
                                                recorder,
                                                id,
                                                "cancelled",
                                                last.get(),
                                                requests.count() == 0
                                                        ? usage.get()
                                                        : requests.latestUsage(),
                                                requests.count(),
                                                null));
                    });
        }

        private Mono<Void> end(
                InvocationRecorder recorder,
                String id,
                String status,
                ChatResponse last,
                ChatUsage usage,
                int observedRequests,
                Throwable error) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("modelCallId", id);
            payload.put("status", status);
            payload.put("model", delegate.getModelName());
            payload.put("purpose", purpose);
            payload.put(
                    "requestObservation",
                    observedRequests == 0 ? "model_call" : "provider_attempts");
            payload.put("observedRequests", observedRequests);
            payload.put("usage", usage);
            if (last != null) {
                payload.put("finishReason", last.getFinishReason());
            }
            if (error != null) payload.put("errorType", error.getClass().getName());
            var policy = context.get(ModelCallPolicy.CONTEXT_KEY);
            Mono<Void> after =
                    policy instanceof ModelCallPolicy configured && configured.hasAfterCall()
                            ? Mono.<Void>fromRunnable(
                                            () ->
                                                    configured.afterCall(
                                                            id,
                                                            delegate.getModelName(),
                                                            status,
                                                            usage,
                                                            context))
                                    .subscribeOn(ModelSchedulers.requests())
                            : Mono.empty();
            return recorder.record("model/end", payload).then(after);
        }

        private Mono<Void> before(String callId) {
            var policy = context.get(ModelCallPolicy.CONTEXT_KEY);
            if (!(policy instanceof ModelCallPolicy configured) || !configured.hasBeforeCall())
                return Mono.empty();
            return Mono.<Void>fromRunnable(
                            () -> configured.beforeCall(callId, delegate.getModelName(), context))
                    .subscribeOn(ModelSchedulers.requests());
        }
    }
}
