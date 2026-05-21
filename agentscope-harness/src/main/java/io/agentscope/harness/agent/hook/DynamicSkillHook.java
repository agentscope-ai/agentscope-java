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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dynamically loads skills from the workspace filesystem on each {@link PreCallEvent}, replacing
 * the static {@link SkillHook} to support per-user skill isolation.
 *
 * <p><strong>Two-layer load</strong> (mirrors the override semantics of {@code
 * WorkspaceManager}):
 *
 * <ol>
 *   <li><em>Layer 1 (override)</em> — {@code filesystem.glob("SKILL.md", "skills")} +
 *       per-file {@code filesystem.read}. The backend's {@code NamespaceFactory} is applied
 *       transparently so each user sees their own slice of the store.
 *   <li><em>Layer 2 (base)</em> — {@link FileSystemSkillRepository} reads the local workspace
 *       {@code skills/} directory directly. This preserves the original behaviour of
 *       {@code LocalFilesystemWithShell} / sandbox modes where skill definitions live on the host
 *       filesystem rather than in a per-user namespace.
 *   <li><em>Merge</em> — same-name skills from Layer 1 override Layer 2.
 * </ol>
 *
 * <p>Priority matches {@link SkillHook#SKILL_HOOK_PRIORITY} (85).
 */
public class DynamicSkillHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillHook.class);

    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";

    private final AbstractFilesystem filesystem;
    private final Path workspace;
    private final Toolkit toolkit;
    private volatile SkillBox currentSkillBox;
    private volatile RuntimeContext runtimeContext;

    /**
     * Builds a dynamic skill hook.
     *
     * @param filesystem workspace filesystem used for namespaced reads of {@code skills/}; may be
     *     {@code null}, in which case only Layer 2 (local disk) is consulted
     * @param workspace local workspace root, used as the Layer 2 base; may be {@code null}, in
     *     which case Layer 2 is skipped
     * @param toolkit toolkit on which loaded skill tools are registered
     */
    public DynamicSkillHook(AbstractFilesystem filesystem, Path workspace, Toolkit toolkit) {
        this.filesystem = filesystem;
        this.workspace = workspace;
        this.toolkit = toolkit;
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    @Override
    public int priority() {
        return SkillHook.SKILL_HOOK_PRIORITY;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            reloadSkills();
            if (currentSkillBox != null) {
                String prompt = currentSkillBox.getSkillPrompt();
                if (prompt != null && !prompt.isEmpty()) {
                    preCallEvent.appendSystemContent(prompt);
                }
            }
        }
        return Mono.just(event);
    }

    /** Returns the current SkillBox, or {@code null} if no skills are loaded. */
    public SkillBox getCurrentSkillBox() {
        return currentSkillBox;
    }

    private void reloadSkills() {
        Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();

        // ---- Layer 2 (base): local workspace scan ----
        for (AgentSkill skill : loadSkillsFromLocalDisk()) {
            skillsByName.put(skill.getName(), skill);
        }

        // ---- Layer 1 (override): filesystem with namespace ----
        for (AgentSkill skill : loadSkillsViaFilesystem()) {
            skillsByName.put(skill.getName(), skill);
        }

        if (skillsByName.isEmpty()) {
            currentSkillBox = null;
            return;
        }
        SkillBox box = new SkillBox(toolkit);
        for (AgentSkill skill : skillsByName.values()) {
            box.registerSkill(skill);
        }
        currentSkillBox = box;
    }

    private List<AgentSkill> loadSkillsFromLocalDisk() {
        if (workspace == null) {
            return Collections.emptyList();
        }
        Path skillsDir = workspace.resolve(SKILLS_DIR);
        if (!Files.isDirectory(skillsDir)) {
            return Collections.emptyList();
        }
        try {
            List<AgentSkill> skills = new FileSystemSkillRepository(skillsDir).getAllSkills();
            return skills != null ? skills : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to load skills from local disk {}: {}", skillsDir, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<AgentSkill> loadSkillsViaFilesystem() {
        if (filesystem == null) {
            return Collections.emptyList();
        }
        RuntimeContext ctx = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        GlobResult glob;
        try {
            glob = filesystem.glob(ctx, SKILL_FILE, SKILLS_DIR);
        } catch (Exception e) {
            log.debug("Filesystem glob for skills failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentSkill> skills = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                AgentSkill skill = SkillUtil.createFrom(rr.fileData().content(), null, "workspace");
                skills.add(skill);
            } catch (Exception e) {
                log.warn("Failed to load skill from '{}': {}", path, e.getMessage());
            }
        }
        return skills;
    }
}
