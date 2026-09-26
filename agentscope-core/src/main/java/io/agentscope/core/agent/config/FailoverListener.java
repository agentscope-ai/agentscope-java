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
package io.agentscope.core.agent.config;

import io.agentscope.core.model.Model;

/**
 * Observer notified when a {@link ModelConfig#fallbackModel() fallback model} takes over from a
 * failed primary model. The switch and its cause are otherwise unobservable in-process: the
 * primary's error is consumed inside the fallback wrapper the agent builds around the model,
 * below both the {@code onModelCall} middleware seam and the {@code AgentEvent} conversion — no
 * existing observer surface reaches it.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>invoked synchronously at the switch site on a reactor thread — implementations must be
 *       fast and non-blocking;
 *   <li>one listener is shared across the agent's concurrent runs — implementations must be
 *       thread-safe;
 *   <li>an {@link Exception} thrown by an implementation is logged and ignored — the switch and
 *       the fallback call proceed unaffected (errors are not contained).
 * </ul>
 *
 * <p>A {@code null} listener (the default) leaves agent behavior unchanged.
 */
@FunctionalInterface
public interface FailoverListener {

    /**
     * Called when the primary model fails and the fallback takes over.
     *
     * @param primary the primary model that failed
     * @param error the error that triggered the switch, after the primary exhausted its retry
     *     budget
     */
    void onFailover(Model primary, Throwable error);
}
