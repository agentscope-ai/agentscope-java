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
package io.agentscope.core.model;

import reactor.core.publisher.Flux;

/**
 * Subscription-scoped observation at the provider request boundary, before retry. Carried through
 * Reactor Context rather than shared model fields. Providers using ModelUtils are observed
 * automatically; hidden SDK retries remain outside this boundary.
 */
public interface ModelRequestObserver {
    Flux<ChatResponse> observe(Flux<ChatResponse> request, String model, String provider);

    /** Recording/ownership failures must not be retried as provider failures. */
    default boolean retryAllowed(Throwable failure) {
        return true;
    }
}
