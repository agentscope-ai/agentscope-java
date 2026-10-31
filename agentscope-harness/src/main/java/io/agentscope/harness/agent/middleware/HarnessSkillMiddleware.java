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
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>Bind the catalog to the current {@link RuntimeContext} through {@link SkillRuntime}, which
 *       also (idempotently) registers {@code load_skill_through_path} on the runtime toolkit.
 *   <li>Render the {@code <available_skills>} prompt block and register it as a tail runtime
 *       material ({@code WorkspaceContextMaterials}) instead of appending it to the system
 *       prompt, keeping the system head byte-stable across steps.
 * </ol>
 *
 * <p><b>Toolkit note:</b> construction registers the load tool as ungrouped before {@link
 * io.agentscope.core.ReActAgent ReActAgent} copies the toolkit, so persisted sessions with an empty
 * active-group list still see it. Each system-prompt pass also verifies the live toolkit, so direct
 * middleware usage remains supported.
 */
public class HarnessSkillMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HarnessSkillMiddleware.class);

    private final List<AgentSkillRepository> repositories;

    /**
     * Plain repositories already warned about lacking an explicit selection — the skip is
     * by design, so the warning fires once per repository, never per call. Safe for
     * concurrent sessions: repositories do not override equals, so the set's default
     * equality is identity, and {@link ConcurrentHashMap#newKeySet()} keeps concurrent
     * first-touch warnings race-free.
     */
    private final Set<AgentSkillRepository> plainEnumerationWarned = ConcurrentHashMap.newKeySet();

    private final SkillFilter builderFilter;
    private final SkillVisibilityFilter visibilityFilter;
    private final MarketplaceStager stager;
    private final ShellPathPolicy shellPathPolicy;
    private final SkillRuntime runtime;
    private final Map<AgentSkillRepository, String> sourceNamespaces;
    private final Map<String, RepoBound> frozenSkills;
    private IsolationScope isolationScope;

    /**
     * Per-call cache scope, mirroring the identity {@link IsolationScope} already applies to
     * runtime data. Calls that share a scope share a {@code .skills-cache} subtree, and only
     * those calls can sweep it — which is what makes one call's visible-skill white-list
     * authoritative for everything the sweep can reach.
     */
    private String scopeKeyFor(RuntimeContext ctx) {
        IsolationScope scope = isolationScope != null ? isolationScope : IsolationScope.USER;
        return switch (scope) {
            case USER -> {
                String uid = ctx != null ? ctx.getUserId() : null;
                if (uid != null && !uid.isBlank()) {
                    yield uid;
                }
                // Mirrors IsolationScope.USER's documented fall back to the session identity.
                // null means "no identity to key on" and is distinct from an identity that
                // happens to be spelled like the stager's shared bucket.
                String sid = ctx != null ? ctx.getSessionId() : null;
                yield sid != null && !sid.isBlank() ? sid : null;
            }
            case SESSION -> {
                String sid = ctx != null ? ctx.getSessionId() : null;
                yield sid != null && !sid.isBlank() ? sid : null;
            }
            // The workspace is already per-agent, so these need no further separation.
            case AGENT, GLOBAL -> null;
        };
    }

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
     * @param toolkit          toolkit being assembled for the agent
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
        this(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                stager,
                shellPathPolicy,
                false);
    }

    /**
     * Creates a middleware whose merged repository view is captured once during construction.
     * Filters remain per-call, and lazy resource access remains bound to the current context.
     */
    public static HarnessSkillMiddleware frozen(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy) {
        return new HarnessSkillMiddleware(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                stager,
                shellPathPolicy,
                true);
    }

    private HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy,
            boolean freezeRepositories) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
        this.visibilityFilter = visibilityFilter;
        this.stager = stager;
        this.shellPathPolicy =
                shellPathPolicy != null ? shellPathPolicy : ShellPathPolicy.noShell();
        this.isolationScope = IsolationScope.USER;
        this.runtime = new SkillRuntime();
        // Pre-resolve source namespaces once at build time. The compose order is fixed for
        // the lifetime of the middleware, so this is safe and avoids repeated work per call.
        this.sourceNamespaces = MarketplaceStager.resolveSourceNamespaces(this.repositories);
        this.frozenSkills =
                freezeRepositories
                        ? Collections.unmodifiableMap(
                                new LinkedHashMap<>(mergeRepositories(RuntimeContext.empty())))
                        : null;
        this.runtime.prepareToolkit(toolkit);
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /** Visible for tests / introspection. */
    public SkillRuntime runtime() {
        return runtime;
    }

    /**
     * Overrides the isolation dimension used to separate {@code .skills-cache} subtrees.
     * Defaults to {@link IsolationScope#USER}, matching the default for runtime data.
     */
    public HarnessSkillMiddleware isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    /** Whether repository enumeration is frozen to the construction-time snapshot. */
    public boolean isFrozen() {
        return frozenSkills != null;
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
        Map<String, RepoBound> merged = skillsForCall(ctx);
        if (merged.isEmpty()) {
            return;
        }
        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        List<RepoBound> enabled = applySkillFilter(visible, effectiveFilter(ctx));
        if (!enabled.isEmpty()) {
            stager.stage(enabled, sourceNamespaces, scopeKeyFor(ctx));
        }
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }

        Toolkit agentToolkit = agent != null ? agent.getToolkit() : null;

        Map<String, RepoBound> merged = skillsForCall(ctx);
        if (merged.isEmpty()) {
            runtime.install(SkillCatalog.empty(), ctx, agentToolkit);
            registerSkillsMaterial(ctx, "");
            return Mono.just(currentPrompt);
        }

        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        List<RepoBound> enabled = applySkillFilter(visible, effectiveFilter(ctx));
        if (enabled.isEmpty()) {
            runtime.install(SkillCatalog.empty(), ctx, agentToolkit);
            registerSkillsMaterial(ctx, "");
            return Mono.just(currentPrompt);
        }

        Map<String, StageResult> staged =
                stager != null
                        ? stager.stage(enabled, sourceNamespaces, scopeKeyFor(ctx))
                        : Map.of();

        List<HarnessSkillEntry> entries = new ArrayList<>(enabled.size());
        for (RepoBound bound : enabled) {
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
            } else if (bound.skill().getOriginDir().isPresent()) {
                lazy = originResources(bound.skill().getOriginDir().get());
            }
            StageResult stage = staged.getOrDefault(bound.skill().getName(), StageResult.NONE);
            String filesRoot = shellPathPolicy.resolve(bound.skill().getName(), stage);
            entries.add(new HarnessSkillEntry(bound.skill(), lazy, filesRoot));
        }

        SkillCatalog catalog = SkillCatalog.of(entries);
        runtime.install(catalog, ctx, agentToolkit);

        registerSkillsMaterial(ctx, runtime.renderPrompt(catalog, SkillFilter.all()));
        return Mono.just(currentPrompt);
    }

    /**
     * Ships the rendered {@code <available_skills>} block as a tail runtime material instead of
     * appending it to the system prompt: the catalog changes while a session runs (skills are
     * installed or removed), and head bytes must stay stable for prefix caching. Registering a
     * blank item keeps the sourceId deterministic when nothing is visible. The catalog is
     * required so skill discovery remains available when conversation history is compacted.
     */
    private static void registerSkillsMaterial(RuntimeContext ctx, String block) {
        WorkspaceContextMaterials.register(
                ctx, List.of(ContextItem.runtimeState("skills", "harness:skills", true, block)));
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------

    private static SkillResources originResources(Path originDir) {
        return new SkillResources() {
            @Override
            public Optional<String> read(String relativePath) {
                return SkillFileSystemHelper.readResource(originDir, relativePath);
            }

            @Override
            public Optional<byte[]> readBinary(String relativePath) {
                return SkillFileSystemHelper.readResourceBinary(originDir, relativePath);
            }

            @Override
            public List<String> list() {
                return SkillFileSystemHelper.listResourcePaths(originDir);
            }
        };
    }

    private Map<String, RepoBound> skillsForCall(RuntimeContext ctx) {
        return frozenSkills != null ? frozenSkills : mergeRepositories(ctx);
    }

    private SkillFilter effectiveFilter(RuntimeContext ctx) {
        return builderFilter.overlay(ctx != null ? ctx.get(SkillFilter.class) : null);
    }

    private List<RepoBound> applySkillFilter(
            java.util.Collection<RepoBound> input, SkillFilter filter) {
        if (input.isEmpty()) {
            return List.of();
        }
        SkillFilter effective = filter != null ? filter : SkillFilter.all();
        List<RepoBound> out = new ArrayList<>(input.size());
        for (RepoBound bound : input) {
            if (effective.isAllowed(bound.skill().getName())) {
                out.add(bound);
            }
        }
        return out;
    }

    /**
     * Merge skills from every repository, in compose order. Later entries with the same
     * {@code AgentSkill.name} win. Also remembers the source repository per winning skill so
     * subsequent steps (lazy resources, marketplace stage) can act on it.
     *
     * <p>A context-scoped repository contributes its request-bound catalog (the first-class
     * context variant) — that read is the freshness mechanism for the workspace tier,
     * bounded by the request's scope. A plain repository is never bulk-enumerated: it
     * contributes only its explicitly selected names — the effective filter's declared
     * names it still admits — pulled one by one through the serving primitive; without an
     * explicit selection it is skipped with one warning per repository.
     */
    private LinkedHashMap<String, RepoBound> mergeRepositories(RuntimeContext ctx) {
        LinkedHashMap<String, RepoBound> merged = new LinkedHashMap<>();
        List<String> explicitSelection = explicitSelectionOf(effectiveFilter(ctx));
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            if (repo instanceof RuntimeContextSkillRepository contextRepository) {
                try {
                    skills = contextRepository.getAllSkills(ctx);
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
            } else {
                skills = pullExplicitSelection(repo, explicitSelection);
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

    /**
     * The serving rule for repositories this middleware must not enumerate (same rule as
     * the core {@code DynamicSkillMiddleware}): the selection is the names the filter
     * declares that the filter itself still admits. The interpretation lives here, at the
     * serving scenario — the filter reports raw declared names and stays a predicate.
     * Sorted for stable prompt material across calls.
     */
    private static List<String> explicitSelectionOf(SkillFilter filter) {
        if (filter.declaredNames().isEmpty()) {
            return List.of();
        }
        List<String> selection = new ArrayList<>(filter.declaredNames().size());
        for (String name : filter.declaredNames()) {
            if (filter.isAllowed(name)) {
                selection.add(name);
            }
        }
        Collections.sort(selection);
        return selection;
    }

    /** Pulls the explicit selection from a plain repository name by name through the
     * serving primitive; any per-name failure is skipped with a warning. */
    private List<AgentSkill> pullExplicitSelection(
            AgentSkillRepository repo, List<String> explicitSelection) {
        if (explicitSelection.isEmpty()) {
            warnPlainEnumerationSkipped(repo);
            return List.of();
        }
        List<AgentSkill> skills = new ArrayList<>(explicitSelection.size());
        for (String name : explicitSelection) {
            try {
                AgentSkill skill = repo.getSkill(name, null);
                if (skill != null && skill.getName() != null) {
                    skills.add(skill);
                }
            } catch (Exception e) {
                log.warn(
                        "Skill pull '{}' on {} failed: {}",
                        name,
                        repo.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        return skills;
    }

    /** Warns once per repository that a plain repository without explicit selection is
     * skipped — enumeration is not part of the serving contract, so this is a usage
     * hint, not an error. */
    private void warnPlainEnumerationSkipped(AgentSkillRepository repo) {
        if (plainEnumerationWarned.add(repo)) {
            log.warn(
                    "Skill repository {} is not context-scoped and the agent declares no"
                            + " explicit skill selection; skipping it for serving — bind the"
                            + " names to serve via SkillFilter.only(...), or serve dynamic"
                            + " discovery from a RuntimeContextSkillRepository",
                    repo.getClass().getSimpleName());
        }
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
