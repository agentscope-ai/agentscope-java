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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Phase A schema additions to {@link SubagentDeclaration}: {@code temperature}, {@code topP},
 * {@code variant}, {@code steps} (renamed from {@code maxIters}).
 */
class SubagentDeclarationPhaseATest {

    @Test
    void builderDefaults_areInheritSemantics() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("d")
                        .description("desc")
                        .inlineAgentsBody("body")
                        .build();
        assertNull(decl.getTemperature());
        assertNull(decl.getTopP());
        assertNull(decl.getVariant());
        // steps default = 10
        assertEquals(10, decl.getSteps());
        // Deprecated alias returns same value
        assertEquals(10, decl.getMaxIters());
    }

    @Test
    void builder_setsHyperparams() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("d")
                        .description("desc")
                        .inlineAgentsBody("body")
                        .temperature(0.3)
                        .topP(0.9)
                        .variant("thinking")
                        .steps(12)
                        .build();
        assertEquals(0.3, decl.getTemperature());
        assertEquals(0.9, decl.getTopP());
        assertEquals("thinking", decl.getVariant());
        assertEquals(12, decl.getSteps());
        assertEquals(12, decl.getMaxIters());
    }

    @Test
    void deprecatedMaxItersBuilder_isStepsAlias() {
        @SuppressWarnings("deprecation")
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("d")
                        .description("desc")
                        .inlineAgentsBody("body")
                        .maxIters(7)
                        .build();
        assertEquals(7, decl.getSteps());
    }

    @Test
    void loader_parsesAllHyperparams() {
        String md =
                """
                ---
                description: A test agent
                temperature: 0.4
                top_p: 0.85
                variant: thinking
                steps: 15
                ---
                Body content.
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "agent-a", null);
        assertNotNull(decl);
        assertEquals("agent-a", decl.getName());
        assertEquals(0.4, decl.getTemperature());
        assertEquals(0.85, decl.getTopP());
        assertEquals("thinking", decl.getVariant());
        assertEquals(15, decl.getSteps());
    }

    @Test
    void loader_acceptsCamelCaseTopP() {
        String md =
                """
                ---
                description: A test agent
                topP: 0.75
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "a", null);
        assertNotNull(decl);
        assertEquals(0.75, decl.getTopP());
    }

    @Test
    void loader_prefersStepsOverDeprecatedMaxIters() {
        String md =
                """
                ---
                description: A test agent
                steps: 20
                maxIters: 5
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "a", null);
        assertNotNull(decl);
        assertEquals(20, decl.getSteps());
    }

    @Test
    void loader_fallsBackToMaxItersWhenStepsAbsent() {
        String md =
                """
                ---
                description: A test agent
                maxIters: 8
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "a", null);
        assertNotNull(decl);
        assertEquals(8, decl.getSteps());
    }

    @Test
    void loader_omittedHyperparams_areNullForInheritance() {
        String md =
                """
                ---
                description: Minimal agent
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "a", null);
        assertNotNull(decl);
        assertNull(decl.getTemperature());
        assertNull(decl.getTopP());
        assertNull(decl.getVariant());
        assertEquals(10, decl.getSteps());
    }

    @Test
    void loader_malformedNumeric_isTreatedAsUnset() {
        String md =
                """
                ---
                description: Loose typing
                temperature: nope
                top_p: also-bad
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "a", null);
        assertNotNull(decl);
        assertNull(decl.getTemperature());
        assertNull(decl.getTopP());
    }
}
