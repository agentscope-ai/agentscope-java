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

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ModelRequestObserver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Records observed provider attempts separately from a logical model call and its replay. */
final class RecordedModelRequests implements ModelRequestObserver {
    private final InvocationRecorder recorder;
    private final String callId;
    private final String purpose;
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile ChatUsage latestUsage;

    RecordedModelRequests(InvocationRecorder recorder, String callId, String purpose) {
        this.recorder = recorder;
        this.callId = callId;
        this.purpose = purpose;
    }

    int count() {
        return attempts.get();
    }

    ChatUsage latestUsage() {
        return latestUsage;
    }

    public boolean retryAllowed(Throwable failure) {
        return !JournalException.causedBy(failure);
    }

    public Flux<ChatResponse> observe(Flux<ChatResponse> request, String model, String provider) {
        return Flux.defer(
                () -> {
                    latestUsage = null;
                    var attempt = new Attempt(model, provider, attempts.incrementAndGet());
                    return Flux.usingWhen(
                            recorder.record("model/request_started", attempt.payload())
                                    .thenReturn(attempt),
                            value -> {
                                value.startedNanos = System.nanoTime();
                                return request.doOnNext(value::accept)
                                        .contextWrite(
                                                context ->
                                                        context.delete(ModelRequestObserver.class));
                            },
                            value -> value.end("completed", null),
                            (value, error) ->
                                    JournalException.causedBy(error)
                                            ? Mono.empty()
                                            : value.end("failed", error),
                            value -> value.end("cancelled", null));
                });
    }

    private final class Attempt {
        private final String id = UUID.randomUUID().toString();
        private final String model;
        private final String provider;
        private final String stepId = recorder.stepId();
        private final int iteration = recorder.executionPosition().reasoningIteration();
        private final int number;
        private final long startedAt = System.currentTimeMillis();
        private long startedNanos;
        private ChatUsage usage;
        private String finishReason;

        Attempt(String model, String provider, int number) {
            this.model = model;
            this.provider = provider;
            this.number = number;
        }

        synchronized void accept(ChatResponse response) {
            if (response.getUsage() != null) {
                usage = response.getUsage();
                latestUsage = usage;
                var data = payload();
                data.put("usage", usage);
                recorder.append("model/request_usage", data);
            }
            if (response.getFinishReason() != null) finishReason = response.getFinishReason();
        }

        Map<String, Object> payload() {
            var data = new LinkedHashMap<String, Object>();
            data.put("requestId", id);
            data.put("modelCallId", callId);
            data.put("model", model);
            data.put("provider", provider);
            data.put("purpose", purpose);
            data.put("stepId", stepId);
            data.put("iteration", iteration);
            data.put("attempt", number);
            data.put("startedAt", startedAt);
            return data;
        }

        synchronized Mono<Void> end(String status, Throwable error) {
            var data = payload();
            data.put("status", status);
            data.put("endedAt", Math.max(startedAt, System.currentTimeMillis()));
            data.put(
                    "durationMillis",
                    startedNanos == 0
                            ? 0L
                            : Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
            data.put("usage", usage);
            data.put("usageComplete", "completed".equals(status) && usage != null);
            data.put("finishReason", finishReason);
            data.put("errorType", error == null ? null : error.getClass().getName());
            return recorder.record("model/request_ended", data);
        }
    }
}
