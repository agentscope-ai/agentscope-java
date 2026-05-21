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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DynamicSkillHook} covering the two-layer load and the override semantics
 * across the three supported filesystem modes:
 *
 * <ul>
 *   <li>{@code LocalFilesystem} without a userId namespace — Layer 1 has no override scope, so
 *       behaviour must match the legacy build-time scan (regression test for the constraint that
 *       {@code LocalFilesystemWithShell} mode is unaffected).
 *   <li>{@code LocalFilesystem} with a userId namespace + writes to {@code <ns>/skills/} —
 *       Layer 1 must observe per-user content while Layer 2 still provides the local-disk base.
 *   <li>Filesystem returns nothing (typical sandbox case where skills live in the host
 *       workspace, not the container) — Layer 2 is the sole source.
 * </ul>
 */
class DynamicSkillHookTest {

    @TempDir Path tmp;

    // ---------------------------------------------------------------------
    //  Layer 2 only: LocalFilesystem with no userId namespace
    //  Regression: this is the LocalFilesystemWithShell-equivalent path.
    // ---------------------------------------------------------------------
    @Test
    void localFilesystemWithoutNamespace_loadsFromLocalDiskOnly() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "Reviews code for quality issues.");

        LocalFilesystem fs = new LocalFilesystem(workspace);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = new DynamicSkillHook(fs, workspace, toolkit);

        fireOnce(hook);

        SkillBox box = hook.getCurrentSkillBox();
        assertNotNull(box, "SkillBox must be created when at least one skill is loaded");
        assertTrue(
                containsSkill(box, "reviewer"), "Layer 2 (local disk) declaration must be visible");
    }

    // ---------------------------------------------------------------------
    //  Layer 1 overrides Layer 2: per-user content under <ns>/skills/
    //  Different users see different skill content on the same registry.
    // ---------------------------------------------------------------------
    @Test
    void namespacedFilesystem_layer1OverridesLayer2_perUser() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "BASE description from local disk.");

        // Alice has her own override of reviewer + a private skill.
        writeNamespacedSkillMd(workspace, "alice", "reviewer", "ALICE override of reviewer.");
        writeNamespacedSkillMd(workspace, "alice", "scribe", "ALICE private scribe.");

        // Bob has nothing in his namespace.

        AtomicReference<String> userRef = new AtomicReference<>();
        NamespaceFactory ns =
                () ->
                        userRef.get() == null || userRef.get().isEmpty()
                                ? List.of()
                                : List.of(userRef.get());
        LocalFilesystem fs = new LocalFilesystem(workspace, false, 0, ns);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = new DynamicSkillHook(fs, workspace, toolkit);

        // --- Alice's view ---
        userRef.set("alice");
        fireOnce(hook);
        SkillBox aliceBox = hook.getCurrentSkillBox();
        assertNotNull(aliceBox, "Alice sees at least the overridden + private skills");
        assertTrue(containsSkill(aliceBox, "reviewer"), "Alice sees reviewer");
        assertTrue(containsSkill(aliceBox, "scribe"), "Alice sees her private scribe");
        assertTrue(
                aliceBox.getSkillPrompt().contains("ALICE override of reviewer."),
                "Layer 1 (alice's override) must win over Layer 2 (local disk)");

        // --- Bob's view ---
        userRef.set("bob");
        fireOnce(hook);
        SkillBox bobBox = hook.getCurrentSkillBox();
        assertNotNull(bobBox, "Bob still sees the Layer 2 reviewer");
        assertTrue(containsSkill(bobBox, "reviewer"), "Bob still sees reviewer (Layer 2 fallback)");
        assertTrue(
                bobBox.getSkillPrompt().contains("BASE description from local disk."),
                "Bob has no override; Layer 2 base must be visible");
        assertNull(findSkill(bobBox, "scribe"), "Bob must NOT see alice's private scribe");
    }

    // ---------------------------------------------------------------------
    //  Sandbox-equivalent: filesystem returns nothing for skills/ —
    //  fall back to Layer 2 (host workspace) entirely.
    // ---------------------------------------------------------------------
    @Test
    void filesystemReturnsNothing_layer2IsAuthoritative() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "researcher", "Researches things.");

        // Filesystem points at an empty directory: nothing to glob.
        Path emptyRoot = tmp.resolve("empty");
        Files.createDirectories(emptyRoot);
        LocalFilesystem fs = new LocalFilesystem(emptyRoot);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = new DynamicSkillHook(fs, workspace, toolkit);

        fireOnce(hook);

        SkillBox box = hook.getCurrentSkillBox();
        assertNotNull(box, "Layer 2 must remain authoritative when Layer 1 is empty");
        assertTrue(
                containsSkill(box, "researcher"),
                "Local-disk skill must be present via Layer 2 fallback");
    }

    // ---------------------------------------------------------------------
    //  No skills anywhere: the hook must keep currentSkillBox == null so
    //  it does not append an empty section to the system content.
    // ---------------------------------------------------------------------
    @Test
    void noSkillsAnywhere_skillBoxRemainsNull() throws IOException {
        Path workspace = tmp.resolve("ws");
        Files.createDirectories(workspace);
        LocalFilesystem fs = new LocalFilesystem(workspace);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = new DynamicSkillHook(fs, workspace, toolkit);

        fireOnce(hook);

        assertNull(hook.getCurrentSkillBox(), "Empty load must leave skillBox unset");
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static void fireOnce(DynamicSkillHook hook) {
        Agent agent = mock(Agent.class);
        hook.onEvent(
                        new io.agentscope.core.hook.PreCallEvent(
                                agent,
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(TextBlock.builder().text("hi").build())
                                                .build())))
                .block();
    }

    private static void writeSkillMd(Path workspace, String name, String description)
            throws IOException {
        Path skillDir = workspace.resolve("skills").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                skillFrontMatter(name, description),
                StandardCharsets.UTF_8);
    }

    private static void writeNamespacedSkillMd(
            Path workspace, String userId, String name, String description) throws IOException {
        Path skillDir = workspace.resolve(userId).resolve("skills").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                skillFrontMatter(name, description),
                StandardCharsets.UTF_8);
    }

    private static String skillFrontMatter(String name, String description) {
        return "---\nname: "
                + name
                + "\ndescription: "
                + description
                + "\n---\n\nSkill body for "
                + name
                + ".\n";
    }

    private static boolean containsSkill(SkillBox box, String name) {
        return findSkill(box, name) != null;
    }

    private static AgentSkill findSkill(SkillBox box, String name) {
        Set<String> ids = box.getAllSkillIds();
        if (ids == null) {
            return null;
        }
        for (String id : ids) {
            AgentSkill skill = box.getSkill(id);
            if (skill != null && name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }
}
