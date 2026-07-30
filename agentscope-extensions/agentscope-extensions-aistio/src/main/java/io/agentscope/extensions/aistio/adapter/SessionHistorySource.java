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
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.extensions.aistio.model.MessagePage;
import java.util.List;
import java.util.Optional;

/**
 * Optional full-history reader for {@code GET /agentscope/sessions/{id}/messages}.
 *
 * <p>AgentScope Harness persists an append-only {@code .log.jsonl}; BYO apps that use the harness
 * can plug a source that reads it (including via a distributed {@code AbstractFilesystem}). When
 * absent, the adapter falls back to the live {@code AgentState} context buffer.
 */
@FunctionalInterface
public interface SessionHistorySource {

    /**
     * @param sessionId session key
     * @param userId user slot recorded when the session was first seen; may be empty
     * @return full message list (not yet paginated), or empty when no log is available
     */
    Optional<List<MessagePage.MessageItem>> loadMessages(String sessionId, String userId);
}
