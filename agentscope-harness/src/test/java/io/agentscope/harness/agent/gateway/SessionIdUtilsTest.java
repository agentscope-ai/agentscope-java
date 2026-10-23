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

import org.junit.jupiter.api.Test;

class SessionIdUtilsTest {

    @Test
    void sameInputs_produceSameHash() {
        String h1 = SessionIdUtils.deterministicHash("chatui|r:session-a");
        String h2 = SessionIdUtils.deterministicHash("chatui|r:session-a");
        assertEquals(h1, h2);
    }

    @Test
    void differentInputs_produceDifferentHash() {
        String h1 = SessionIdUtils.deterministicHash("chatui|r:session-a");
        String h2 = SessionIdUtils.deterministicHash("chatui|r:session-b");
        assertNotEquals(h1, h2);
    }

    @Test
    void hashIs12HexChars() {
        String h = SessionIdUtils.deterministicHash("any-input");
        assertEquals(12, h.length());
        assertTrue(h.matches("[0-9a-f]{12}"));
    }

    @Test
    void multipleParts_joinedBySlash() {
        String joined = SessionIdUtils.deterministicHash("a", "b", "c");
        String manual = SessionIdUtils.deterministicHash("a/b/c");
        assertEquals(joined, manual);
    }

    @Test
    void singlePart_works() {
        String h = SessionIdUtils.deterministicHash("solo");
        assertEquals(12, h.length());
    }
}
