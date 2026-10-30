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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agent.Agent;

/**
 * Factory for creating {@link AguiAgentAdapter} instances for AG-UI requests.
 *
 * <p>Applications can provide a custom factory when they need to replace the default adapter, for
 * example to use an {@link AguiAgentAdapter} subclass that customizes runtime context creation.
 */
@FunctionalInterface
public interface AguiAgentAdapterFactory {

    /**
     * Create an adapter for a resolved agent.
     *
     * @param agent The resolved agent
     * @param config The AG-UI adapter configuration
     * @return The adapter to use for the request
     */
    AguiAgentAdapter create(Agent agent, AguiAdapterConfig config);

    /**
     * Return the default factory.
     *
     * @return A factory that creates the standard {@link AguiAgentAdapter}
     */
    static AguiAgentAdapterFactory defaultFactory() {
        return AguiAgentAdapter::new;
    }
}
