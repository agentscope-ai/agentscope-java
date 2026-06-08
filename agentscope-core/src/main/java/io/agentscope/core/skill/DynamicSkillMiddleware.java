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
package io.agentscope.core.skill;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dynamically composes skills from an ordered list of {@link AgentSkillRepository repositories}
 * on every {@code call()} via {@link #onSystemPrompt(Agent, String)}, replacing the static
 * skill prompt with a per-call view that supports per-user skill isolation and one-click
 * marketplace integration.
 *
 * <p>The repository list is iterated low-priority first; when two repositories provide a skill
 * with the same {@link AgentSkill#getName()}, the later (higher-priority) entry wins.
 *
 * <p>Rebuilding on every call is intentional: per-user namespaced repositories may return
 * different content under the same skill name as the {@link RuntimeContext} switches users, so
 * caching by skill id alone would mask those swaps. {@code bindToolkit} /
 * {@code registerSkillLoadTool} are idempotent on a fresh {@link SkillBox}, so the rebuild stays
 * cheap.
 *
 * <p>Subclasses can plug runtime visibility logic (canary lists, environment gates, etc.) by
 * overriding {@link #filterVisible(List, RuntimeContext)} — the default implementation returns
 * the input list unchanged.
 */
@SuppressWarnings("deprecation")
public class DynamicSkillMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillMiddleware.class);

    private final List<AgentSkillRepository> repositories;
    private final Toolkit toolkit;
    private final SkillFilter builderFilter;

    private volatile SkillBox currentSkillBox;

    public DynamicSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null);
    }

    public DynamicSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.toolkit = toolkit;
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
    }

    /**
     * Returns the most recently materialised {@link SkillBox}, or {@code null} when no skills
     * are visible yet (e.g. before the first {@code call()}).
     */
    public SkillBox getCurrentSkillBox() {
        return currentSkillBox;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        RuntimeContext rc = agent != null ? agent.getRuntimeContext() : null;
        if (rc == null) {
            rc = RuntimeContext.empty();
        }
        reloadSkills(rc);
        if (currentSkillBox == null) {
            return Mono.just(currentPrompt);
        }
        SkillFilter effectiveFilter = resolveFilter(rc);
        String prompt = currentSkillBox.getSkillPrompt(effectiveFilter);
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(base + separator + prompt);
    }

    /**
     * Hook for subclasses to drop skills that should not be visible in the current request
     * (canary releases, allow-lists, environment gates, …). Called once per {@code call()},
     * after merging across repositories and before the {@link SkillBox} is rebuilt.
     *
     * <p>Default implementation returns {@code raw} unchanged.
     *
     * @param raw the merged, deduplicated skill list (low-to-high repository priority order)
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the visible subset (must not be {@code null}; an empty list short-circuits the
     *     prompt update for this call)
     */
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return raw;
    }

    private SkillFilter resolveFilter(RuntimeContext rc) {
        SkillFilter runtimeOverlay = rc != null ? rc.get(SkillFilter.class) : null;
        return builderFilter.overlay(runtimeOverlay);
    }

    private void reloadSkills(RuntimeContext ctx) {
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
        // Apply subclass visibility hook before the SkillBox is rebuilt so the prompt only
        // sees skills the current ctx is allowed to see.
        List<AgentSkill> visible;
        try {
            List<AgentSkill> filtered = filterVisible(new ArrayList<>(skillsByName.values()), ctx);
            visible = filtered != null ? filtered : new ArrayList<>(skillsByName.values());
        } catch (Exception e) {
            log.warn(
                    "filterVisible() in {} failed; treating as pass-through: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
            visible = new ArrayList<>(skillsByName.values());
        }
        if (visible.isEmpty()) {
            currentSkillBox = null;
            return;
        }
        SkillBox box = new SkillBox(toolkit);
        for (AgentSkill skill : visible) {
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
