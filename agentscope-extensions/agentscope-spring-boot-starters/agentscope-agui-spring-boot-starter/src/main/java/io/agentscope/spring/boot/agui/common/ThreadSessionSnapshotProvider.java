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

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.processor.AguiSnapshotProvider;
import io.agentscope.core.agui.processor.AguiSnapshotRequest;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot provider backed by {@link ThreadSessionManager}.
 */
public class ThreadSessionSnapshotProvider implements AguiSnapshotProvider {

    private final AguiAgentRegistry registry;
    private final ThreadSessionManager sessionManager;
    private final boolean serverSideMemory;
    private final AguiMessageConverter messageConverter;

    /**
     * Creates a new ThreadSessionSnapshotProvider.
     *
     * @param registry The AG-UI agent registry
     * @param sessionManager The thread session manager
     * @param serverSideMemory Whether server-side memory is enabled
     */
    public ThreadSessionSnapshotProvider(
            AguiAgentRegistry registry,
            ThreadSessionManager sessionManager,
            boolean serverSideMemory) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = sessionManager;
        this.serverSideMemory = serverSideMemory && sessionManager != null;
        this.messageConverter = new AguiMessageConverter();
    }

    @Override
    public List<AguiMessage> messagesSnapshot(AguiSnapshotRequest request) {
        if (!registry.hasAgent(request.agentId())) {
            throw new AguiException.AgentNotFoundException(request.agentId());
        }
        if (!serverSideMemory) {
            return List.of();
        }
        return messageConverter.toAguiMessageList(
                sessionManager.getMessages(request.threadId(), request.agentId()));
    }
}
