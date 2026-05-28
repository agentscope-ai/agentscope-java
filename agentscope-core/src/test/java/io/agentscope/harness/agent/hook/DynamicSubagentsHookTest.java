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
package io.agentscope.harness.agent.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.legacy.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the two-layer load and override semantics of {@link DynamicSubagentsHook}.
 *
 * <p>Each test exercises one of the three filesystem modes:
 *
 * <ul>
 *   <li>{@code LocalFilesystem} without a userId namespace — Layer 1 has no override scope, so
 *       behaviour must match the legacy build-time scan (regression test for the constraint that
 *       {@code LocalFilesystemWithShell} mode is unaffected).
 *   <li>{@code LocalFilesystem} with a userId namespace + writes to {@code <ns>/subagents/} —
 *       Layer 1 must observe per-user content while Layer 2 still provides the local-disk base.
 *   <li>Filesystem returns nothing (typical sandbox case where subagents live in the host
 *       workspace, not the container) — Layer 2 is the sole source and behaviour matches the
 *       legacy scan.
 * </ul>
 */
class DynamicSubagentsHookTest {

    @TempDir Path tmp;

    // ---------------------------------------------------------------------
    //  Layer 2 only: LocalFilesystem with no userId namespace
    //  Regression: this is the LocalFilesystemWithShell-equivalent path.
    //  Expected outcome: behaviour matches the legacy build-time scan.
    // ---------------------------------------------------------------------
    @Test
    void localFilesystemWithoutNamespace_loadsFromLocalDiskOnly() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSubagentMd(workspace, "reviewer", "Reviews code for quality issues.");

        LocalFilesystem fs = new LocalFilesystem(workspace);
        DefaultAgentManager dam = new DefaultAgentManager(List.of(), null);

        DynamicSubagentsHook hook = newHook(List.of(), fs, workspace, dam);
        fireOnce(hook);

