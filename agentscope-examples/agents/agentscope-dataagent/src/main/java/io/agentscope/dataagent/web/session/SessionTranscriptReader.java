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
package io.agentscope.dataagent.web.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.Objects;

/**
 * Reads session transcripts through a borrowed workspace view so REST reads do not depend on an
 * active agent call context.
 */
public final class SessionTranscriptReader {

    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final SessionWorkspaceProvider workspaceProvider;

    public SessionTranscriptReader(
            SessionAgentManager sessionAgentManager,
            AgentCatalogService catalogService,
            SessionWorkspaceProvider workspaceProvider) {
        this.sessionAgentManager =
                Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.workspaceProvider = Objects.requireNonNull(workspaceProvider, "workspaceProvider");
    }

    public String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        WorkspaceManager workspaceManager = resolveWorkspaceManager(urlAgentId, entry);
        if (workspaceManager != null) {
            String runtimeAgentId = entry.agentId();
            if (runtimeAgentId != null && !runtimeAgentId.isBlank()) {
                String relLog =
                        "agents/"
                                + runtimeAgentId
                                + "/sessions/"
                                + entry.sessionId()
                                + ".log.jsonl";
                String fromLog =
                        workspaceManager.readManagedWorkspaceFileUtf8(
                                RuntimeContext.empty(), relLog);
                if (fromLog != null && !fromLog.isEmpty()) {
                    return fromLog;
                }
                String relCtx =
                        "agents/"
                                + runtimeAgentId
                                + "/sessions/"
                                + entry.sessionId()
                                + ".jsonl";
                String fromCtx =
                        workspaceManager.readManagedWorkspaceFileUtf8(
                                RuntimeContext.empty(), relCtx);
                if (fromCtx != null && !fromCtx.isEmpty()) {
                    return fromCtx;
                }
            }
        }
        HistoryResult raw = sessionAgentManager.history(entry.sessionKey(), 0);
        if (raw == null || raw.error() != null) {
            return "";
        }
        return raw.content() != null ? raw.content() : "";
    }

    private WorkspaceManager resolveWorkspaceManager(String urlAgentId, SessionEntry entry) {
        if (entry == null || entry.userId() == null || entry.userId().isBlank()) {
            return null;
        }
        if (urlAgentId == null || urlAgentId.isBlank()) {
            return null;
        }
        AgentDefinition def = catalogService.findVisible(entry.userId(), urlAgentId).orElse(null);
        if (def == null) {
            return null;
        }
        String runtimeAgentId = entry.agentId();
        if (runtimeAgentId == null || runtimeAgentId.isBlank()) {
            runtimeAgentId = catalogService.peekGatewayAgentId(entry.userId(), urlAgentId);
        }
        if (runtimeAgentId == null || runtimeAgentId.isBlank()) {
            return null;
        }
        return workspaceProvider.forAgent(
                entry.userId(), runtimeAgentId, def.workspacePath());
    }

    @FunctionalInterface
    public interface SessionWorkspaceProvider {
        WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath);
    }
}
