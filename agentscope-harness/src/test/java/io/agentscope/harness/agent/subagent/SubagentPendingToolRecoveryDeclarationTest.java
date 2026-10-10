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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SubagentPendingToolRecoveryDeclarationTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void builderPreservesTriState(Boolean enabled) {
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Worker")
                        .inlineAgentsBody("Complete the task.")
                        .enablePendingToolRecovery(enabled)
                        .build();
        assertEquals(enabled, declaration.getEnablePendingToolRecovery());
    }

    @ParameterizedTest
    @CsvSource({
        "enable_pending_tool_recovery,true",
        "enable_pending_tool_recovery,false",
        "enablePendingToolRecovery,true",
        "enablePendingToolRecovery,false"
    })
    void loaderParsesBothSpellings(String key, boolean enabled) {
        SubagentDeclaration declaration = parse(key + ": " + enabled);
        assertEquals(enabled, declaration.getEnablePendingToolRecovery());
    }

    @Test
    void omittedOrNullSettingInheritsParent() {
        assertNull(parse("").getEnablePendingToolRecovery());
        assertNull(parse("enable_pending_tool_recovery: null").getEnablePendingToolRecovery());
    }

    @Test
    void explicitSnakeCaseFalseTakesPrecedenceOverCamelCaseTrue() {
        assertEquals(
                false,
                parse("enable_pending_tool_recovery: false\nenablePendingToolRecovery: true")
                        .getEnablePendingToolRecovery());
    }

    private static SubagentDeclaration parse(String setting) {
        return AgentSpecLoader.parse(
                "---\ndescription: Worker\n" + setting + "\n---\nComplete the task.\n",
                "worker",
                null);
    }
}
