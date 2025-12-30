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
package io.agentscope.core.chat.completions.session;

import io.agentscope.core.ReActAgent;
import java.util.function.Supplier;

/**
 * Simple SPI for managing session-scoped ReActAgent instances.
 *
 * <p>MVP implementation is in-memory only and not intended for production use.
 */
public interface ChatCompletionsSessionManager {

    /**
     * Get or create a ReActAgent for the given session id.
     *
     * @param sessionId session identifier; may be null to indicate a stateless request
     * @param agentSupplier supplier used to lazily create new ReActAgent instances
     * @return a ReActAgent instance (new or existing)
     */
    ReActAgent getOrCreateAgent(String sessionId, Supplier<ReActAgent> agentSupplier);
}
