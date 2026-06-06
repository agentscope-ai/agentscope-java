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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionTranscriptReaderTest {

    @TempDir Path workspaceRoot;

    @Mock SessionAgentManager sessionAgentManager;

    @Mock AgentCatalogService catalogService;

    @Test
    void readsBorrowedWorkspaceBeforeHistoryFallback() throws Exception {
        String userId = "alice";
        String logicalAgentId = "data-agent";
        String runtimeAgentId = "uca-alice-data-agent";
        String sessionId = "session-1";
        SessionEntry entry =
                new SessionEntry(
                        "agent:" + runtimeAgentId + ":main:" + sessionId,
                        runtimeAgentId,
                        sessionId,
                        null,
                        SessionKind.MAIN,
                        null,
                        0,
                        1L,
                        1L,
                        workspaceRoot.resolve("legacy.json").toString(),
                        null,
                        "|x:agentId=" + runtimeAgentId + "|",
                        userId);

        AgentDefinition definition = agentDefinition(logicalAgentId, "custom-root", userId);
        when(catalogService.findVisible(userId, logicalAgentId)).thenReturn(Optional.of(definition));

        AtomicReference<String> ownerRef = new AtomicReference<>();
        AtomicReference<String> agentRef = new AtomicReference<>();
        AtomicReference<String> workspacePathRef = new AtomicReference<>();
        SessionTranscriptReader reader =
                new SessionTranscriptReader(
                        sessionAgentManager,
                        catalogService,
                        (ownerId, agentId, workspacePath) -> {
                            ownerRef.set(ownerId);
                            agentRef.set(agentId);
                            workspacePathRef.set(workspacePath);
                            return new WorkspaceManager(workspaceRoot);
                        });

        Path logDir = workspaceRoot.resolve("agents").resolve(runtimeAgentId).resolve("sessions");
        Files.createDirectories(logDir);
        String jsonl =
                """
                {"type":"message","id":"1","role":"ASSISTANT","content":"Hello from sandbox","timestamp":1}
                """;
        Files.writeString(logDir.resolve(sessionId + ".log.jsonl"), jsonl);

        String content = reader.readSessionLogContent(logicalAgentId, entry);

        assertThat(content).contains("Hello from sandbox");
        assertThat(ownerRef).hasValue(userId);
        assertThat(agentRef).hasValue(runtimeAgentId);
        assertThat(workspacePathRef).hasValue("custom-root");
        verify(sessionAgentManager, never()).history(anyString(), anyInt());
    }

    @Test
    void fallsBackToHistoryWhenBorrowedWorkspaceIsEmpty() {
        String userId = "alice";
        String logicalAgentId = "data-agent";
        String runtimeAgentId = "uca-alice-data-agent";
        String sessionId = "session-2";
        SessionEntry entry =
                new SessionEntry(
                        "agent:" + runtimeAgentId + ":main:" + sessionId,
                        runtimeAgentId,
                        sessionId,
                        null,
                        SessionKind.MAIN,
                        null,
                        0,
                        1L,
                        1L,
                        workspaceRoot.resolve("legacy.json").toString(),
                        null,
                        "|x:agentId=" + runtimeAgentId + "|",
                        userId);

        AgentDefinition definition = agentDefinition(logicalAgentId, null, userId);
        when(catalogService.findVisible(userId, logicalAgentId)).thenReturn(Optional.of(definition));
        when(sessionAgentManager.history(entry.sessionKey(), 0))
                .thenReturn(
                        new HistoryResult(
                                entry.sessionKey(),
                                entry.sessionFilePath(),
                                "legacy payload",
                                null));

        SessionTranscriptReader reader =
                new SessionTranscriptReader(
                        sessionAgentManager,
                        catalogService,
                        (ownerId, agentId, workspacePath) -> new WorkspaceManager(workspaceRoot));

        String content = reader.readSessionLogContent(logicalAgentId, entry);

        assertThat(content).isEqualTo("legacy payload");
        verify(sessionAgentManager).history(entry.sessionKey(), 0);
    }

    private static AgentDefinition agentDefinition(
            String id, String workspacePath, String ownerId) {
        return new AgentDefinition(
                id,
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AgentDefinition.SCOPE_USER,
                ownerId,
                1L,
                1L,
                null,
                AgentDefinition.RUN_AS_INVOKER,
                null,
                workspacePath,
                null,
                null,
                null);
    }
}
