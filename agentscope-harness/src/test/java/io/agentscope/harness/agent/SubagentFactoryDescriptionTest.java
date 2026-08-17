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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the description advertised for subagents registered through
 * {@link HarnessAgent.Builder#subagentFactory(String, String, Function)}.
 *
 * <p>The description is the routing signal the parent agent's model reads when choosing a
 * delegate, so it must be the caller-supplied text when one is given.
 */
class SubagentFactoryDescriptionTest {

    @TempDir Path workspace;

    private Model model;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
    }

    @Test
    @DisplayName("subagentFactory(name, description, factory) advertises the given description")
    void suppliedDescriptionIsAdvertised() {
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .subagentFactory(
                                "code-reviewer", "Reviews diffs for defects", stubFactory())
                        .buildSubagentEntries(workspace);

        assertEquals("Reviews diffs for defects", descriptionOf(entries, "code-reviewer"));
    }

    @Test
    @DisplayName("Blank description falls back to the subagent name")
    void blankDescriptionFallsBackToName() {
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .subagentFactory("blank-desc", "   ", stubFactory())
                        .subagentFactory("null-desc", null, stubFactory())
                        .buildSubagentEntries(workspace);

        assertEquals("blank-desc", descriptionOf(entries, "blank-desc"));
        assertEquals("null-desc", descriptionOf(entries, "null-desc"));
    }

    @Test
    @DisplayName("Two-argument overload keeps advertising the name, unchanged")
    void twoArgOverloadKeepsNameAsDescription() {
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .subagentFactory("legacy-agent", stubFactory())
                        .buildSubagentEntries(workspace);

        assertEquals("legacy-agent", descriptionOf(entries, "legacy-agent"));
    }

    @Test
    @DisplayName("Description also reaches the static entry path used when sandboxed")
    void staticEntryPathCarriesDescription() {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .subagentFactory("doc-writer", "Writes user-facing docs", stubFactory());

        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(builder, workspace, null);

        assertEquals("Writes user-facing docs", descriptionOf(entries, "doc-writer"));
    }

    private Function<String, Agent> stubFactory() {
        return name -> HarnessAgent.builder().name(name).model(model).workspace(workspace).build();
    }

    private static String descriptionOf(List<SubagentEntry> entries, String name) {
        return entries.stream()
                .filter(e -> name.equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing subagent entry: " + name))
                .description();
    }
}
