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
package io.agentscope.core.hook;

import io.agentscope.core.agent.RuntimeContext;

/**
 * Optional contract for {@link Hook} implementations that need the current
 * per-call {@link RuntimeContext}.
 *
 * <p>During a {@code ReActAgent.call(msgs, ctx)} execution, the framework sets the context
 * on all registered hooks that implement this interface, and clears it on completion. Hooks may
 * cache the reference in a field, as the same {@link RuntimeContext} instance is mutably shared
 * for cross-hook/tool coordination.
 */
@FunctionalInterface
public interface RuntimeContextAware {

    /**
     * Injects the runtime context for the current call, or {@code null} when not executing or
     * when clearing after a call.
     *
     * @param context current runtime context, or null
     */
    void setRuntimeContext(RuntimeContext context);
}