        assertTrue(dam.hasAgent("reviewer"), "Layer 2 declaration must be visible");
        assertEquals(
                "Reviews code for quality issues.",
                dam.getDeclaration("reviewer").orElseThrow().getDescription());
    }

    // ---------------------------------------------------------------------
    //  Layer 1 overrides Layer 2: per-user content under <ns>/subagents/
    //  Different users see different declarations on the same registry.
    // ---------------------------------------------------------------------
    @Test
    void namespacedFilesystem_layer1OverridesLayer2_perUser() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSubagentMd(workspace, "reviewer", "BASE description from local disk.");

        // Alice has her own override of reviewer + a private subagent.
        Files.createDirectories(workspace.resolve("alice/subagents"));
        Files.writeString(
                workspace.resolve("alice/subagents/reviewer.md"),
                frontMatter("ALICE description for reviewer."),
                StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("alice/subagents/scribe.md"),
                frontMatter("ALICE private scribe."),
                StandardCharsets.UTF_8);

        // Bob has nothing in his namespace.

        NamespaceFactory ns =
                rc ->
                        rc == null || rc.getUserId() == null || rc.getUserId().isEmpty()
                                ? List.of()
                                : List.of(rc.getUserId());
        LocalFilesystem fs = new LocalFilesystem(workspace, false, 0, ns);

        DefaultAgentManager dam = new DefaultAgentManager(List.of(), null);
        DynamicSubagentsHook hook = newHook(List.of(), fs, workspace, dam);

        // --- Alice's view ---
        hook.setRuntimeContext(RuntimeContext.builder().userId("alice").build());
        fireOnce(hook);
        assertTrue(dam.hasAgent("reviewer"), "Alice sees reviewer");
        assertTrue(dam.hasAgent("scribe"), "Alice sees her private scribe");
        assertEquals(
                "ALICE description for reviewer.",
                dam.getDeclaration("reviewer").orElseThrow().getDescription(),
                "Layer 1 (alice's override) must win over Layer 2 (local disk)");

        // --- Bob's view ---
        hook.setRuntimeContext(RuntimeContext.builder().userId("bob").build());
        fireOnce(hook);
        assertTrue(dam.hasAgent("reviewer"), "Bob still sees reviewer (Layer 2 fallback)");
        assertEquals(
                "BASE description from local disk.",
                dam.getDeclaration("reviewer").orElseThrow().getDescription(),
                "Bob has no override; Layer 2 base must be visible");
        assertNull(
                dam.getDeclaration("scribe").orElse(null),
                "Bob must NOT see alice's private scribe");
    }

    // ---------------------------------------------------------------------
    //  Programmatic (static) entries are preserved across reloads.
    //  Layer 1 same-name still overrides programmatic.
    // ---------------------------------------------------------------------
    @Test
    void staticEntriesPreserved_andOverriddenByLayer1OnNameClash() throws IOException {
        Path workspace = tmp.resolve("ws");
        Files.createDirectories(workspace.resolve("alice/subagents"));
        Files.writeString(
                workspace.resolve("alice/subagents/reviewer.md"),
                frontMatter("Dynamic-loaded reviewer."),
                StandardCharsets.UTF_8);

        NamespaceFactory ns =
                rc -> rc == null || rc.getUserId() == null ? List.of() : List.of(rc.getUserId());
        LocalFilesystem fs = new LocalFilesystem(workspace, false, 0, ns);

        SubagentEntry staticReviewer =
                new SubagentEntry(
                        "reviewer",
                        "STATIC programmatic reviewer.",
                        stubFactory(),
                        SubagentDeclaration.builder()
                                .name("reviewer")
                                .description("STATIC programmatic reviewer.")
                                .workspaceMode(WorkspaceMode.SHARED)
                                .build());
        SubagentEntry staticOnly =
                new SubagentEntry("librarian", "Static-only librarian.", stubFactory());

        DefaultAgentManager dam =
                new DefaultAgentManager(List.of(staticReviewer, staticOnly), null);
        DynamicSubagentsHook hook =
                newHook(List.of(staticReviewer, staticOnly), fs, workspace, dam);
        hook.setRuntimeContext(RuntimeContext.builder().userId("alice").build());
        fireOnce(hook);

        assertTrue(dam.hasAgent("librarian"), "Static-only entries must survive replaceAgents");
        assertEquals(
                "Dynamic-loaded reviewer.",
                dam.getDeclaration("reviewer").orElseThrow().getDescription(),
                "Layer 1 dynamic must override programmatic same-name registration");
    }

    // ---------------------------------------------------------------------
    //  Sandbox-equivalent: filesystem returns nothing for subagents/ —
    //  fall back to Layer 2 (host workspace) entirely.
    // ---------------------------------------------------------------------
    @Test
    void filesystemReturnsNothing_layer2IsAuthoritative() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSubagentMd(workspace, "researcher", "Researches things.");

        // Filesystem points at an empty directory: nothing to glob.
        Path emptyRoot = tmp.resolve("empty");
        Files.createDirectories(emptyRoot);
        LocalFilesystem fs = new LocalFilesystem(emptyRoot);

        DefaultAgentManager dam = new DefaultAgentManager(List.of(), null);
        DynamicSubagentsHook hook = newHook(List.of(), fs, workspace, dam);
        fireOnce(hook);

        assertTrue(
                dam.hasAgent("researcher"),
                "Layer 2 (host workspace) must remain authoritative when Layer 1 is empty");
    }

    // ---------------------------------------------------------------------
    //  System content injection: rendered ## Subagents section must include
    //  every visible agent_id.
    // ---------------------------------------------------------------------
    @Test
    void appendsSubagentSectionWithAgentIdsToSystemContent() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSubagentMd(workspace, "auditor", "Audits decisions.");

        LocalFilesystem fs = new LocalFilesystem(workspace);
        DefaultAgentManager dam = new DefaultAgentManager(List.of(), null);
        DynamicSubagentsHook hook = newHook(List.of(), fs, workspace, dam);

        PreReasoningEvent event = newPreReasoningEvent();
        hook.onEvent(event).block();

        Msg sys = event.getSystemMessage();
        assertNotNull(sys, "system message must be initialised");
        String rendered = renderSystemContent(sys);
        assertTrue(rendered.contains("## Subagents"), "subagent section header missing");
        assertTrue(rendered.contains("`auditor`"), "auditor must appear in the agent list");
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static DynamicSubagentsHook newHook(
            List<SubagentEntry> staticEntries,
            LocalFilesystem fs,
            Path workspace,
            DefaultAgentManager dam) {
        Function<SubagentDeclaration, SubagentFactory> factoryBuilder = decl -> stubFactory();
        return new DynamicSubagentsHook(
                staticEntries,
                fs,
                workspace,
                factoryBuilder,
                dam,
                /* subagentTool= */ new Object(),
                new DefaultTaskRepository());
    }

    private static void fireOnce(DynamicSubagentsHook hook) {
        hook.onEvent(newPreReasoningEvent()).block();
    }

    private static PreReasoningEvent newPreReasoningEvent() {
        Agent agent = mock(Agent.class);
        return new PreReasoningEvent(
                agent,
                "stub-model",
                null,
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("hi").build())
                                .build()));
    }

    private static SubagentFactory stubFactory() {
        return () -> mock(Agent.class);
    }

    private static void writeSubagentMd(Path workspace, String name, String description)
            throws IOException {
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        Files.writeString(
                subagents.resolve(name + ".md"), frontMatter(description), StandardCharsets.UTF_8);
    }

    private static String frontMatter(String description) {
        return "---\ndescription: " + description + "\nworkspace:\n  mode: shared\n---\n";
    }

    private static String renderSystemContent(Msg sys) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : sys.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
