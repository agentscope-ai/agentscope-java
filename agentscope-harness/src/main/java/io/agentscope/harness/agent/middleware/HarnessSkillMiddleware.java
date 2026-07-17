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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.SkillRuntime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Harness-native skill middleware. Replaces the legacy {@code DynamicSkillMiddleware} subclass
 * with a standalone implementation that owns a {@link SkillRuntime}.
 *
 * <p>Per {@code onSystemPrompt} pass:
 *
 * <ol>
 *   <li>Resolve the current {@link RuntimeContext} from the agent.
 *   <li>Iterate repositories low-to-high priority and merge by {@code AgentSkill.name} (later
 *       wins).
 *   <li>Apply the optional {@link SkillVisibilityFilter}.
 *   <li>Stage Layer-1/Layer-2 marketplace skills' resources into
 *       {@code <wsRoot>/.skills-cache/<source-ns>/<skill>/} via {@link MarketplaceStager}.
 *   <li>Build a {@link SkillCatalog} of {@link HarnessSkillEntry} (with lazy resources and
 *       resolved {@code filesRoot}).
 *   <li>Install the catalog into the {@link SkillRuntime}, which (idempotently) registers the
 *       {@code load_skill_through_path} tool on the agent's runtime toolkit.
 *   <li>Render the {@code <available_skills>} prompt block and append it to the current system
 *       prompt.
 * </ol>
 *
 * <p><b>Toolkit note:</b> the {@code toolkit} constructor parameter is accepted for API
 * compatibility but is <em>not</em> used for runtime tool registration. Instead,
 * {@link #onSystemPrompt} always installs into {@code agent.getToolkit()} so the tool is
 * registered on the toolkit that the running agent actually uses for reasoning. This matters
 * because {@link io.agentscope.harness.agent.HarnessAgent HarnessAgent} makes a deep copy of
 * the toolkit when building the inner {@link io.agentscope.core.ReActAgent ReActAgent}, so the
 * constructor-injected intermediate toolkit is not the same instance as the agent's live toolkit.
 */
@SuppressWarnings("deprecation")
public class HarnessSkillMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HarnessSkillMiddleware.class);

    private final List<AgentSkillRepository> repositories;
    private final Toolkit toolkit;
    private final SkillFilter builderFilter;
    private final SkillVisibilityFilter visibilityFilter;
    private final MarketplaceStager stager;
    private final ShellPathPolicy shellPathPolicy;
    private final SkillRuntime runtime;
    private final Map<AgentSkillRepository, String> sourceNamespaces;

    public HarnessSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this(repositories, toolkit, builderFilter, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter) {
        this(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                null,
                ShellPathPolicy.noShell());
    }

    /**
     * Full constructor.
     *
     * @param repositories     compose-ordered list (low-to-high priority)
     * @param toolkit          accepted for API compatibility; not used for runtime registration
     *                         (see class-level note on toolkit copy semantics)
     * @param builderFilter    skill filter passed at agent build time (may be {@code null})
     * @param visibilityFilter optional per-request filter (canary/allow-list)
     * @param stager           marketplace stager; {@code null} disables staging entirely
     * @param shellPathPolicy  policy for resolving {@code <files-root>} per shell mode; never
     *                         {@code null} — pass {@link ShellPathPolicy#noShell()} when no
     *                         shell tool is registered
     */
    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.toolkit = toolkit;
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
        this.visibilityFilter = visibilityFilter;
        this.stager = stager;
        this.shellPathPolicy =
                shellPathPolicy != null ? shellPathPolicy : ShellPathPolicy.noShell();
        this.runtime = new SkillRuntime();
        // Pre-resolve source namespaces once at build time. The compose order is fixed for
        // the lifetime of the middleware, so this is safe and avoids repeated work per call.
        this.sourceNamespaces = MarketplaceStager.resolveSourceNamespaces(this.repositories);
    }

    /** Visible for tests / introspection. */
    public SkillRuntime runtime() {
        return runtime;
    }

    /**
     * Pre-stages marketplace skill resources to {@code .skills-cache/} on the host workspace.
     * Intended to be called <em>before</em> sandbox start so that workspace projection picks up
     * the staged content in the same call. Safe to call multiple times — staging is idempotent
     * (content-hash guarded).
     *
     * @param ctx the per-call runtime context
     */
    public void prestageMarketplaceSkills(RuntimeContext ctx) {
        if (stager == null) {
            return;
        }
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }
        Map<String, RepoBound> merged = mergeRepositories(ctx);
        if (merged.isEmpty()) {
            return;
        }
        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        if (!visible.isEmpty()) {
            stager.stage(visible, sourceNamespaces);
        }
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }

        Toolkit agentToolkit = agent != null ? agent.getToolkit() : null;

        Map<String, RepoBound> merged = mergeRepositories(ctx);
        if (merged.isEmpty()) {
            runtime.install(SkillCatalog.empty(), agentToolkit);
            return Mono.just(currentPrompt);
        }

        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        if (visible.isEmpty()) {
            runtime.install(SkillCatalog.empty(), agentToolkit);
            return Mono.just(currentPrompt);
        }

        Map<String, StageResult> staged =
                stager != null ? stager.stage(visible, sourceNamespaces) : Map.of();

        List<HarnessSkillEntry> entries = new ArrayList<>(visible.size());
        for (RepoBound bound : visible) {
            SkillResources lazy = null;
            if (bound.repo() instanceof LazyResourceCapable lrc) {
                try {
                    lazy = lrc.resourcesFor(bound.skill().getName(), ctx);
                } catch (Exception e) {
                    log.debug(
                            "resourcesFor({}) failed; continuing without lazy: {}",
                            bound.skill().getName(),
                            e.getMessage());
                }
            }
            StageResult stage = staged.getOrDefault(bound.skill().getName(), StageResult.NONE);
            String filesRoot = shellPathPolicy.resolve(bound.skill().getName(), stage);
            entries.add(new HarnessSkillEntry(bound.skill(), lazy, filesRoot));
        }

        SkillCatalog catalog = SkillCatalog.of(entries);
        runtime.install(catalog, agentToolkit);

        SkillFilter effective =
                builderFilter.overlay(ctx != null ? ctx.get(SkillFilter.class) : null);
        String append = runtime.renderPrompt(catalog, effective);
        if (append == null || append.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(base + separator + append);
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------

    /**
     * Merge skills from every repository, in compose order. Later entries with the same
     * {@code AgentSkill.name} win. Also remembers the source repository per winning skill so
     * subsequent steps (lazy resources, marketplace stage) can act on it.
     */
    private LinkedHashMap<String, RepoBound> mergeRepositories(RuntimeContext ctx) {
        LinkedHashMap<String, RepoBound> merged = new LinkedHashMap<>();
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
                merged.put(skill.getName(), new RepoBound(skill, repo));
            }
        }
        return merged;
    }

    private List<RepoBound> applyVisibility(
            java.util.Collection<RepoBound> input, RuntimeContext ctx) {
        if (visibilityFilter == null || input.isEmpty()) {
            return new ArrayList<>(input);
        }
        Map<AgentSkill, RepoBound> bySkill = new IdentityHashMap<>();
        List<AgentSkill> raw = new ArrayList<>(input.size());
        for (RepoBound rb : input) {
            bySkill.put(rb.skill(), rb);
            raw.add(rb.skill());
        }
        List<AgentSkill> filtered;
        try {
            filtered = visibilityFilter.filter(raw, ctx);
        } catch (Exception e) {
            log.warn(
                    "SkillVisibilityFilter {} failed; treating as pass-through: {}",
                    visibilityFilter.getClass().getSimpleName(),
                    e.getMessage());
            return new ArrayList<>(input);
        }
        if (filtered == null) {
            return new ArrayList<>(input);
        }
        List<RepoBound> out = new ArrayList<>(filtered.size());
        for (AgentSkill s : filtered) {
            RepoBound rb = bySkill.get(s);
            if (rb != null) {
                out.add(rb);
            }
        }
        return out;
    }
}
