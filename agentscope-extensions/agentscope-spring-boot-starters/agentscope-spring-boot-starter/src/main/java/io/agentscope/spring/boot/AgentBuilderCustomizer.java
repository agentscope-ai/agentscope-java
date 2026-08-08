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
package io.agentscope.spring.boot;

import io.agentscope.core.ReActAgent;
import java.util.function.Consumer;

/**
 * Functional interface for customizing the {@link ReActAgent.Builder} before
 * {@link ReActAgent.Builder#build()} is invoked by the auto-configuration.
 *
 * <p>Beans implementing this interface are collected via
 * {@link org.springframework.beans.factory.ObjectProvider#orderedStream()} and
 * applied to the builder in order. This mirrors the existing
 * {@code ChatModelBuilderCustomizer} pattern used by model provider starters.
 *
 * <p>Example:
 * <pre>{@code
 * @Bean
 * public AgentBuilderCustomizer myCustomizer() {
 *     return builder -> builder.middleware(new MyMiddleware());
 * }
 * }</pre>
 *
 * @see AgentscopeAutoConfiguration#agentscopeReActAgent
 */
@FunctionalInterface
public interface AgentBuilderCustomizer extends Consumer<ReActAgent.Builder> {}
