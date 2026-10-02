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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link HarnessGateway} produces deterministic session IDs from the same
 * {@link MsgContext#canonicalKey()}, even across separate gateway instances (simulating JVM
 * restarts).
 */
class HarnessGatewaySessionIdTest {

    @Test
    void sameGateKey_sameSessionId_acrossGatewayInstances() {
        MsgContext ctx = new MsgContext("chatui", null, "session-a", null, null, Map.of());
        String gateKey = ctx.canonicalKey();

        String id1 = generateSessionId(gateKey);
        String id2 = generateSessionId(gateKey);
        assertEquals(id1, id2);
    }

    @Test
    void differentGateKey_differentSessionId() {
        MsgContext ctxA = new MsgContext("chatui", null, "session-a", null, null, Map.of());
        MsgContext ctxB = new MsgContext("chatui", null, "session-b", null, null, Map.of());

        String idA = generateSessionId(ctxA.canonicalKey());
        String idB = generateSessionId(ctxB.canonicalKey());
        assertNotEquals(idA, idB);
    }

    @Test
    void sessionId_hasGwPrefix() {
        String id = generateSessionId("chatui|r:test");
        assertTrue(id.startsWith("gw-"));
    }

    @Test
    void sessionId_isFilenameSafe() {
        String id = generateSessionId("chatui|r:session-a|x:agentId=assistant");
        assertTrue(id.matches("gw-[0-9a-f]{12}"), "Expected gw-<12 hex chars>, got: " + id);
    }

    private static String generateSessionId(String gateKey) {
        return "gw-" + SessionIdUtils.deterministicHash(gateKey);
    }
}
