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

import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Optional call-scoped host replay boundary. Invoking next preserves recording and model policy. */
@FunctionalInterface
public interface ModelInvocation {
    /** Resume an already selected fallback without reporting another provider failure. */
    default boolean resumingFallback(String purpose) {
        return false;
    }

    /** Let the host distinguish a provider failure from a dispatch or reply protocol failure. */
    default boolean beginFallback(String purpose, Throwable cause) {
        return true;
    }

    /** Prepared input and stable identity for this model invocation. */
    record Call(String callId, String purpose, ModelCallInput input) {}

    sealed interface Route permits Direct, Suspend, Resume {}

    record Direct() implements Route {}

    /** The handoff must be cold. Core subscribes only after admission and checkpoint commit. */
    record Suspend(Mono<Void> handoff) implements Route {
        public Suspend {
            Objects.requireNonNull(handoff, "handoff");
        }
    }

    /** Responses for the admitted call, supplied by the host on reentry. */
    record Resume(Flux<ChatResponse> responses) implements Route {
        public Resume {
            Objects.requireNonNull(responses, "responses");
        }
    }

    /** Select an execution path without starting a provider request or handing off work. */
    default Route route(Call call) {
        return new Direct();
    }

    Flux<ChatResponse> stream(
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            Function<String, Flux<ChatResponse>> next);
}
