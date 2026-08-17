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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase A {@code mode} + {@code hidden} semantics: schema parsing, LLM-facing visibility
 * filtering, and spawn-rejection of PRIMARY-only declarations.
 */
class SubagentModeHiddenTest {

    @Test
    void loader_parsesModeAndHidden() {
        String md =
                """
                ---
                description: An internal compaction agent
                mode: primary
                hidden: true
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "compaction", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.PRIMARY, decl.getMode());
        assertTrue(decl.isHidden());
    }

    @Test
    void loader_modeDefaultsToAll_andHiddenDefaultsFalse() {
        String md =
                """
                ---
                description: Plain agent
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "plain", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.ALL, decl.getMode());
        assertFalse(decl.isHidden());
    }

    @Test
    void loader_acceptsSubagentMode() {
        String md =
                """
                ---
                description: Worker-only
                mode: subagent
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "worker", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.SUBAGENT, decl.getMode());
    }

    @Test
    void loader_unknownMode_fallsBackToAllWithLog() {
        String md =
                """
                ---
                description: Bad mode value
                mode: superduper
                ---
                body
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(md, "x", null);
        assertNotNull(decl);
        assertEquals(SubagentDeclaration.Mode.ALL, decl.getMode());
    }

    @Test
    void renderSubagentSection_hidesHiddenAndPrimaryOnly() {
        SubagentDeclaration plain =
                SubagentDeclaration.builder()
                        .name("plain")
                        .description("public agent")
                        .inlineAgentsBody("b")
                        .build();
        SubagentDeclaration hidden =
                SubagentDeclaration.builder()
                        .name("secret")
                        .description("internal use only")
                        .inlineAgentsBody("b")
                        .hidden(true)
                        .build();
        SubagentDeclaration primaryOnly =
                SubagentDeclaration.builder()
                        .name("compaction")
                        .description("Compacts conversations")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.PRIMARY)
                        .build();

        SubagentFactory noopFactory = (rc) -> (Agent) null;
        List<SubagentEntry> entries =
                List.of(
                        new SubagentEntry("plain", plain.getDescription(), noopFactory, plain),
                        new SubagentEntry("secret", hidden.getDescription(), noopFactory, hidden),
                        new SubagentEntry(
                                "compaction",
                                primaryOnly.getDescription(),
                                noopFactory,
                                primaryOnly));

        String rendered = SubagentsMiddleware.renderSubagentSection(entries, false);

        // Use the rendered line prefix `- \`<name>\`` so we don't false-match on words that
        // appear inside the static section template body (e.g. "compaction" in the
        // "after conversation compaction" guidance line).
        assertTrue(rendered.contains("- `plain`:"), "public agent should be listed");
        assertFalse(rendered.contains("- `secret`:"), "hidden agent must not be listed");
        assertFalse(
                rendered.contains("- `compaction`:"),
                "PRIMARY-only declaration must not appear in spawnable list");
    }

    @Test
    void renderSubagentSection_programmaticEntriesAlwaysShown() {
        SubagentFactory noopFactory = (rc) -> (Agent) null;
        // declaration == null path — programmatic registration via the 3-arg constructor.
        SubagentEntry entry = new SubagentEntry("bare", "Bare programmatic", noopFactory);
        String rendered = SubagentsMiddleware.renderSubagentSection(List.of(entry), false);
        assertTrue(rendered.contains("bare"));
    }

    @Test
    void defaultAgentManager_primaryOnlyRejectsSpawn() {
        SubagentDeclaration primaryOnly =
                SubagentDeclaration.builder()
                        .name("compaction")
                        .description("Compacts conversations")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.PRIMARY)
                        .build();
        SubagentFactory shouldNotRun =
                (rc) -> {
                    throw new AssertionError(
                            "factory.create() must not be invoked for PRIMARY-only");
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "compaction",
                                        primaryOnly.getDescription(),
                                        shouldNotRun,
                                        primaryOnly)),
                        null);
        assertTrue(
                mgr.createAgentIfPresent(
                                "compaction", io.agentscope.core.agent.RuntimeContext.empty())
                        .isEmpty());
        assertTrue(mgr.isPrimaryOnly("compaction"));
    }

    @Test
    void defaultAgentManager_subagentMode_isSpawnable() {
        // Verify the gate via hasAgent/isPrimaryOnly + the declaration round-trip, rather than
        // calling createAgentIfPresent — building a real Agent here would pull in the full
        // ReActAgent stack just to assert the gating decision.
        SubagentDeclaration worker =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Worker-only")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.SUBAGENT)
                        .build();
        SubagentFactory shouldNotRunInThisTest =
                (rc) -> {
                    throw new AssertionError(
                            "factory.create() not exercised by this assertion path");
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker",
                                        worker.getDescription(),
                                        shouldNotRunInThisTest,
                                        worker)),
                        null);
        assertTrue(mgr.hasAgent("worker"));
        assertFalse(mgr.isPrimaryOnly("worker"));
        assertTrue(mgr.getDeclaration("worker").isPresent());
        assertEquals(
                SubagentDeclaration.Mode.SUBAGENT, mgr.getDeclaration("worker").get().getMode());
    }
}
