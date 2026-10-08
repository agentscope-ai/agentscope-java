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
package io.agentscope.core.observation;

import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

/** Acknowledged persistence boundary, not a best-effort telemetry callback. */
@FunctionalInterface
public interface ActionObserver {
    String CONTEXT_KEY = "agentscope.action.observer";
    String EVENT_NAME = "action_observation";

    /** The result is null for start, cancellation or an uncaught execution exception. */
    Mono<Void> record(ActionObservation observation, ToolResultBlock result);
}
