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
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.skill.mirror.SkillAnchorService;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dynamically composes skills from an ordered list of {@link AgentSkillRepository repositories}
 * on every {@code call()} via {@link #onSystemPrompt(Agent, RuntimeContext, String)}, replacing the static
 * skill prompt with a per-call view that supports per-user skill isolation and one-click
 * marketplace integration.
 *
 * <p>The repository list is iterated low-priority first; when two repositories provide a skill
 * with the same {@link AgentSkill#getName()}, the later (higher-priority) entry wins.
 *
 * <p>Repositories own content: every pull through {@code getSkill} returns a
 * descriptor-form summary — no body in memory, already materialized on disk with its
 * {@code originDir} — and the repository decides whether it changed. This middleware owns
 * binding: which version is served under the agent's {@link SkillVersionPolicy} — the
 * per-session version table, the stamp comparison behind LATEST freshness and PIN
 * anchoring, and the {@link SkillBox} view. It materializes no content itself, and no
 * skill file body is ever held in memory.
 *
 * <p>Rebuilding the visible view — and its {@link SkillBox} — on every call is intentional:
 * per-user namespaced repositories may return different content under the same skill name as
 * the {@link RuntimeContext} switches users, so caching by skill id alone would mask those
 * swaps. The rebuild stays cheap by construction: repositories own content and its
 * materialization (version-keyed, immutable tokens), so a fully materialized view (every
 * visible skill carries an {@code originDir}) builds a box with no file I/O and no directory
 * allocation. Only a view still containing unmaterialized skills runs {@code
 * uploadSkillFiles}, which allocates one fresh per-box directory lazily inside the box. No
 * tools are (re)registered on the shared toolkit.
 *
 * <p>Subclasses can plug runtime visibility logic (canary lists, environment gates, etc.) by
 * overriding {@link #filterVisible(List, RuntimeContext)} — the default implementation returns
 * the input list unchanged.
 *
 * <p>Contract:
 * <ul>
 *   <li>Construction performs no registration; the shared {@code load_skill_through_path}
 *       tool is registered on the invoking agent's live toolkit at the first prompt pass —
 *       before tool schemas are enumerated, so it is visible to the model within the same
 *       call. {@link #registerSkillTools(Toolkit)} remains available for explicit manual
 *       wiring.</li>
 *   <li>Per-pass runtime objects resolve from the invoking agent: its live toolkit
 *       ({@link Agent#getToolkit()}) flows into box construction and its filter baseline
 *       drives filtering. Instances hold no per-agent runtime references.</li>
 *   <li>Session establishment resolves each repository's candidate names once. A
 *       context-scoped repository ({@link RuntimeContextSkillRepository}) enumerates its
 *       request-bound catalog through the first-class context variants; a plain
 *       repository is never enumerated — its candidates are the baseline {@link SkillFilter}'s
 *       explicit names ({@link SkillFilter#only}), and a plain repository with no explicit
 *       selection is skipped with one warning. Candidates are pulled at a null version
 *       (latest); pulls carrying version stamps make their repository version-capable
 *       (session-bound entries, TTL-refreshed by stamp comparison), while unstamped
 *       repositories are re-served on every call — context-scoped ones by re-reading
 *       their request-bound catalog, plain ones by re-pulling the explicit selection
 *       through the serving primitive. A per-call overlay pass pulls names the effective
 *       filter allows on demand, so a runtime overlay can serve a name the baseline
 *       denied. The first pull of a skill may materialize it inside the repository; later
 *       pulls of the same version are local reads.</li>
 *   <li>Each call's box is freshly built for that call alone — no box or directory is shared
 *       across calls. Every build that must materialize unmaterialized skills owns its
 *       directory (allocated lazily inside {@link SkillBox}, cleaned at JVM exit), so a later
 *       call can never observe or rewrite files an earlier one is still executing against.</li>
 *   <li>The framework creates a fresh {@link RuntimeContext} per call; every pass overwrites
 *       (or clears) the {@link SkillBox} binding.</li>
 *   <li>The effective filter composes the per-call context overlay on the invoking agent's
 *       baseline ({@link Agent#getSkillFilter()}), defaulting to {@link SkillFilter#all()}
 *       when the agent exposes none.</li>
 * </ul>
 */
public class DynamicSkillMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillMiddleware.class);

    /** Upper bound on retained per-session version-route state; oldest evicted (see
     * {@link #sessions}). */
    private static final int MAX_CACHED_SESSIONS = 256;

    /**
     * How often a session re-checks versioned repositories for newer versions in LATEST
     * mode; an internal tuning constant.
     */
    private static final long FRESHNESS_TTL_MILLIS = 60_000;

    /** Freshness TTL, overridable in tests to exercise the refresh path without waiting. */
    static volatile long freshnessTtlMillis = FRESHNESS_TTL_MILLIS;

    /**
     * Session-state map keyed by {@code agent|user|session}, insertion-ordered and bounded:
     * the oldest session state is evicted past {@link #MAX_CACHED_SESSIONS}. Eviction is
     * correctness-safe — a re-arriving session re-establishes from scratch (repository
     * re-read plus anchor lookup, where first-writer-wins restores the pinned version), so
     * an evicted entry only costs one re-establishment read, never a wrong version.
     */
    private final Map<String, MirrorSession> sessions = new LinkedHashMap<>();

    private final List<AgentSkillRepository> repositories;

    /**
     * Plain repositories already warned about lacking an explicit selection — the
     * enumeration skip is by design, so the warning fires once per repository, never per
     * call. Identity-based: repositories do not override equals.
     */
    private final Set<AgentSkillRepository> plainEnumerationWarned =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final boolean codeExecutionEnabled;

    /**
     * One visible skill's version binding: the owning repository and the resident skill.
     * The bound version is always the resident skill's own stamp ({@link #version()}); for
     * a versioned entry that skill is the repository-served summary: stamped, no body,
     * empty resource map, {@code originDir} pointing at the materialized version
     * directory.
     */
    private static final class SkillEntry {
        final AgentSkillRepository repository;
        AgentSkill skill;
        boolean pinned;

        SkillEntry(AgentSkillRepository repository, AgentSkill skill) {
            this.repository = repository;
            this.skill = skill;
        }

        /**
         * The bound version: the resident skill's version stamp.
         *
         * @return the resident skill's version reference, or {@code null} when unstamped
         */
        String version() {
            return skill.getVersion();
        }
    }

    /**
     * Version-route state for one session: the addressable entries (name → binding) and the
     * repositories classified as version-capable. Repositories without version capability
     * are re-read per call instead, outside this state. All mutable state is guarded by
     * the per-session monitor taken in {@link #reloadSkills(Agent, RuntimeContext)}.
     */
    private static final class MirrorSession {
        final Map<String, SkillEntry> entriesByName = new LinkedHashMap<>();
        final Set<AgentSkillRepository> versionedRepositories = ConcurrentHashMap.newKeySet();

        /** Establishment-time reads from non-versioned repositories, consumed once by the
         * first merge pass. */
        final Map<AgentSkillRepository, List<AgentSkill>> establishmentReads =
                new LinkedHashMap<>();

        /** Names enumerated once at establishment for context-scoped repositories — the
         * session's discovery catalog. Names the baseline filter denied were not pulled
         * then; the per-call overlay pass pulls them on demand from this catalog, without
         * re-enumerating the repository while the session lives. Plain repositories keep
         * no catalog: their candidates are always the filter's explicit selection. */
        final Map<AgentSkillRepository, List<String>> nameCatalogs = new LinkedHashMap<>();

        /** Names per repository that were pulled but cannot be bound — unstamped loads from a
         * repository classified version-capable. Never retried, matching establishment
         * semantics. */
        final Map<AgentSkillRepository, Set<String>> unbindableNames = new LinkedHashMap<>();

        volatile boolean established;
        volatile long lastFreshnessMillis;
    }

    /**
     * Convenience constructor delegating to the full constructor with code execution disabled.
     *
     * @param repositories compose-ordered list (low-to-high priority); merged by skill name
     */
    public DynamicSkillMiddleware(List<AgentSkillRepository> repositories) {
        this(repositories, false);
    }

    /**
     * Full constructor. Performs pure field initialisation; the shared load tool is registered
     * at wiring time via {@link #registerSkillTools(Toolkit)} (called by the agent builder), or
     * lazily before the first prompt pass when the instance is used outside agent assembly.
     * Per-pass runtime objects — the box-construction toolkit and the filter baseline — are
     * resolved from the invoking agent; any directory a box build needs is allocated lazily
     * inside {@link SkillBox}.
     *
     * @param repositories         compose-ordered list (low-to-high priority); merged by skill name
     * @param codeExecutionEnabled when {@code true}, toggles the prompt provider's code-execution
     *                             block. Set this only when the agent has a shell-like tool wired
     *                             in; otherwise the prompt asks the model to do things it cannot.
     */
    public DynamicSkillMiddleware(
            List<AgentSkillRepository> repositories, boolean codeExecutionEnabled) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.codeExecutionEnabled = codeExecutionEnabled;
    }

    /**
     * Registers the shared {@code load_skill_through_path} tool on the given toolkit. Invoked
     * per prompt pass with the invoking agent's live toolkit (before tool schemas are
     * enumerated, so the tool is visible within the same call); also callable directly for
     * explicit manual wiring. No-op for a {@code null} toolkit or when a same-named tool
     * already exists (never overwritten); because the guard inspects the toolkit, a removed
     * tool is re-registered on the next call.
     *
     * @param toolkit the toolkit to register the shared load tool on; may be {@code null}
     */
    public void registerSkillTools(Toolkit toolkit) {
        if (toolkit == null || SkillToolFactory.isRuntimeLoadToolRegistered(toolkit)) {
            return;
        }
        // The shared load tool resolves the current per-call SkillBox from the RuntimeContext,
        // so the shared toolkit is never mutated per call.
        SkillToolFactory.registerRuntimeLoadTool(toolkit);
    }

    /**
     * Narrow declaration: subclasses overriding more hooks must extend this set.
     *
     * @return the extension points this middleware reacts to
     */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /**
     * Reloads the visible skill view and appends its rendered prompt to {@code currentPrompt}.
     *
     * <p>The reload performs blocking I/O (repository reads, occasional file materialization),
     * so it runs on {@link Schedulers#boundedElastic()} rather than the subscribing thread,
     * keeping reactive event-loop threads non-blocking. The reload happens on every call; a
     * fully materialized view (every visible skill carries an {@code originDir}) rebuilds
     * purely in memory, and only repositories that changed do I/O.
     *
     * <p>Agent contract: the framework invokes this hook with a non-null {@code agent}; direct
     * callers must supply one or accept the degraded behaviour of a null one (tolerated, not
     * contractual).
     *
     * @param agent the agent whose toolkit, skill-filter baseline and version policy drive this
     *     pass; may be {@code null} for direct use outside agent assembly
     * @param ctx the per-call {@link RuntimeContext}; {@code null} is treated as an empty context
     * @param currentPrompt the system prompt composed so far by earlier middlewares
     * @return the prompt with this call's skill section appended, or {@code currentPrompt}
     *     unchanged when no skill is visible for this call
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        // The tool follows the middleware binding: register on the invoking agent's live
        // toolkit before the first prompt pass (idempotent — a same-named tool present
        // means already registered). Runs before tool schemas are enumerated, so the
        // load tool is visible to the model within the same call.
        if (agent != null) {
            registerSkillTools(agent.getToolkit());
        }
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return Mono.fromCallable(
                        () -> {
                            SkillBox box = reloadSkills(agent, rc);
                            // Expose the current per-call SkillBox — or clear it when null — so
                            // the shared load tool resolves this call's skill view and a reused
                            // RuntimeContext never serves a stale box.
                            rc.put(SkillBox.class, box);
                            return appendSkillPrompt(agent, box, rc, currentPrompt);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Returns {@code currentPrompt} with the box's rendered skill prompt appended, or {@code
     * currentPrompt} unchanged when {@code box} is {@code null} or renders no prompt.
     *
     * @param agent the invoking agent, whose baseline composed with the context overlay selects
     *     the rendered subset; may be {@code null}
     * @param box the per-call {@link SkillBox} to render; {@code null} skips rendering
     * @param rc the per-call {@link RuntimeContext}; never {@code null}
     * @param currentPrompt the system prompt composed so far; may be {@code null}
     * @return the prompt with the skill section appended, or {@code currentPrompt} unchanged
     */
    private String appendSkillPrompt(
            Agent agent, SkillBox box, RuntimeContext rc, String currentPrompt) {
        if (box == null) {
            return currentPrompt;
        }
        SkillFilter effectiveFilter = resolveFilter(agent, rc);
        String prompt = box.getSkillPrompt(effectiveFilter);
        if (prompt == null || prompt.isEmpty()) {
            return currentPrompt;
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return base + separator + prompt;
    }

    /**
     * Hook for subclasses to drop skills that should not be visible in the current request
     * (canary releases, allow-lists, environment gates, …). Called once per {@code call()},
     * after merging across repositories and before the {@link SkillBox} is rebuilt.
     *
     * <p>Default implementation returns {@code raw} unchanged.
     *
     * <p>Note: the returned list selects <b>by skill name</b> — merged entries whose names
     * survive keep their own bound content; the returned {@code AgentSkill} instances
     * themselves are not adopted into the view.
     *
     * @param raw the merged, deduplicated skill list (low-to-high repository priority order)
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the visible subset (must not be {@code null}; an empty list short-circuits the
     *     prompt update for this call)
     */
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return raw;
    }

    /**
     * Effective filter = per-call context overlay on the invoking agent's baseline.
     *
     * @param agent the invoking agent whose baseline applies; may be {@code null}
     * @param rc the per-call context carrying the optional overlay; may be {@code null}
     * @return the composed {@link SkillFilter}; never {@code null}
     */
    private SkillFilter resolveFilter(Agent agent, RuntimeContext rc) {
        SkillFilter baseline = resolveBaseline(agent);
        SkillFilter runtimeOverlay = rc != null ? rc.get(SkillFilter.class) : null;
        return baseline.overlay(runtimeOverlay);
    }

    /**
     * Resolves the invoking agent's baseline {@link SkillFilter}, defaulting to {@link
     * SkillFilter#all()} when the agent is {@code null} or exposes none.
     *
     * @param agent the invoking agent; may be {@code null}
     * @return the baseline filter; never {@code null}
     */
    private SkillFilter resolveBaseline(Agent agent) {
        SkillFilter fromAgent = agent != null ? agent.getSkillFilter() : null;
        return fromAgent != null ? fromAgent : SkillFilter.all();
    }

    /**
     * Reloads the visible skill view for this call and builds its {@link SkillBox}.
     *
     * <p>Session state resolves (or establishes) first under the per-session monitor, then the
     * versioned entries replay from the session while repositories without version capability
     * are re-read live. The subclass visibility hook filters the merged view by name, and the
     * survivors become a freshly built box.
     *
     * @param agent the invoking agent whose toolkit, filter baseline and version policy drive
     *     this pass; may be {@code null}
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the per-call {@link SkillBox}, or {@code null} when no repository is registered
     *     or nothing is visible for this call
     */
    private SkillBox reloadSkills(Agent agent, RuntimeContext ctx) {
        if (repositories.isEmpty()) {
            return null;
        }
        MirrorSession session;
        synchronized (sessions) {
            String key = sessionKeyOf(agent, ctx);
            session = sessions.get(key);
            if (session == null) {
                session = new MirrorSession();
                sessions.put(key, session);
                // Bound the session-state table: oldest first. Safe because a session
                // re-establishes deterministically (anchors restore pinned versions).
                while (sessions.size() > MAX_CACHED_SESSIONS) {
                    sessions.remove(sessions.keySet().iterator().next());
                }
            }
        }
        Map<String, SkillEntry> merged = new LinkedHashMap<>();
        // One monitor per session: the establish/refresh pass and the entry replay below
        // mutate plain-map session state, so overlapping calls of the same session must
        // serialize here (otherwise double establishment and a
        // ConcurrentModificationException on the replay are both possible). Different
        // sessions never contend on this monitor.
        synchronized (session) {
            if (!session.established) {
                establishSession(agent, ctx, session);
            } else {
                refreshSession(ctx, session);
            }
            ensureOverlayEntries(agent, ctx, session);
            SkillFilter effective = resolveFilter(agent, ctx);
            // Per call: replay the versioned session entries and re-read the repositories
            // without version capability (they keep per-call freshness). Stack order
            // preserved; later repos override by name.
            for (AgentSkillRepository repo : repositories) {
                if (session.versionedRepositories.contains(repo)) {
                    for (SkillEntry entry : session.entriesByName.values()) {
                        if (entry.repository == repo) {
                            merged.put(entry.skill.getName(), entry);
                        }
                    }
                } else {
                    List<AgentSkill> skills = session.establishmentReads.remove(repo);
                    if (skills == null) {
                        // The live tier keeps per-call freshness: a context-scoped
                        // repository re-reads its request-bound catalog; a plain repository
                        // is never enumerated — its explicit selection is re-pulled through
                        // the serving primitive, bounded by the filter, not the repository.
                        skills =
                                repo instanceof RuntimeContextSkillRepository
                                        ? readRepository(repo, ctx)
                                        : repullExplicit(repo, effective, ctx);
                    }
                    for (AgentSkill skill : skills) {
                        if (skill == null || skill.getName() == null) {
                            continue;
                        }
                        merged.put(skill.getName(), new SkillEntry(repo, skill));
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        // Apply subclass visibility hook before the SkillBox is rebuilt so the prompt only
        // sees skills the current ctx is allowed to see.
        List<SkillEntry> visible;
        try {
            List<AgentSkill> raw = new ArrayList<>();
            for (SkillEntry entry : merged.values()) {
                raw.add(entry.skill);
            }
            List<AgentSkill> filtered = filterVisible(raw, ctx);
            if (filtered == null) {
                visible = new ArrayList<>(merged.values());
            } else {
                Set<String> kept = new HashSet<>();
                for (AgentSkill skill : filtered) {
                    if (skill != null && skill.getName() != null) {
                        kept.add(skill.getName());
                    }
                }
                visible = new ArrayList<>();
                for (SkillEntry entry : merged.values()) {
                    if (kept.contains(entry.skill.getName())) {
                        visible.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "filterVisible() in {} failed; treating as pass-through: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
            visible = new ArrayList<>(merged.values());
        }
        if (visible.isEmpty()) {
            return null;
        }
        return buildBox(agent, visible);
    }

    /**
     * Session establishment: resolve each repository's candidate names once and pull only
     * the names the invoking agent's baseline {@link SkillFilter} allows, each at a null
     * version (latest). A context-scoped repository's candidates are its request-bound
     * catalog (first-class context variant); a plain repository is never enumerated — only
     * the baseline's explicit names ({@link SkillFilter#only}) are pulled from it, and
     * without an explicit selection it is skipped with one warning. Stamped pulls make
     * their repository version-capable (session-bound entries; PIN skills anchored);
     * unstamped pulls leave it re-read per call; a repository whose pulls resolve nothing
     * (for example a context-scoped backend without a version route) falls back to the
     * request-bound bulk read. Context-scoped catalogs stay in the session so a later call
     * whose effective filter allows a name the baseline denied can pull it on demand
     * ({@link #ensureOverlayEntries}); a plain repository's overlay candidates are the
     * effective filter's explicit names themselves. A context-scoped repository whose every
     * catalog name the baseline denies is classified by one probe pull instead of the
     * selection: a stamped probe keeps the version route with zero entries, anything else
     * keeps today's bulk semantics (a plain repository's selection is baseline-allowed by
     * construction, so the probe path never applies to it). The first pull of a skill may
     * materialize it inside the repository.
     *
     * @param agent the invoking agent whose baseline filter and version policy drive
     *     establishment; may be {@code null}
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @param session the session state to populate; established on return
     */
    private void establishSession(Agent agent, RuntimeContext ctx, MirrorSession session) {
        SessionRecorder recorder = SessionRecorder.from(ctx);
        boolean pin = isPinPolicy(agent);
        SkillFilter baseline = resolveBaseline(agent);
        boolean anyCandidateNames = false;
        for (AgentSkillRepository repo : repositories) {
            List<String> names = readNames(repo, ctx, baseline);
            if (names.isEmpty()) {
                continue;
            }
            anyCandidateNames = true;
            if (repo instanceof RuntimeContextSkillRepository) {
                session.nameCatalogs.put(repo, names);
            }
            // Explicit selection bounds the establishment work: names the baseline denies
            // are not pulled here; they stay in the catalog for the per-call overlay pass.
            List<AgentSkill> candidates = new ArrayList<>(names.size());
            boolean anyPullAttempted = false;
            for (String name : names) {
                if (!baseline.isAllowed(name)) {
                    continue;
                }
                anyPullAttempted = true;
                AgentSkill skill = pullSkill(repo, name, null, ctx);
                if (skill == null || skill.getName() == null) {
                    continue;
                }
                candidates.add(skill);
            }
            if (!anyPullAttempted) {
                // Every name denied: classification cannot come from the selection, so one
                // probe pull (an arbitrary catalog name, latest) learns the route. A stamped
                // probe keeps the version route with zero entries — the catalog serves later
                // overlay-enabled names; any other outcome falls through to the bulk read.
                AgentSkill probe = pullSkill(repo, names.get(0), null, ctx);
                if (probe != null && probe.getName() != null && probe.getVersion() != null) {
                    session.versionedRepositories.add(repo);
                    continue;
                }
                anyPullAttempted = true;
            }
            if (candidates.isEmpty()) {
                // When no pull resolves (a backend without a version route), the bulk read
                // supplies the candidates instead.
                candidates = readRepository(repo, ctx);
            }
            if (candidates.isEmpty()) {
                continue;
            }
            boolean anyVersionStamp = false;
            for (AgentSkill skill : candidates) {
                if (skill != null && skill.getName() != null && skill.getVersion() != null) {
                    anyVersionStamp = true;
                    break;
                }
            }
            if (anyVersionStamp) {
                session.versionedRepositories.add(repo);
                for (AgentSkill skill : candidates) {
                    if (skill == null) {
                        continue;
                    }
                    if (skill.getVersion() == null) {
                        // A version-capable backend stamps every instance it serves; an
                        // unstamped load cannot be bound to a version, so warn and skip it
                        // instead of binding it silently.
                        session.unbindableNames
                                .computeIfAbsent(repo, r -> new HashSet<>())
                                .add(skill.getName());
                        log.warn(
                                "Repository {} returned unstamped skill '{}'; skipping the"
                                        + " unbindable load",
                                repo.getClass().getSimpleName(),
                                skill.getName());
                        continue;
                    }
                    SkillEntry entry = new SkillEntry(repo, skill);
                    if (pin) {
                        anchorEntry(agent, ctx, recorder, entry);
                    }
                    session.entriesByName.put(skill.getName(), entry);
                }
            } else {
                // No version capability for this repository: the establishment reads serve
                // the first merge pass, then the per-call bulk read takes over.
                session.establishmentReads.put(repo, candidates);
            }
        }
        if (pin && session.versionedRepositories.isEmpty() && !repositories.isEmpty()) {
            // A pin that no repository can honor is surfaced per the policy instead of
            // silently serving latest: the strict mode fails at establishment, the default
            // fallback degrades with a warning. The diagnosis names the actual shape —
            // loads happen but none carries a version stamp, versus nothing to pull at all
            // (every catalog or explicit selection empty). The empty case still fails: an
            // empty tier is live, so skills appearing there later would serve unstamped
            // under a declared pin — capability can never be demonstrated.
            String diagnosis =
                    anyCandidateNames
                            ? "no registered repository loads versioned skills (no load"
                                    + " carries a version stamp)"
                            : "no skill is available to pull (every repository catalog or"
                                    + " explicit selection is empty), so version capability"
                                    + " cannot be demonstrated";
            if (isStrictPolicy(agent)) {
                throw new IllegalStateException(
                        "Skill version policy PIN is declared but "
                                + diagnosis
                                + "; a strict pin cannot be honoured — declare an explicit"
                                + " SkillFilter.only(...) selection backed by a version-capable"
                                + " repository, use SkillVersionPolicy.latest(), or register a"
                                + " version-capable repository");
            }
            log.warn(
                    "Skill version policy PIN declared but {}; serving latest (fallback"
                            + " semantics)",
                    diagnosis);
        }
        session.established = true;
        session.lastFreshnessMillis = System.currentTimeMillis();
    }

    /**
     * Per-call overlay pass over the version route: a catalog name the effective filter
     * (agent baseline composed with the call's context overlay) allows but that has no
     * session entry — the baseline denied it at establishment, or a runtime overlay enabled
     * it later — is pulled on demand now. Repositories are visited high-priority first so a
     * name shared by two repositories is entered once, by the winner, matching
     * establishment's compose order. A context-scoped repository without version capability
     * is outside this pass (its per-call bulk read already carries every catalog name); a
     * plain repository consults only the overlay delta — the explicit names the effective
     * filter adds over the baseline, since establishment already pulled the baseline's
     * selection and the per-call re-pull serves the whole effective selection for
     * live-tier repositories. A plain repository that establishment never classified — it
     * had no explicit selection then — enters the version route lazily here, on the first
     * stamped pull this pass sees, so overlay-enabled names get the same binding (and PIN
     * anchoring) as establishment-time entries. PIN entries anchor at first sight (first
     * writer wins, so a resumed session keeps the winner's version).
     *
     * @param agent the invoking agent whose baseline and overlay compose the effective filter;
     *     may be {@code null}
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @param session the session whose catalog names and entries this pass extends
     */
    private void ensureOverlayEntries(Agent agent, RuntimeContext ctx, MirrorSession session) {
        if (session.versionedRepositories.isEmpty()
                && repositories.stream()
                        .allMatch(RuntimeContextSkillRepository.class::isInstance)) {
            return;
        }
        SkillFilter effective = resolveFilter(agent, ctx);
        List<String> baselineSelection = explicitSelectionOf(resolveBaseline(agent));
        boolean pin = isPinPolicy(agent);
        SessionRecorder recorder = SessionRecorder.from(ctx);
        for (int i = repositories.size() - 1; i >= 0; i--) {
            AgentSkillRepository repo = repositories.get(i);
            boolean contextRepo = repo instanceof RuntimeContextSkillRepository;
            boolean versioned = session.versionedRepositories.contains(repo);
            if (!versioned && contextRepo) {
                // A context-scoped live-tier repository: the per-call bulk read carries
                // every catalog name, so nothing to bind here.
                continue;
            }
            // A context-scoped repository consults its session catalog; a plain repository
            // has none — the overlay delta (the effective selection minus the baseline's)
            // is its only candidate set here.
            List<String> catalog;
            if (contextRepo) {
                catalog = session.nameCatalogs.get(repo);
            } else {
                catalog = new ArrayList<>(explicitSelectionOf(effective));
                catalog.removeAll(baselineSelection);
            }
            if (catalog == null || catalog.isEmpty()) {
                continue;
            }
            Set<String> unbindable =
                    session.unbindableNames.computeIfAbsent(repo, r -> new HashSet<>());
            for (String name : catalog) {
                if (!effective.isAllowed(name)
                        || unbindable.contains(name)
                        || session.entriesByName.containsKey(name)) {
                    continue;
                }
                AgentSkill pulled = pullSkill(repo, name, null, ctx);
                if (pulled == null || pulled.getName() == null) {
                    // Transient (or gone): retried on a later call, like refresh.
                    continue;
                }
                if (pulled.getVersion() == null) {
                    if (versioned) {
                        // Same rule as establishment: an unstamped load cannot be bound.
                        unbindable.add(name);
                        log.warn(
                                "Repository {} returned unstamped skill '{}' on the overlay"
                                        + " pass; skipping the unbindable load",
                                repo.getClass().getSimpleName(),
                                name);
                    }
                    // An unstamped pull from an unclassified plain repository is
                    // live-tier legitimate: left to the per-call explicit re-pull, never
                    // bound.
                    continue;
                }
                if (!versioned) {
                    // Lazy classification: the first stamped overlay pull enters this
                    // plain repository into the version route.
                    session.versionedRepositories.add(repo);
                    versioned = true;
                }
                SkillEntry entry = new SkillEntry(repo, pulled);
                if (pin) {
                    anchorEntry(agent, ctx, recorder, entry);
                }
                session.entriesByName.put(name, entry);
            }
        }
    }

    /**
     * LATEST freshness: at most once per {@link #FRESHNESS_TTL_MILLIS}, re-pull each
     * non-pinned entry at a null version and compare stamps — an unchanged stamp keeps the
     * entry; a new stamp replaces the resident skill (materialized by the repository with a
     * fresh {@code originDir}) and records a REFRESH audit event. Pinned entries are
     * skipped: a pin never advances.
     *
     * @param ctx the per-call {@link RuntimeContext} supplying the audit recorder; never
     *     {@code null}
     * @param session the session whose non-pinned entries are freshness-checked
     */
    private void refreshSession(RuntimeContext ctx, MirrorSession session) {
        long now = System.currentTimeMillis();
        if (session.versionedRepositories.isEmpty()
                || now - session.lastFreshnessMillis < freshnessTtlMillis) {
            return;
        }
        session.lastFreshnessMillis = now;
        SessionRecorder recorder = SessionRecorder.from(ctx);
        for (SkillEntry entry : session.entriesByName.values()) {
            if (entry.pinned || !session.versionedRepositories.contains(entry.repository)) {
                continue;
            }
            String name = entry.skill.getName();
            AgentSkill pulled = pullSkill(entry.repository, name, null, ctx);
            if (pulled == null || pulled.getName() == null) {
                // Transient failure: keep serving the last-known version.
                continue;
            }
            String latest = pulled.getVersion();
            if (latest == null || latest.equals(entry.version())) {
                continue;
            }
            String previous = entry.version();
            entry.skill = pulled;
            SkillAnchorService.refreshAudit(recorder, pulled.getSkillId(), previous, latest);
        }
    }

    /**
     * Anchors one PIN entry via {@link SkillAnchorService} (first writer wins) and adopts
     * the winning anchor's version: a resumed session keeps the winner's version, and that
     * version is re-pulled explicitly. An unreachable or mismatched pull for the anchored
     * version is resolved by {@link #handleUnreachable} per the policy's {@code
     * onUnreachable}.
     *
     * @param agent the invoking agent, consulted when the anchored version needs the policy's
     *     unreachable decision; may be {@code null}
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @param recorder the session recorder receiving anchor events
     * @param entry the entry to anchor and pin; always left {@code pinned = true}
     */
    private void anchorEntry(
            Agent agent, RuntimeContext ctx, SessionRecorder recorder, SkillEntry entry) {
        String anchored =
                SkillAnchorService.anchorInitial(
                                recorder, entry.skill.getSkillId(), entry.version(), "pin")
                        .version();
        if (!anchored.equals(entry.version())) {
            // First writer wins: serve the winner's anchored version, not the current latest.
            AgentSkill pinned = pullSkill(entry.repository, entry.skill.getName(), anchored, ctx);
            if (pinned == null || isMismatchedVersionServed(anchored, pinned)) {
                handleUnreachable(agent, ctx, entry, anchored);
            } else {
                entry.skill = pinned;
            }
        }
        entry.pinned = true;
    }

    /**
     * Resolves a pinned entry whose anchored version is unreachable, per the policy's {@code
     * onUnreachable}: the strict mode throws; the default FALLBACK_TO_LATEST pulls the
     * current version once, adopts it, re-anchors the session record to the served
     * version, and logs the fallback at error level.
     *
     * @param agent the invoking agent whose policy picks strict or fallback semantics
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @param entry the pinned entry whose anchored version is unreachable
     * @param unreachable the anchored version reference that could not be served
     */
    private void handleUnreachable(
            Agent agent, RuntimeContext ctx, SkillEntry entry, String unreachable) {
        String name = entry.skill.getName();
        if (isStrictPolicy(agent)) {
            throw new IllegalStateException(
                    "Pinned skill '"
                            + name
                            + "' version "
                            + unreachable
                            + " is unreachable (removed or never existed); declare"
                            + " SkillVersionPolicy.pinned().fallbackToLatest() to degrade"
                            + " visibly, or fix the anchor");
        }
        AgentSkill fallback = pullSkill(entry.repository, name, null, ctx);
        if (fallback == null || fallback.getVersion() == null) {
            throw new IllegalStateException(
                    "Pinned skill '"
                            + name
                            + "' version "
                            + unreachable
                            + " is unreachable and the backend reports no stamped current"
                            + " version to fall back to");
        }
        entry.skill = fallback;
        log.error(
                "Skill fallback: pinned '{}' version {} unreachable; re-anchored to {}"
                        + " (one-shot, session continues under pin semantics)",
                name,
                unreachable,
                fallback.getVersion());
        SkillAnchorService.anchorFallback(
                SessionRecorder.from(ctx),
                fallback.getSkillId(),
                unreachable,
                fallback.getVersion());
    }

    /**
     * Detects a backend serving a skill stamped with a version different from the one
     * requested: the caller treats the requested version as unreachable and routes the
     * entry through the policy's {@code onUnreachable} decision, so mismatched content is
     * never bound to the requested reference. Only guards explicit version-addressed
     * pulls (the PIN resume path).
     *
     * @param requested the version reference the pull asked for
     * @param fetched the skill the backend actually returned
     * @return {@code true} when the served stamp differs from the requested version
     */
    private static boolean isMismatchedVersionServed(String requested, AgentSkill fetched) {
        String served = fetched.getVersion();
        if (served != null && !served.equals(requested)) {
            log.warn(
                    "Backend served skill '{}' labeled version {} under requested version"
                            + " {} — no-mismatch violation, treating the requested version as"
                            + " unreachable",
                    fetched.getName(),
                    served,
                    requested);
            return true;
        }
        return false;
    }

    /**
     * Builds the per-call {@link SkillBox} — one fresh box per call, never shared. Versioned
     * entries register their repository-served summary (the materialized version directory is
     * already their {@code originDir}); entries without a version register their in-memory
     * snapshot. {@code uploadSkillFiles} runs only when some visible skill still needs
     * materialization (no {@code originDir}): it allocates one fresh per-box directory
     * lazily inside the box, so a fully materialized view allocates nothing, and each build
     * owns its directory — a later call never rewrites files an earlier one may still be
     * executing against.
     *
     * @param agent the invoking agent whose live toolkit the box binds to; may be {@code null}
     * @param visible the merged, filtered entries to register
     * @return the freshly built, fully populated box; never {@code null}
     */
    private SkillBox buildBox(Agent agent, List<SkillEntry> visible) {
        SkillBox box = new SkillBox(agent != null ? agent.getToolkit() : null);
        for (SkillEntry entry : visible) {
            box.registerSkill(entry.skill);
        }
        boolean anyUnmaterialized = false;
        for (SkillEntry entry : visible) {
            if (entry.skill.getOriginDir().isEmpty()) {
                anyUnmaterialized = true;
                break;
            }
        }
        if (anyUnmaterialized && box.isAutoUploadSkill()) {
            try {
                box.uploadSkillFiles();
            } catch (Exception e) {
                log.warn("Failed to upload skill files: {}", e.getMessage());
            }
        }
        // Toggle the code-execution prompt block: when codeExecutionEnabled, the prompt
        // provider will either emit per-skill <files-root> entries (every visible skill has an
        // originDir) or fall back to the single uploadDir template. Either way the LLM gets
        // an addressable path instead of staring at a `load_skill_through_path` blob.
        box.getSkillPromptProvider().setCodeExecutionEnable(codeExecutionEnabled);
        return box;
    }

    /**
     * Whether the invoking agent declares a PIN-mode policy (no policy = LATEST).
     *
     * @param agent the invoking agent; may be {@code null}
     * @return {@code true} when a PIN-mode {@link SkillVersionPolicy} is declared
     */
    private static boolean isPinPolicy(Agent agent) {
        SkillVersionPolicy policy = versionPolicyOf(agent);
        return policy != null && policy.mode() == SkillVersionPolicy.Mode.PIN;
    }

    /**
     * Whether the invoking agent declares a strict pin (PIN + {@code onUnreachable=ERROR}).
     *
     * @param agent the invoking agent; may be {@code null}
     * @return {@code true} when the declared policy is a strict pin
     */
    private static boolean isStrictPolicy(Agent agent) {
        SkillVersionPolicy policy = versionPolicyOf(agent);
        return policy != null
                && policy.mode() == SkillVersionPolicy.Mode.PIN
                && policy.onUnreachable() == SkillVersionPolicy.OnUnreachable.ERROR;
    }

    /**
     * Resolves the invoking agent's policy via the {@link Agent} interface — {@code null}
     * (unset) means LATEST.
     *
     * @param agent the invoking agent; may be {@code null}
     * @return the declared {@link SkillVersionPolicy}, or {@code null} when unset
     */
    private static SkillVersionPolicy versionPolicyOf(Agent agent) {
        return agent != null ? agent.getSkillVersionPolicy() : null;
    }

    /**
     * Builds the session-state key as {@code agentId|userId|sessionId}, defaulting missing
     * parts to placeholders so the key is always stable.
     *
     * @param agent the invoking agent; may be {@code null}
     * @param ctx the per-call context; may be {@code null}
     * @return the stable session-state key; never {@code null}
     */
    private static String sessionKeyOf(Agent agent, RuntimeContext ctx) {
        String agentId = agent != null && agent.getName() != null ? agent.getName() : "agent";
        String userId = ctx != null && ctx.getUserId() != null ? ctx.getUserId() : "-";
        String sessionId = ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : "-";
        return agentId + "|" + userId + "|" + sessionId;
    }

    /** Reads one repository's full listing (context repositories only — their
     * request-bound catalog is the first-class freshness mechanism), mapping any failure to
     * an empty list with a warning — one broken repository never blanks the whole skill
     * view. Used once per call for context repositories without version capability.
     *
     * @param repo the context-scoped repository to read; a plain repository answers empty
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the repository's skills, or an empty list on failure or unsupported repository
     */
    private List<AgentSkill> readRepository(AgentSkillRepository repo, RuntimeContext ctx) {
        if (!(repo instanceof RuntimeContextSkillRepository contextual)) {
            // Serving paths never bulk-read a plain repository (name listing already
            // warned when the repository contributes nothing); guarded defensively.
            return List.of();
        }
        try {
            List<AgentSkill> skills = contextual.getAllSkills(ctx);
            return skills == null ? List.of() : skills;
        } catch (Exception e) {
            log.warn(
                    "Skill repository {} failed to load: {}",
                    repo.getClass().getSimpleName(),
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolves one repository's candidate names: a context-scoped repository lists its
     * request-bound catalog (first-class); a plain repository is never enumerated — the
     * baseline's explicit selection is its only candidate source, and without one it is
     * skipped with one warning per repository (mapped to an empty candidate list).
     *
     * @param repo the repository whose candidate names are resolved
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @param baseline the invoking agent's baseline filter, source of a plain repository's
     *     explicit selection
     * @return the candidate names, or an empty list on failure
     */
    private List<String> readNames(
            AgentSkillRepository repo, RuntimeContext ctx, SkillFilter baseline) {
        if (!(repo instanceof RuntimeContextSkillRepository contextual)) {
            List<String> selection = explicitSelectionOf(baseline);
            if (selection.isEmpty()) {
                warnPlainEnumerationSkipped(repo);
            }
            return selection;
        }
        try {
            List<String> names = contextual.getAllSkillNames(ctx);
            return names == null ? List.of() : names;
        } catch (Exception e) {
            log.warn(
                    "Skill repository {} failed to list names: {}",
                    repo.getClass().getSimpleName(),
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Warns once per repository that a plain repository without explicit selection is
     * skipped — enumeration is not part of the serving contract, so this is a usage
     * hint, not an error.
     *
     * @param repo the plain repository being skipped for serving
     */
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

    /**
     * The serving rule for repositories this middleware must not enumerate: a plain
     * repository is only ever pulled by explicit selection, and the selection is the
     * names the filter declares that the filter itself still admits. The interpretation
     * lives here, at the serving scenario — the filter reports raw declared names and
     * stays a predicate. Sorted so the pull order stays deterministic and stable across
     * calls.
     *
     * @param filter the filter whose declared names form the selection
     * @return the sorted explicit selection the filter itself still admits; empty when the
     *     filter declares nothing
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

    /**
     * Re-pulls a plain repository's explicitly selected names through the serving
     * primitive — the per-call freshness path for plain repositories without version
     * capability. Bounded by the effective filter's explicit selection, never by
     * repository enumeration.
     *
     * @param repo the plain repository to re-pull from
     * @param effective the effective filter bounding the selection
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the re-pulled skills; empty when the selection is empty or every pull fails
     */
    private List<AgentSkill> repullExplicit(
            AgentSkillRepository repo, SkillFilter effective, RuntimeContext ctx) {
        List<String> names = explicitSelectionOf(effective);
        if (names.isEmpty()) {
            return List.of();
        }
        List<AgentSkill> skills = new ArrayList<>(names.size());
        for (String name : names) {
            AgentSkill skill = pullSkill(repo, name, null, ctx);
            if (skill != null && skill.getName() != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    /**
     * One version pull, context-routed for scope repositories, failure-tolerant: a pull that
     * throws answers {@code null} (the caller treats it as "keep last-known" / "skip the
     * name"), because one broken repository or one missing skill never blanks the skill view.
     * {@code version == null} means latest — the repository resolves its own change signal.
     *
     * @param repo the repository to pull from
     * @param name the skill name to pull
     * @param version the version reference to pull, or {@code null} for latest
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the pulled skill, or {@code null} when the pull fails or resolves nothing
     */
    private static AgentSkill pullSkill(
            AgentSkillRepository repo, String name, String version, RuntimeContext ctx) {
        try {
            return repo instanceof RuntimeContextSkillRepository contextual
                    ? contextual.getSkill(name, version, ctx)
                    : repo.getSkill(name, version);
        } catch (Exception e) {
            log.warn(
                    "Skill pull '{}' (version {}) on {} failed: {}",
                    name,
                    version,
                    repo.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }
}
