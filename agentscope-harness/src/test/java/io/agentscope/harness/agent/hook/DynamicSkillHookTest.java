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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.FilesystemBackedSkillRepository;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DynamicSkillHook} covering the four-layer composite repository merge that
 * powers HarnessAgent's skill wiring:
 *
 * <ul>
 *   <li>Workspace-only path (regression for {@code LocalFilesystem} mode without a namespace) —
 *       only Layer 3 (workspace agent-shared) contributes skills.
 *   <li>Per-user namespace overrides workspace — Layer 4 wins on same-name skills.
 *   <li>Marketplace repos compose between project-global and workspace — verifies the four-layer
 *       precedence with a fake in-memory marketplace repo.
 *   <li>Filesystem returns nothing — Layer 3 (workspace) is authoritative.
 *   <li>No skills anywhere — {@code currentSkillBox} stays null so the prompt isn't appended.
 *   <li>After first fire — {@code DynamicSkillHook} registers the {@code skill-build-in-tools}
 *       tool group on the toolkit (parity with the legacy {@code SkillHook} path).
 * </ul>
 */
class DynamicSkillHookTest {

    @TempDir Path tmp;

    // ---------------------------------------------------------------------
    //  Workspace only (Layer 3): LocalFilesystem with no userId namespace
    //  Regression: this is the LocalFilesystemWithShell-equivalent path.
    // ---------------------------------------------------------------------
    @Test
    void localFilesystemWithoutNamespace_loadsFromWorkspaceOnly() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "Reviews code for quality issues.");

        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = newHook(toolkit, workspace, new LocalFilesystem(workspace));

        fireOnce(hook);

        SkillBox box = hook.getCurrentSkillBox();
        assertNotNull(box, "SkillBox must be created when at least one skill is loaded");
        assertTrue(
                containsSkill(box, "reviewer"), "Layer 3 (workspace) declaration must be visible");
    }

    // ---------------------------------------------------------------------
    //  Layer 4 overrides Layer 3: per-user content under <ns>/skills/
    //  Different users see different skill content on the same registry.
    // ---------------------------------------------------------------------
    @Test
    void namespacedFilesystem_layer4OverridesLayer3_perUser() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "BASE description from local disk.");

        // Alice has her own override of reviewer + a private skill.
        writeNamespacedSkillMd(workspace, "alice", "reviewer", "ALICE override of reviewer.");
        writeNamespacedSkillMd(workspace, "alice", "scribe", "ALICE private scribe.");

        // Bob has nothing in his namespace.

        AtomicReference<String> userRef = new AtomicReference<>();
        NamespaceFactory ns =
                rc ->
                        rc == null || rc.getUserId() == null || rc.getUserId().isEmpty()
                                ? List.of()
                                : List.of(rc.getUserId());
        LocalFilesystem fs = new LocalFilesystem(workspace, false, 0, ns);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = newHookWithUser(toolkit, workspace, fs, userRef);

        // --- Alice's view ---
        userRef.set("alice");
        fireOnce(hook);
        SkillBox aliceBox = hook.getCurrentSkillBox();
        assertNotNull(aliceBox, "Alice sees at least the overridden + private skills");
        assertTrue(containsSkill(aliceBox, "reviewer"), "Alice sees reviewer");
        assertTrue(containsSkill(aliceBox, "scribe"), "Alice sees her private scribe");
        assertTrue(
                aliceBox.getSkillPrompt().contains("ALICE override of reviewer."),
                "Layer 4 (alice's override) must win over Layer 3 (workspace)");

        // --- Bob's view ---
        userRef.set("bob");
        fireOnce(hook);
        SkillBox bobBox = hook.getCurrentSkillBox();
        assertNotNull(bobBox, "Bob still sees the Layer 3 reviewer");
        assertTrue(containsSkill(bobBox, "reviewer"), "Bob still sees reviewer (Layer 3 fallback)");
        assertTrue(
                bobBox.getSkillPrompt().contains("BASE description from local disk."),
                "Bob has no override; Layer 3 base must be visible");
        assertNull(findSkill(bobBox, "scribe"), "Bob must NOT see alice's private scribe");
    }

    // ---------------------------------------------------------------------
    //  Four-layer precedence: marketplace repo composes between project-global
    //  and workspace. Workspace must win over marketplace, namespace must win
    //  over workspace.
    // ---------------------------------------------------------------------
    @Test
    void marketplaceRepo_isOverriddenByWorkspace_andByNamespace() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "WORKSPACE reviewer description.");
        writeNamespacedSkillMd(workspace, "alice", "reviewer", "ALICE namespaced reviewer.");

        AgentSkillRepository marketplace =
                new InMemorySkillRepository(
                        "marketplace",
                        AgentSkill.builder()
                                .name("reviewer")
                                .description("MARKETPLACE reviewer description.")
                                .skillContent("Marketplace reviewer body")
                                .source("marketplace")
                                .build(),
                        AgentSkill.builder()
                                .name("forecaster")
                                .description("MARKETPLACE-only forecaster.")
                                .skillContent("Forecast body")
                                .source("marketplace")
                                .build());

        AtomicReference<String> userRef = new AtomicReference<>();
        NamespaceFactory ns =
                rc ->
                        rc == null || rc.getUserId() == null || rc.getUserId().isEmpty()
                                ? List.of()
                                : List.of(rc.getUserId());
        LocalFilesystem fs = new LocalFilesystem(workspace, false, 0, ns);
        Toolkit toolkit = new Toolkit();

        // Compose the four-layer list manually (HarnessAgent.Builder builds the same shape).
        List<AgentSkillRepository> repos = new ArrayList<>();
        repos.add(marketplace); // Layer 2
        repos.add(new FileSystemSkillRepository(workspace.resolve("skills"))); // Layer 3
        repos.add(
                new FilesystemBackedSkillRepository(
                        fs,
                        "skills",
                        () ->
                                userRef.get() == null
                                        ? RuntimeContext.empty()
                                        : RuntimeContext.builder().userId(userRef.get()).build(),
                        "ns")); // Layer 4
        DynamicSkillHook hook = new DynamicSkillHook(repos, toolkit);

        // --- No user: workspace overrides marketplace, marketplace-only stays. ---
        userRef.set(null);
        fireOnce(hook);
        SkillBox box = hook.getCurrentSkillBox();
        assertNotNull(box);
        assertTrue(
                box.getSkillPrompt().contains("WORKSPACE reviewer description."),
                "Layer 3 (workspace) must override Layer 2 (marketplace) for shared name");
        assertTrue(
                containsSkill(box, "forecaster"),
                "Marketplace-only skill must remain when no higher layer overrides it");

        // --- Alice: namespace overrides everything. ---
        userRef.set("alice");
        fireOnce(hook);
        SkillBox aliceBox = hook.getCurrentSkillBox();
        assertNotNull(aliceBox);
        assertTrue(
                aliceBox.getSkillPrompt().contains("ALICE namespaced reviewer."),
                "Layer 4 (namespace) must win over Layer 3 (workspace) and Layer 2 (marketplace)");
        assertTrue(
                containsSkill(aliceBox, "forecaster"),
                "Marketplace-only skill remains visible to Alice");
    }

    // ---------------------------------------------------------------------
    //  Sandbox-equivalent: filesystem returns nothing for skills/ —
    //  fall back to Layer 3 (workspace) entirely.
    // ---------------------------------------------------------------------
    @Test
    void filesystemReturnsNothing_workspaceIsAuthoritative() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "researcher", "Researches things.");

        // Filesystem points at an empty directory: nothing to glob.
        Path emptyRoot = tmp.resolve("empty");
        Files.createDirectories(emptyRoot);
        LocalFilesystem fs = new LocalFilesystem(emptyRoot);
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = newHook(toolkit, workspace, fs);

        fireOnce(hook);

        SkillBox box = hook.getCurrentSkillBox();
        assertNotNull(box, "Layer 3 must remain authoritative when Layer 4 is empty");
        assertTrue(
                containsSkill(box, "researcher"),
                "Workspace skill must be present via Layer 3 fallback");
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
        DynamicSkillHook hook = newHook(toolkit, workspace, fs);

        fireOnce(hook);

        assertNull(hook.getCurrentSkillBox(), "Empty load must leave skillBox unset");
    }

    // ---------------------------------------------------------------------
    //  Feature-parity with legacy SkillHook: after firing, the hook must
    //  register the `skill-build-in-tools` group (load_skill_through_path).
    // ---------------------------------------------------------------------
    @Test
    void afterFirstFire_registersSkillLoadToolGroup() throws IOException {
        Path workspace = tmp.resolve("ws");
        writeSkillMd(workspace, "reviewer", "Reviews code.");
        Toolkit toolkit = new Toolkit();
        DynamicSkillHook hook = newHook(toolkit, workspace, new LocalFilesystem(workspace));

        fireOnce(hook);

        assertNotNull(
                toolkit.getToolGroup("skill-build-in-tools"),
                "DynamicSkillHook must wire the load_skill_through_path tool group on first fire");
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /** Builds a DynamicSkillHook over the standard workspace + namespaced-fs repository pair. */
    private static DynamicSkillHook newHook(Toolkit toolkit, Path workspace, LocalFilesystem fs)
            throws IOException {
        List<AgentSkillRepository> repos = new ArrayList<>();
        Path workspaceSkills = workspace.resolve("skills");
        if (Files.isDirectory(workspaceSkills)) {
            repos.add(new FileSystemSkillRepository(workspaceSkills));
        }
        repos.add(
                new FilesystemBackedSkillRepository(
                        fs, "skills", RuntimeContext::empty, "test-ns"));
        return new DynamicSkillHook(repos, toolkit);
    }

    /**
     * Like {@link #newHook(Toolkit, Path, LocalFilesystem)} but threads a per-user RuntimeContext
     * through the namespace-aware skill repository so tests can simulate per-user views by mutating
     * a single {@link AtomicReference}.
     */
    private static DynamicSkillHook newHookWithUser(
            Toolkit toolkit, Path workspace, LocalFilesystem fs, AtomicReference<String> userRef)
            throws IOException {
        List<AgentSkillRepository> repos = new ArrayList<>();
        Path workspaceSkills = workspace.resolve("skills");
        if (Files.isDirectory(workspaceSkills)) {
            repos.add(new FileSystemSkillRepository(workspaceSkills));
        }
        repos.add(
                new FilesystemBackedSkillRepository(
                        fs,
                        "skills",
                        () ->
                                userRef.get() == null
                                        ? RuntimeContext.empty()
                                        : RuntimeContext.builder().userId(userRef.get()).build(),
                        "test-ns"));
        return new DynamicSkillHook(repos, toolkit);
    }

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

    /** Minimal in-memory repository used to simulate a marketplace source in tests. */
    private static final class InMemorySkillRepository implements AgentSkillRepository {
        private final String source;
        private final List<AgentSkill> skills;

        InMemorySkillRepository(String source, AgentSkill... skills) {
            this.source = source;
            this.skills = List.of(skills);
        }

        @Override
        public AgentSkill getSkill(String name) {
            for (AgentSkill s : skills) {
                if (s.getName().equals(name)) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            List<String> names = new ArrayList<>();
            for (AgentSkill s : skills) {
                names.add(s.getName());
            }
            return names;
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return Collections.unmodifiableList(skills);
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return getSkill(skillName) != null;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("in-memory", source, false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
            // no-op
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
