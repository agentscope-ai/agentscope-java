/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CloseInfo Tests")
class CloseInfoTest {

    @Test
    @DisplayName("Should create CloseInfo with code and reason")
    void shouldCreateCloseInfoWithCodeAndReason() {
        CloseInfo closeInfo = new CloseInfo(1000, "Normal closure");

        assertEquals(1000, closeInfo.code());
        assertEquals("Normal closure", closeInfo.reason());
    }

    @Test
    @DisplayName("Should identify normal closure")
    void shouldIdentifyNormalClosure() {
        CloseInfo normalClose = new CloseInfo(CloseInfo.NORMAL_CLOSURE, "Normal");
        CloseInfo abnormalClose = new CloseInfo(CloseInfo.ABNORMAL_CLOSURE, "Abnormal");

        assertTrue(normalClose.isNormal());
        assertFalse(abnormalClose.isNormal());
    }

    @Test
    @DisplayName("Should create normal closure via factory method")
    void shouldCreateNormalClosureViaFactoryMethod() {
        CloseInfo closeInfo = CloseInfo.normal("Session ended");

        assertEquals(CloseInfo.NORMAL_CLOSURE, closeInfo.code());
        assertEquals("Session ended", closeInfo.reason());
        assertTrue(closeInfo.isNormal());
    }

    @Test
    @DisplayName("Should create abnormal closure via factory method")
    void shouldCreateAbnormalClosureViaFactoryMethod() {
        CloseInfo closeInfo = CloseInfo.abnormal("Connection lost");

        assertEquals(CloseInfo.ABNORMAL_CLOSURE, closeInfo.code());
        assertEquals("Connection lost", closeInfo.reason());
        assertFalse(closeInfo.isNormal());
    }

    @Test
    @DisplayName("Should have correct close code constants")
    void shouldHaveCorrectCloseCodeConstants() {
        assertEquals(1000, CloseInfo.NORMAL_CLOSURE);
        assertEquals(1001, CloseInfo.GOING_AWAY);
        assertEquals(1002, CloseInfo.PROTOCOL_ERROR);
        assertEquals(1006, CloseInfo.ABNORMAL_CLOSURE);
    }
}
