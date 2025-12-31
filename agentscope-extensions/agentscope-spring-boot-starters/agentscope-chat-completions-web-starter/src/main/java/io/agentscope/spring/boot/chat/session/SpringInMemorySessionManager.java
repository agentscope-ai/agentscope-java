/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.spring.boot.chat.session;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.session.InMemorySessionManager;
import java.util.function.Supplier;

/**
 * Spring Boot implementation of {@link ChatCompletionsSessionManager} using in-memory storage.
 *
 * <p>This is a Spring-specific implementation that delegates to the core
 * {@link InMemorySessionManager}. It implements the Spring interface which provides
 * ObjectProvider support via default methods.
 */
public class SpringInMemorySessionManager implements ChatCompletionsSessionManager {

    private final InMemorySessionManager delegate;

    /**
     * Create a new in-memory session manager.
     */
    public SpringInMemorySessionManager() {
        this.delegate = new InMemorySessionManager();
    }

    /**
     * Create a new in-memory session manager with a custom delegate.
     *
     * @param delegate the core session manager to delegate to
     */
    public SpringInMemorySessionManager(InMemorySessionManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public ReActAgent getOrCreateAgent(String sessionId, Supplier<ReActAgent> agentSupplier) {
        return delegate.getOrCreateAgent(sessionId, agentSupplier);
    }
}
