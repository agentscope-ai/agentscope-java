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
import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.hook.HookEvent;
import io.agentscope.core.legacy.hook.PreCallEvent;
import io.agentscope.core.legacy.hook.RuntimeContextAware;
import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.core.legacy.skill.SkillBox;
import io.agentscope.core.legacy.skill.SkillHook;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dynamically composes skills from an ordered list of {@link AgentSkillRepository repositories}
 * on every {@link PreCallEvent}, replacing the static {@link SkillHook} with a per-call view that
 * supports per-user skill isolation and one-click marketplace integration.
 *
 * <p>The repository list is iterated low-priority first; when two repositories provide a skill
 * with the same {@link AgentSkill#getName()}, the later (higher-priority) entry wins. The default
 * composition assembled by {@code HarnessAgent.Builder} is:
 *
 * <ol>
 *   <li>Project-global directory (if configured)</li>
 *   <li>Marketplace repositories (in registration order)</li>
 *   <li>Workspace agent-shared directory ({@code workspace/skills/})</li>
 *   <li>Per-user namespaced filesystem ({@code <userId>/skills/})</li>
 * </ol>
 *
 * <p>Behaviour each {@link PreCallEvent}:
 *
 * <ol>
 *   <li>Walk the repository list and collect skills into a {@link LinkedHashMap} keyed by name —
 *       later repositories override earlier ones.</li>
 *   <li>Build a fresh {@link SkillBox}, replay {@link SkillBox#bindToolkit},
 *       {@link SkillBox#registerSkillLoadTool}, and {@link SkillBox#uploadSkillFiles} (when
 *       {@link SkillBox#isAutoUploadSkill()} is set), then append {@code skillBox.getSkillPrompt()}
 *       to the system message.</li>
 * </ol>
 *
 * <p>Rebuilding on every call is intentional: per-user namespaced repositories return different
 * content under the same skill name as the {@link RuntimeContext} switches users, so caching by
 * skill id alone would mask those swaps. {@code bindToolkit} / {@code registerSkillLoadTool} are
 * idempotent on a fresh {@link SkillBox}, so the rebuild stays cheap.
 *
 * <p>Priority matches {@link SkillHook#SKILL_HOOK_PRIORITY} (85).
 */
public class DynamicSkillHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillHook.class);

    private final List<AgentSkillRepository> repositories;
    private final Toolkit toolkit;

    private volatile SkillBox currentSkillBox;
    private volatile RuntimeContext runtimeContext;

    /**
     * @param repositories ordered repositories; later entries override earlier ones on name
     *     collisions. May be empty (the hook becomes a no-op).
     * @param toolkit toolkit on which loaded skill tool groups are registered
     */
    public DynamicSkillHook(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
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
        if (repositories.isEmpty()) {
            currentSkillBox = null;
            return;
        }

        Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            try {
                skills = repo.getAllSkills();
            } catch (Exception e) {
                log.warn(
                        "Skill repository {} failed to load: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
                continue;
            }
            if (skills == null) {
                continue;
            }
            for (AgentSkill skill : skills) {
                if (skill == null || skill.getName() == null) {
                    continue;
                }
                skillsByName.put(skill.getName(), skill);
            }
        }

        if (skillsByName.isEmpty()) {
            currentSkillBox = null;
            return;
        }

        SkillBox box = new SkillBox(toolkit);
        for (AgentSkill skill : skillsByName.values()) {
            box.registerSkill(skill);
        }
        if (toolkit != null) {
            try {
                box.bindToolkit(toolkit);
                box.registerSkillLoadTool();
            } catch (Exception e) {
                log.warn("Failed to bind skill toolkit hooks: {}", e.getMessage());
            }
        }
        if (box.isAutoUploadSkill()) {
            try {
                box.uploadSkillFiles();
            } catch (Exception e) {
                log.warn("Failed to upload skill files: {}", e.getMessage());
            }
        }
        currentSkillBox = box;
    }
}
