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
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * <p>Rebuilding on every call is intentional: per-user namespaced repositories may return
 * different content under the same skill name as the {@link RuntimeContext} switches users, so
 * caching by skill id alone would mask those swaps. A content-addressed cache keyed by the
 * visible-view signature reuses an already-materialised (immutable) box when the visible content
 * is unchanged, so {@code uploadSkillFiles} only re-runs when content actually changes. No tools
 * are (re)registered on the shared toolkit.
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
 *       ({@link Agent#getToolkit()}) flows into box construction, its filter baseline and
 *       its stable work directory ({@link Agent#getSkillWorkDir()}) drive filtering and
 *       materialisation. Instances hold no per-agent runtime references.</li>
 *   <li>Same-signature cache hits share one materialised directory: skill-side modifications
 *       stay visible to later calls with the same content; changed content materialises a
 *       fresh, isolated directory.</li>
 *   <li>The framework creates a fresh {@link RuntimeContext} per call; every pass overwrites
 *       (or clears) the {@link SkillBox} binding.</li>
 *   <li>The effective filter composes the per-call context overlay on the invoking agent's
 *       baseline ({@link Agent#getSkillFilter()}), defaulting to {@link SkillFilter#all()}
 *       when the agent exposes none.</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public class DynamicSkillMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillMiddleware.class);
    private static final int MAX_CACHED_BOXES = 32;

    private final List<AgentSkillRepository> repositories;

    private final boolean codeExecutionEnabled;

    /**
     * JVM-wide fallback root for materialising {@link SkillBox} directories when the invoking
     * agent provides none. Allocated once and cleaned up by a shutdown hook at JVM exit —
     * preserving the pre-refactor dir-handling behaviour; agent-provided roots are
     * caller-managed.
     */
    private static volatile Path fallbackWorkDir;

    /** Content-addressed cache of materialised {@link SkillBox}es, keyed by the visible-view
     * signature. Boxes are immutable after build, so sharing them across sessions with identical
     * content is safe and skips re-running {@code uploadSkillFiles}. */
    private final Map<String, SkillBox> boxCache = new LinkedHashMap<>();

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
     * Per-pass runtime objects — the box-construction toolkit, the filter baseline and the
     * materialisation root — are resolved from the invoking agent.
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

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /**
     * Reloads the visible skill view and appends its rendered prompt to {@code currentPrompt}.
     *
     * <p>The reload performs blocking I/O (repository reads, file materialization), so it runs on
     * {@link Schedulers#boundedElastic()} rather than the subscribing thread, keeping reactive
     * event-loop threads non-blocking. Semantics are unchanged: the reload still happens on every
     * call, and the content-addressed box cache reuses an already-materialised box whenever the
     * visible content is unchanged. The cache-hit fast path cannot stay on the calling thread
     * because the cache key (the content signature) is only known after the blocking repository
     * read, so the whole reload — including cache hits — moves to boundedElastic.
     *
     * <p>Agent contract: the framework invokes this hook with a non-null {@code agent}; direct
     * callers must supply one or accept the degraded behaviour of a null one (tolerated, not
     * contractual).
     *
     * @param agent the agent whose toolkit, skill-filter baseline and work dir drive this pass;
     *     may be {@code null} for direct use outside agent assembly
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

    /** Returns {@code currentPrompt} with the box's rendered skill prompt appended, or {@code
     * currentPrompt} unchanged when {@code box} is {@code null} or renders no prompt. */
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
     * @param raw the merged, deduplicated skill list (low-to-high repository priority order)
     * @param ctx the per-call {@link RuntimeContext}; never {@code null}
     * @return the visible subset (must not be {@code null}; an empty list short-circuits the
     *     prompt update for this call)
     */
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return raw;
    }

    /** Effective filter = per-call context overlay on the invoking agent's baseline. */
    private SkillFilter resolveFilter(Agent agent, RuntimeContext rc) {
        SkillFilter baseline = resolveBaseline(agent);
        SkillFilter runtimeOverlay = rc != null ? rc.get(SkillFilter.class) : null;
        return baseline.overlay(runtimeOverlay);
    }

    private SkillFilter resolveBaseline(Agent agent) {
        SkillFilter fromAgent = agent != null ? agent.getSkillFilter() : null;
        return fromAgent != null ? fromAgent : SkillFilter.all();
    }

    private SkillBox reloadSkills(Agent agent, RuntimeContext ctx) {
        if (repositories.isEmpty()) {
            return null;
        }
        Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            try {
                skills =
                        repo instanceof RuntimeContextSkillRepository contextual
                                ? contextual.getAllSkills(ctx)
                                : repo.getAllSkills();
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
            return null;
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
            return null;
        }

        String signature = computeSignature(visible);
        synchronized (boxCache) {
            SkillBox box = boxCache.get(signature);
            if (box == null) {
                box = buildBox(agent, visible, signature);
                if (boxCache.size() >= MAX_CACHED_BOXES) {
                    // Only bound retained boxes. Active requests may still reference evicted
                    // boxes and their files, so resource directories must remain available.
                    boxCache.clear();
                }
                boxCache.put(signature, box);
            }
            return box;
        }
    }

    private SkillBox buildBox(Agent agent, List<AgentSkill> visible, String signature) {
        SkillBox box = new SkillBox(agent != null ? agent.getToolkit() : null);
        // Each materialization owns its directory. After eviction, rebuilding the same
        // signature must not truncate files that an earlier request may still be reading.
        try {
            Path root = ensureStableWorkDir(agent);
            Files.createDirectories(root);
            box.setWorkDir(Files.createTempDirectory(root, signature + "-"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate skill resource directory", e);
        }
        for (AgentSkill skill : visible) {
            box.registerSkill(skill);
        }
        if (box.isAutoUploadSkill()) {
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
     * Resolves the workDir for this pass: the invoking agent's stable directory when it
     * provides one (caller-managed); otherwise the shared fallback root, lazily allocated on
     * first call and registered for shutdown cleanup. Resource trees remain available while
     * the JVM runs, including after cache eviction.
     */
    private Path ensureStableWorkDir(Agent agent) {
        Path fromAgent = agent != null ? agent.getSkillWorkDir() : null;
        if (fromAgent != null) {
            return fromAgent;
        }
        Path dir = fallbackWorkDir;
        if (dir != null) {
            return dir;
        }
        synchronized (DynamicSkillMiddleware.class) {
            if (fallbackWorkDir != null) {
                return fallbackWorkDir;
            }
            try {
                Path created = Files.createTempDirectory("agentscope-skill-workdir-");
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                deleteRecursively(created);
                                            } catch (IOException e) {
                                                log.debug(
                                                        "shutdown cleanup of {} failed: {}",
                                                        created,
                                                        e.getMessage());
                                            }
                                        }));
                fallbackWorkDir = created;
                return created;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to allocate stable skill workDir", e);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            stream.forEach(all::add);
            all.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Build a deterministic SHA-256 over the merged visible skill view: per-skill {@code name +
     * sha256(content) + sha256(sorted-resource-keys) + originDir}. Two reload calls with the same
     * visible content produce the same signature, regardless of map iteration order, so the
     * content-addressed {@link #boxCache} can reuse a box without re-uploading its files.
     */
    private static String computeSignature(List<AgentSkill> visible) {
        Set<String> sortedNames = new TreeSet<>();
        for (AgentSkill s : visible) {
            sortedNames.add(s.getName());
        }
        Map<String, AgentSkill> byName = new LinkedHashMap<>();
        for (AgentSkill s : visible) {
            byName.put(s.getName(), s);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : sortedNames) {
                AgentSkill s = byName.get(name);
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(
                        s.getSource() != null
                                ? s.getSource().getBytes(StandardCharsets.UTF_8)
                                : new byte[0]);
                md.update((byte) 0);
                hashMetadata(md, s.getMetadata());
                md.update((byte) 0);
                md.update(
                        s.getSkillContent() != null
                                ? s.getSkillContent().getBytes(StandardCharsets.UTF_8)
                                : new byte[0]);
                md.update((byte) 0);
                for (String key : new TreeSet<>(s.getResourcePaths())) {
                    md.update(key.getBytes(StandardCharsets.UTF_8));
                    String val = s.getResource(key);
                    if (val != null) {
                        md.update(val.getBytes(StandardCharsets.UTF_8));
                    }
                    md.update((byte) 0);
                }
                s.getOriginDir()
                        .ifPresent(p -> md.update(p.toString().getBytes(StandardCharsets.UTF_8)));
                md.update((byte) 1);
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Hashes a skill's full metadata (sorted keys, nested maps/lists recursively) so that custom
     * fields rendered into the prompt (version/homepage/license/...) also participate in the
     * content signature. */
    private static void hashMetadata(MessageDigest md, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        for (String key : new TreeSet<>(metadata.keySet())) {
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            hashMetadataValue(md, metadata.get(key));
            md.update((byte) 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void hashMetadataValue(MessageDigest md, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            md.update(s.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            md.update((byte) '{');
            List<Object> keys = new ArrayList<>(map.keySet());
            keys.sort(Comparator.comparing(Object::toString));
            for (Object key : keys) {
                md.update(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ':');
                hashMetadataValue(md, map.get(key));
            }
            md.update((byte) '}');
        } else if (value instanceof List<?> list) {
            md.update((byte) '[');
            for (Object element : list) {
                hashMetadataValue(md, element);
            }
            md.update((byte) ']');
        } else {
            md.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
