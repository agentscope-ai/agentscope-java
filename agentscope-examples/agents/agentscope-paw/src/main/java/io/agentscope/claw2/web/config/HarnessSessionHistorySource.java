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
import io.agentscope.extensions.aistio.adapter.SessionHistorySource;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads harness {@code {sessionId}.log.jsonl} (full history) for the aistio messages contract.
 * Distributed readability is the caller's responsibility via {@code AbstractFilesystem}.
 */
public final class HarnessSessionHistorySource implements SessionHistorySource {

    private static final Logger LOG = Logger.getLogger(HarnessSessionHistorySource.class.getName());

    private final HarnessAgent harness;

    public HarnessSessionHistorySource(HarnessAgent harness) {
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
            WorkspaceManager wm = harness.workspaceFor(uid, sessionId);
            String agentId = harness.getAgentId();
            Path contextFile = wm.resolveSessionContextFile(rc, agentId, sessionId);
            String contextRel =
                    WorkspaceConstants.AGENTS_DIR
                            + "/"
                            + agentId
                            + "/"
                            + WorkspaceConstants.SESSIONS_DIR
                            + "/"
                            + sessionId
                            + WorkspaceConstants.SESSION_CONTEXT_EXT;
            SessionTree tree =
                    new SessionTree(
                                    contextFile,
                                    wm.getWorkspace(),
                                    wm.getFilesystem(),
                                    wm.getIndex(),
                                    contextRel)
                            .setRuntimeContext(rc);
            tree.load();
            tree.syncFromRemote();
            tree.syncFromLog();

            List<SessionEntry> entries = tree.getAllEntries();
            if (entries.isEmpty()) {
                return Optional.empty();
            }
            List<MessagePage.MessageItem> items = new ArrayList<>();
            int seq = 0;
            for (SessionEntry entry : entries) {
                long occurred =
                        entry.getTimestamp() == null ? 0L : entry.getTimestamp().toEpochMilli();
                if (entry instanceof SessionEntry.MessageEntry msg) {
                    seq++;
                    String role = mapRole(msg.getRole());
                    String content = msg.getContent() == null ? "" : msg.getContent();
                    if ((content == null || content.isBlank())
                            && msg.getBlockTypes() != null
                            && !msg.getBlockTypes().isEmpty()) {
                        content = "[blocks: " + String.join(",", msg.getBlockTypes()) + "]";
                    }
                    items.add(
                            new MessagePage.MessageItem(
                                    seq, role, content, "", null, "", occurred));
                } else if (entry instanceof SessionEntry.ToolUseEntry use) {
                    seq++;
                    items.add(
                            new MessagePage.MessageItem(
                                    seq,
                                    SessionEvent.ROLE_ASSISTANT,
                                    "",
                                    use.getName() == null ? "" : use.getName(),
                                    use.getInput(),
                                    "",
                                    occurred));
                } else if (entry instanceof SessionEntry.ToolResultEntry result) {
                    seq++;
                    String output = result.getOutput() == null ? "" : result.getOutput();
                    if (result.isTruncated()) {
                        output =
                                output
                                        + "...(truncated, originalSize="
                                        + result.getOriginalSize()
                                        + ")";
                    }
                    items.add(
                            new MessagePage.MessageItem(
                                    seq,
                                    SessionEvent.ROLE_TOOL,
                                    "",
                                    result.getName() == null ? "" : result.getName(),
                                    null,
                                    output,
                                    occurred));
                }
            }
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "aistio: failed to read session log for " + sessionId, e);
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
