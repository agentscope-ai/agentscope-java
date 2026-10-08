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
package io.agentscope.claw2.web.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionTranscriptExport;
import io.agentscope.extensions.controlplane.adapter.SessionHistorySource;
import io.agentscope.extensions.controlplane.model.MessagePage;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads committed native session history for the controlplane messages contract. */
public final class AgentSessionHistorySource implements SessionHistorySource {

    private static final Logger LOG = Logger.getLogger(AgentSessionHistorySource.class.getName());

    private final HarnessAgent harness;

    public AgentSessionHistorySource(HarnessAgent harness) {
        this.harness = harness;
    }

    @Override
    public Optional<List<MessagePage.MessageItem>> loadMessages(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String uid = userId == null ? "" : userId;
            RuntimeContext rc = RuntimeContext.builder().userId(uid).sessionId(sessionId).build();
            List<MessagePage.MessageItem> items = new ArrayList<>();
            int seq = 0;
            for (var entry : SessionTranscriptExport.entries(harness.sessionTranscript(rc))) {
                items.add(
                        new MessagePage.MessageItem(
                                ++seq,
                                entry.toolInput() != null
                                        ? SessionEvent.ROLE_ASSISTANT
                                        : mapRole(entry.role()),
                                entry.content() == null ? "" : entry.content(),
                                entry.toolName() == null ? "" : entry.toolName(),
                                entry.toolInput(),
                                entry.toolResult() == null ? "" : entry.toolResult(),
                                entry.timestampMs()));
            }
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "controlplane: failed to read session log for " + sessionId, e);
            return Optional.empty();
        }
    }

    private static String mapRole(String wire) {
        if (wire == null || wire.isBlank()) {
            return SessionEvent.ROLE_ASSISTANT;
        }
        return switch (wire.toUpperCase(Locale.ROOT)) {
            case "USER" -> SessionEvent.ROLE_USER;
            case "SYSTEM" -> SessionEvent.ROLE_SYSTEM;
            case "TOOL" -> SessionEvent.ROLE_TOOL;
            default -> SessionEvent.ROLE_ASSISTANT;
        };
    }
}
