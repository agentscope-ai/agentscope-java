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
import io.agentscope.core.tool.ToolkitAware;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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
 * caching by skill id alone would mask those swaps. {@code bindToolkit} /
 * {@code registerSkillLoadTool} are idempotent on a fresh {@link SkillBox}, so the rebuild stays
 * cheap.
 *
 * <p>Subclasses can plug runtime visibility logic (canary lists, environment gates, etc.) by
 * overriding {@link #filterVisible(List, RuntimeContext)} — the default implementation returns
 * the input list unchanged.
 */
@SuppressWarnings("deprecation")
public class DynamicSkillMiddleware implements MiddlewareBase, ToolkitAware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillMiddleware.class);

    private final List<AgentSkillRepository> repositories;
    private volatile Toolkit toolkit;
    private final SkillFilter builderFilter;
    private final boolean codeExecutionEnabled;

    /**
     * Stable workDir handed to every rebuilt {@link SkillBox}. {@code null} means "mkdtemp once
     * on first use and reuse." Fixed for the lifetime of the middleware so {@code uploadSkillFiles}
     * no longer creates a fresh {@code agentscope-code-execution-*} directory every call.
     */
    private volatile Path stableWorkDir;

    private volatile SkillBox currentSkillBox;

    /** Hash of the last merged-and-filtered skill view; identical hash ⇒ reuse {@link #currentSkillBox}. */
    private volatile String lastSignature;

    public DynamicSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null, false, null);
    }

    public DynamicSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this(repositories, toolkit, builderFilter, false, null);
    }

    /**
     * Full constructor.
     *
     * @param repositories        compose-ordered list (low-to-high priority); merged by skill name
     * @param toolkit             toolkit to register {@code load_skill_through_path} on
     * @param builderFilter       optional builder-time skill filter; null treated as {@link SkillFilter#all()}
     * @param codeExecutionEnabled when {@code true}, toggles the prompt provider's code-execution
     *                             block. Set this only when the agent has a shell-like tool wired
     *                             in; otherwise the prompt asks the model to do things it cannot.
     * @param workDir              stable working directory for {@code uploadSkillFiles}; {@code null}
     *                             ⇒ the middleware mkdtemps one on first reload and reuses it
     */
    public DynamicSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            boolean codeExecutionEnabled,
            Path workDir) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.toolkit = toolkit;
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
        this.codeExecutionEnabled = codeExecutionEnabled;
        this.stableWorkDir = workDir;
    }

    /**
     * Returns the most recently materialised {@link SkillBox}, or {@code null} when no skills
     * are visible yet (e.g. before the first {@code call()}).
     */
    public SkillBox getCurrentSkillBox() {
        return currentSkillBox;
    }

    @Override
    public void rebindToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Toolkit callToolkit = RuntimeContext.resolveToolkit(rc, agent);
        SkillBox skillBox = reloadSkills(rc, callToolkit);
        if (skillBox == null) {
            return Mono.just(currentPrompt);
        }
        SkillFilter effectiveFilter = resolveFilter(rc);
        String prompt = skillBox.getSkillPrompt(effectiveFilter);
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

    private synchronized SkillBox reloadSkills(RuntimeContext ctx, Toolkit callToolkit) {
        if (repositories.isEmpty()) {
            currentSkillBox = null;
            lastSignature = null;
            return null;
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
            lastSignature = null;
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
            currentSkillBox = null;
            lastSignature = null;
            return null;
        }

        // Content-keyed short-circuit: if the (sorted) merged view hashes to the same value as
        // the previous call AND we already have a SkillBox, skip the entire rebuild + upload.
        // This is the common case once the agent has been running for a while — repos don't
        // change between most turns.
        String signature = computeSignature(visible);
        if (signature.equals(lastSignature) && currentSkillBox != null) {
            if (callToolkit != null && callToolkit != toolkit) {
                registerSkillLoader(currentSkillBox, callToolkit);
            }
            return currentSkillBox;
        }

        Toolkit targetToolkit = callToolkit != null ? callToolkit : toolkit;
        SkillBox box = new SkillBox(targetToolkit);
        // Hand it the stable workDir BEFORE uploadSkillFiles runs so the upload lands at a
        // predictable location and we don't mkdtemp per call.
        box.setWorkDir(ensureStableWorkDir());
        for (AgentSkill skill : visible) {
            box.registerSkill(skill);
        }
        registerSkillLoader(box, targetToolkit);
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
        currentSkillBox = box;
        lastSignature = signature;
        return box;
    }

    private void registerSkillLoader(SkillBox box, Toolkit callToolkit) {
        Toolkit targetToolkit = callToolkit != null ? callToolkit : toolkit;
        if (targetToolkit == null) {
            return;
        }
        try {
            box.bindToolkit(targetToolkit);
            box.registerSkillLoadTool();
        } catch (Exception e) {
            log.warn("Failed to bind skill toolkit hooks: {}", e.getMessage());
        }
    }

    /**
     * Lazily allocates the stable workDir on first call when the caller didn't provide one.
     * The directory is registered for shutdown cleanup so a long-running JVM doesn't leak temp
     * trees.
     */
    private Path ensureStableWorkDir() {
        Path dir = stableWorkDir;
        if (dir != null) {
            return dir;
        }
        synchronized (this) {
            if (stableWorkDir != null) {
                return stableWorkDir;
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
                stableWorkDir = created;
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
     * Build a deterministic SHA-256 over the merged skill view: per-skill {@code name +
     * sha256(content) + sha256(sorted-resource-keys) + originDir}. Two reload calls with the
     * same repository content produce the same signature, regardless of map iteration order.
     */
    private static String computeSignature(List<AgentSkill> visible) {
        // Use a sorted set so that LinkedHashMap insertion-order changes don't perturb the hash.
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
                        s.getSkillContent() != null
                                ? s.getSkillContent().getBytes(StandardCharsets.UTF_8)
                                : new byte[0]);
                md.update((byte) 0);
                Set<String> sortedResources = new TreeSet<>(s.getResources().keySet());
                for (String key : sortedResources) {
                    md.update(key.getBytes(StandardCharsets.UTF_8));
                    String val = s.getResources().get(key);
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
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
