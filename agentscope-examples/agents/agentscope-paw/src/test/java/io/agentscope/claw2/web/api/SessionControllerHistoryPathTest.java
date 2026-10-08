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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

class SessionControllerHistoryPathTest {

    @Test
    void extractConversationId_fromThreadSegment() {
        assertEquals(
                "conv-1",
                SessionController.extractConversationId(
                        "chatui|r:__anonymous__|t:conv-1|x:agentId=claw"));
        assertNull(SessionController.extractConversationId("chatui|x:agentId=claw"));
        assertNull(SessionController.extractConversationId(null));
    }

    @Test
    void sessionMatchesAgent_ignoresConversationThread() {
        SessionEntry a = main("sk-a", "sid-a", "chatui|r:u|t:conv-a|x:agentId=claw");
        SessionEntry b = main("sk-b", "sid-b", "chatui|r:u|t:conv-b|x:agentId=claw");
        SessionEntry other = main("sk-c", "sid-c", "chatui|x:agentId=other");

        assertTrue(SessionController.sessionMatchesAgent(a, "claw"));
        assertTrue(SessionController.sessionMatchesAgent(b, "claw"));
        assertFalse(SessionController.sessionMatchesAgent(other, "claw"));
        assertNotEquals(a.gateKey(), b.gateKey());
    }

    @Test
    void transcriptReadContext_carriesSessionId() {
        SessionEntry e =
                main("sk", "main-4de78fdf-78da-4a99-8aef-0520ba74ab02", "chatui|x:agentId=claw");
        RuntimeContext rc = SessionController.transcriptReadContext(e);
        assertEquals("main-4de78fdf-78da-4a99-8aef-0520ba74ab02", rc.getSessionId());
    }

    private static SessionEntry main(String sessionKey, String sessionId, String gateKey) {
        return new SessionEntry(
                sessionKey,
                "agent-uuid",
                sessionId,
                sessionId,
                SessionKind.MAIN,
                null,
                0,
                1L,
                2L,
                "/tmp/x.json",
                null,
                gateKey);
    }
}
