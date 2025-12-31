/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.chat.session;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.session.ChatCompletionsSessionManager;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring Boot interface for managing session-scoped ReActAgent instances.
 *
 * <p>This interface extends the core {@link ChatCompletionsSessionManager} and adds Spring-specific
 * methods that work with {@link ObjectProvider}.
 *
 * <p>The name is prefixed with "Spring" to avoid confusion with the core interface
 * {@link ChatCompletionsSessionManager}.
 */
public interface SpringChatCompletionsSessionManager extends ChatCompletionsSessionManager {

    /**
     * Get or create a ReActAgent for the given session id using Spring's ObjectProvider.
     *
     * <p>This is a convenience method that adapts Spring's {@link ObjectProvider} to the
     * {@link java.util.function.Supplier} interface expected by the core method.
     *
     * @param sessionId session identifier; may be null to indicate a stateless request
     * @param agentProvider Spring ObjectProvider used to lazily create new ReActAgent instances
     * @return a ReActAgent instance (new or existing)
     */
    default ReActAgent getOrCreateAgent(
            String sessionId, ObjectProvider<ReActAgent> agentProvider) {
        return getOrCreateAgent(sessionId, agentProvider::getObject);
    }
}
