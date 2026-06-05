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

class PermissionBehaviorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enumeratesExactlyFourBehaviors() {
        assertEquals(4, PermissionBehavior.values().length);
    }

    @Test
    void wireFormatIsLowerCase() {
        assertEquals("allow", PermissionBehavior.ALLOW.getValue());
        assertEquals("deny", PermissionBehavior.DENY.getValue());
        assertEquals("ask", PermissionBehavior.ASK.getValue());
        assertEquals("passthrough", PermissionBehavior.PASSTHROUGH.getValue());
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertSame(PermissionBehavior.ALLOW, PermissionBehavior.fromString("ALLOW"));
        assertSame(PermissionBehavior.PASSTHROUGH, PermissionBehavior.fromString("PassThrough"));
    }

    @Test
    void fromStringRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> PermissionBehavior.fromString("maybe"));
        assertThrows(IllegalArgumentException.class, () -> PermissionBehavior.fromString(null));
    }

    @Test
    void serializesAsScalar() throws Exception {
        assertEquals("\"deny\"", mapper.writeValueAsString(PermissionBehavior.DENY));
        assertSame(PermissionBehavior.ASK, mapper.readValue("\"ask\"", PermissionBehavior.class));
    }
}
