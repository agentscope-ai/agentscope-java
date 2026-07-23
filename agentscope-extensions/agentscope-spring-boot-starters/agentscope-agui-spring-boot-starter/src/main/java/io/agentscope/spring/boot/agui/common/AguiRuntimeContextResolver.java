/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agent.RuntimeContext;

/**
 * Resolves a caller-provided {@link RuntimeContext} for an AG-UI request.
 *
 * <p>Spring applications can expose this as a bean to attach request-scoped values such as tenant
 * information, tracing data, or tool dependencies. The AG-UI runtime metadata is still supplied by
 * the protocol adapter and takes precedence over conflicting values.
 */
@FunctionalInterface
public interface AguiRuntimeContextResolver {

    /**
     * Resolve the runtime context for the current request.
     *
     * @param request The AG-UI request context
     * @return A runtime context to merge into the AG-UI run, or null
     */
    RuntimeContext resolve(AguiRuntimeContextRequest request);
}
