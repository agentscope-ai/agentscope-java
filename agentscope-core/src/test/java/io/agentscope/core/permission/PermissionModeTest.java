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
package io.agentscope.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PermissionModeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enumeratesExactlyFiveModes() {
        assertEquals(5, PermissionMode.values().length);
    }

    @Test
    void wireFormatIsLowerSnake() {
        assertEquals("default", PermissionMode.DEFAULT.getValue());
        assertEquals("accept_edits", PermissionMode.ACCEPT_EDITS.getValue());
        assertEquals("explore", PermissionMode.EXPLORE.getValue());
        assertEquals("bypass", PermissionMode.BYPASS.getValue());
        assertEquals("dont_ask", PermissionMode.DONT_ASK.getValue());
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertSame(PermissionMode.ACCEPT_EDITS, PermissionMode.fromString("ACCEPT_EDITS"));
        assertSame(PermissionMode.DONT_ASK, PermissionMode.fromString("Dont_Ask"));
    }

    @Test
    void fromStringRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.fromString("nope"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.fromString(null));
    }

    @Test
    void serializesAsScalar() throws Exception {
        assertEquals("\"explore\"", mapper.writeValueAsString(PermissionMode.EXPLORE));
        assertSame(
                PermissionMode.ACCEPT_EDITS,
                mapper.readValue("\"accept_edits\"", PermissionMode.class));
    }
}
