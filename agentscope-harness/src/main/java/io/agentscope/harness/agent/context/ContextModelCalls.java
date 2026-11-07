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
package io.agentscope.harness.agent.context;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionModels;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Budget-only auxiliary call boundary: never recursively compacts or injects task state. */
public final class ContextModelCalls {
    private ContextModelCalls() {}

    public static Flux<ChatResponse> auxiliary(Model model, List<Msg> messages) {
        return prepareAuxiliary(model, messages).flatMapMany(ContextModelCalls::auxiliary);
    }

    /** Prepares the budget separately so invocation failover cannot consume preparation errors. */
    public static Mono<ModelCallInput> prepareAuxiliary(Model model, List<Msg> messages) {
        return Mono.defer(
                () ->
                        new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                                .prepare(
                                        null,
                                        RuntimeContext.empty(),
                                        new ModelCallInput(messages, List.of(), null, model),
                                        UUID.randomUUID().toString(),
                                        ModelRequestPreparer.Purpose.SUMMARY));
    }

    /** Executes an already budget-validated auxiliary request with the subscriber's session log. */
    public static Flux<ChatResponse> auxiliary(ModelCallInput input) {
        return auxiliary(input, null);
    }

    /** Observes only errors from the supplied model, excluding injected session lifecycle hooks. */
    public static Flux<ChatResponse> auxiliary(
            ModelCallInput input, Consumer<Throwable> invocationError) {
        return Flux.deferContextual(
                subscriber -> {
                    RuntimeContext context =
                            subscriber.getOrDefault(
                                    AgentBase.RUNTIME_CONTEXT_KEY, RuntimeContext.empty());
                    if (invocationError != null) {
                        return SessionModels.stream(input, context, "SUMMARY", invocationError);
                    }
                    return SessionModels.wrap(input.model(), context, "SUMMARY").stream(
                            input.messages(), input.tools(), input.options());
                });
    }

    /** Recognizes cancellation through provider wrappers without looping over cyclic causes. */
    public static boolean isCancellation(Throwable error) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        for (Throwable cause = error;
                cause != null && visited.put(cause, Boolean.TRUE) == null;
                cause = cause.getCause()) {
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }
}
