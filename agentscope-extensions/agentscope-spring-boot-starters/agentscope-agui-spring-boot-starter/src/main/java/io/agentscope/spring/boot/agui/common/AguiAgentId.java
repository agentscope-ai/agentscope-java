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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify a custom agent ID for auto-registration.
 *
 * <p>When an Agent bean is annotated with this annotation, the specified ID will be used instead of
 * the bean name for registration in the {@link
 * io.agentscope.core.agui.registry.AguiAgentRegistry}.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @Bean
 * @AguiAgentId("custom-chat-agent")
 * public Agent chatAgent() {
 *     return ReActAgent.builder()
 *         .name("ChatAgent")
 *         .model(model)
 *         .build();
 * }
 * }</pre>
 *
 * <p>If this annotation is not present, the bean name will be used as the agent ID.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AguiAgentId {

    /**
     * The agent ID to use for registration.
     *
     * @return The agent ID
     */
    String value();
}
