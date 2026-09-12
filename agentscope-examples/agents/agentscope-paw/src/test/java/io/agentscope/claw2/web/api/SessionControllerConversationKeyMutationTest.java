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
package io.agentscope.claw2.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.session.SessionReadStateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Frontend navigates with the conversation UUID ({@code |t:}). Reset / Delete / mark-read must
 * mutate the internal storage key, not that UUID.
 */
class SessionControllerConversationKeyMutationTest {

    private static final String AGENT_ID = "claw";
    private static final String CONVERSATION_ID = "conv-uuid-1";
    private static final String STORAGE_KEY = "agent:claw:main:sid-1";

    @TempDir Path tempDir;

    private SessionAgentManager sessionAgentManager;
    private SessionReadStateStore readStateStore;
    private SessionController controller;
    private SessionEntry entry;

    @BeforeEach
    void setUp() {
        sessionAgentManager = mock(SessionAgentManager.class);
        HarnessGateway gateway = mock(HarnessGateway.class);
        when(gateway.sessionAgentManager()).thenReturn(sessionAgentManager);

        ClawBootstrap bootstrap = mock(ClawBootstrap.class);
        when(bootstrap.gateway()).thenReturn(gateway);
        when(bootstrap.clawHome()).thenReturn(tempDir);

        AgentCatalogService catalog = mock(AgentCatalogService.class);
        when(catalog.resolveGatewayAgentId(AGENT_ID)).thenReturn(AGENT_ID);

        readStateStore = new SessionReadStateStore(bootstrap);
        controller = new SessionController(bootstrap, readStateStore, catalog);

        entry =
                new SessionEntry(
                        STORAGE_KEY,
                        "agent-uuid",
                        "sid-1",
                        "sid-1",
                        SessionKind.MAIN,
                        null,
                        0,
                        2_000L,
                        2_000L,
                        "/tmp/x.json",
                        null,
                        "chatui|r:__anonymous__|t:" + CONVERSATION_ID + "|x:agentId=" + AGENT_ID);
        when(sessionAgentManager.getSession(CONVERSATION_ID)).thenReturn(Optional.empty());
        when(sessionAgentManager.allSessions()).thenReturn(List.of(entry));
        when(sessionAgentManager.resetSession(STORAGE_KEY)).thenReturn(true);
    }

    @Test
    void resetByConversationId_usesInternalStorageKey() {
        SessionController.ResetResult result = controller.reset(AGENT_ID, CONVERSATION_ID).block();

        verify(sessionAgentManager).resetSession(STORAGE_KEY);
        assertTrue(result.reset());
    }

    @Test
    void deleteByConversationId_usesInternalStorageKey() {
        controller.delete(AGENT_ID, CONVERSATION_ID).block();

        verify(sessionAgentManager).removeSession(STORAGE_KEY);
    }

    @Test
    void markReadByConversationId_usesInternalStorageKey() {
        assertTrue(readStateStore.isUnread(STORAGE_KEY, entry.lastActivityMs()));

        controller.markRead(AGENT_ID, CONVERSATION_ID).block();

        assertFalse(readStateStore.isUnread(STORAGE_KEY, entry.lastActivityMs()));
        assertTrue(readStateStore.isUnread(CONVERSATION_ID, entry.lastActivityMs()));
    }
}
