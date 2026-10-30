/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AguiProtocolTest {

    @Test
    void protocolVersionConstant() {
        assertEquals("1.0.0", AguiProtocol.PROTOCOL_VERSION);
    }

    @Test
    void absentConsumerDoesNotSupportAttribution() {
        assertFalse(AguiProtocol.supportsSubagentAttribution(null));
        assertFalse(AguiProtocol.supportsSubagentAttribution(""));
        assertFalse(AguiProtocol.supportsSubagentAttribution("   "));
    }

    @Test
    void oneDotZeroSupportsAttribution() {
        assertTrue(AguiProtocol.supportsSubagentAttribution("1.0.0"));
        assertTrue(AguiProtocol.supportsSubagentAttribution("1.0"));
        assertTrue(AguiProtocol.supportsSubagentAttribution("1.0.1"));
        assertTrue(AguiProtocol.supportsSubagentAttribution("1.1.0"));
        assertTrue(AguiProtocol.supportsSubagentAttribution("2.0.0"));
    }

    @Test
    void preOneDoesNotSupportAttribution() {
        assertFalse(AguiProtocol.supportsSubagentAttribution("0.0.57"));
        assertFalse(AguiProtocol.supportsSubagentAttribution("0.9.0"));
        assertFalse(AguiProtocol.supportsSubagentAttribution("0.9"));
    }

    @Test
    void compareTreatsMissingPatchAsZero() {
        assertEquals(0, AguiProtocol.compare("1.0", "1.0.0"));
        assertTrue(AguiProtocol.compare("1.0.1", "1.0.0") > 0);
        assertTrue(AguiProtocol.compare("1.0.0", "1.1") < 0);
    }
}
