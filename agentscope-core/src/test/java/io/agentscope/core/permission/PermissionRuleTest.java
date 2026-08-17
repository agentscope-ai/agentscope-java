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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PermissionRuleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowsNullRuleContent() {
        PermissionRule rule =
                new PermissionRule("Bash", null, PermissionBehavior.ALLOW, "userSettings");
        assertNull(rule.ruleContent());
        assertEquals("Bash", rule.toolName());
    }

    @Test
    void rejectsNullToolName() {
        assertThrows(
                NullPointerException.class,
                () -> new PermissionRule(null, "git:*", PermissionBehavior.ALLOW, "test"));
    }

    @Test
    void rejectsNullBehavior() {
        assertThrows(
                NullPointerException.class,
                () -> new PermissionRule("Bash", "git:*", null, "test"));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(
                NullPointerException.class,
                () -> new PermissionRule("Bash", "git:*", PermissionBehavior.ALLOW, null));
    }

    @Test
    void jsonUsesSnakeCase() throws Exception {
        PermissionRule rule =
                new PermissionRule("Bash", "git:*", PermissionBehavior.ALLOW, "userSettings");
        String json = mapper.writeValueAsString(rule);
        assertTrue(json.contains("\"tool_name\":\"Bash\""), json);
        assertTrue(json.contains("\"rule_content\":\"git:*\""), json);
        assertTrue(json.contains("\"behavior\":\"allow\""), json);
        assertTrue(json.contains("\"source\":\"userSettings\""), json);
    }

    @Test
    void jsonRoundTripPreservesValues() throws Exception {
        PermissionRule original =
                new PermissionRule("Read", "src/**", PermissionBehavior.ASK, "projectSettings");
        String json = mapper.writeValueAsString(original);
        PermissionRule decoded = mapper.readValue(json, PermissionRule.class);
        assertEquals(original, decoded);
    }

    @Test
    void jsonRoundTripWithNullRuleContent() throws Exception {
        PermissionRule original = new PermissionRule("Bash", null, PermissionBehavior.DENY, "test");
        String json = mapper.writeValueAsString(original);
        PermissionRule decoded = mapper.readValue(json, PermissionRule.class);
        assertEquals(original, decoded);
    }
}
