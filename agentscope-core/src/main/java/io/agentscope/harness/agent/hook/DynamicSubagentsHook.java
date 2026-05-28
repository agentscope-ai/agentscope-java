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
import io.agentscope.core.legacy.hook.PreReasoningEvent;
import io.agentscope.core.legacy.hook.RuntimeContextAware;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dynamic counterpart to {@link SubagentsHook} that re-resolves the registered subagent set on
 * every reasoning step, supporting per-user isolation through the workspace
 * {@link AbstractFilesystem} (e.g. {@code CompositeFilesystem} routing user-scoped writes into a
 * remote Store).
 *
 * <p><strong>Two-layer load</strong> (mirrors {@link WorkspaceManager} override semantics):
 *
 * <ol>
 *   <li><em>Layer 1 (override)</em> — {@code filesystem.glob("*.md", "subagents")} + per-file
 *       {@code filesystem.read}. The backend's {@code NamespaceFactory} is applied transparently
 *       so each user sees their own slice of the store.
 *   <li><em>Layer 2 (base)</em> — {@code AgentSpecLoader.loadFromDirectory} reads the local
 *       workspace {@code subagents/} directory directly. This preserves the original behaviour of
 *       {@code LocalFilesystemWithShell} / {@code Sandbox} modes where subagent declarations live
 *       on the host filesystem rather than in a per-user namespace.
 *   <li><em>Merge</em> — same-name entries from Layer 1 override Layer 2; programmatic
 *       (builder-registered) entries are preserved as the static prefix and same-name dynamic
 *       declarations override them too.
 * </ol>
 *
 * <p>After merging, the new entry list is pushed atomically into the shared
 * {@link DefaultAgentManager} via {@link DefaultAgentManager#replaceAgents(List)} so that any
 * {@link AgentSpawnTool} invocation in the same turn observes the fresh registry. The hook then
 * renders the same {@code ## Subagents} prompt section as {@link SubagentsHook}.
 *
 * <p>This hook runs at {@link PreReasoningEvent}; priority matches
 * {@link SubagentsHook#SUBAGENT_HOOK_PRIORITY} (80).
 */
public class DynamicSubagentsHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSubagentsHook.class);

    private static final String SUBAGENTS_DIR = "subagents";
    private static final String SUBAGENT_GLOB = "*.md";

    private final List<SubagentEntry> staticEntries;
    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;
    private final Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;
    private volatile RuntimeContext runtimeContext;

    /**
     * Builds a dynamic subagents hook.
     *
     * @param staticEntries entries from the builder that are <em>not</em> derived from local-disk
     *     scanning (programmatic registrations + {@code general-purpose}). Same-name dynamic
     *     declarations override these.
     * @param filesystem workspace filesystem used for namespaced reads of {@code subagents/}
     * @param mainWorkspace local workspace root, used for Layer 2 fallback and as the
     *     {@code mainWorkspace} argument when parsing declarations
     * @param factoryBuilder turns a parsed {@link SubagentDeclaration} into a {@link SubagentFactory};
     *     supplied by {@code HarnessAgent.Builder} so this hook can reuse the same captured context
     *     (model resolver, parent toolkit, disable flags, ...)
     * @param agentManager target manager that is atomically replaced each reasoning step
     * @param subagentTool the agent-spawn tool to expose
     * @param taskRepository repository backing {@link TaskTool}; may be {@code null} for a default
     *     in-memory store
     */
    public DynamicSubagentsHook(
            List<SubagentEntry> staticEntries,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder,
            DefaultAgentManager agentManager,
            Object subagentTool,
            TaskRepository taskRepository) {
        this.staticEntries = List.copyOf(staticEntries != null ? staticEntries : List.of());
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
        this.agentManager = agentManager;
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.taskRepository = repo;
        this.subagentTool =
                subagentTool != null ? subagentTool : new AgentSpawnTool(agentManager, repo, 0);
        this.taskTool = new TaskTool(repo);
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    @Override
    public int priority() {
        return SubagentsHook.SUBAGENT_HOOK_PRIORITY;
    }

    @Override
    public List<Object> tools() {
        return List.of(subagentTool, taskTool);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent preReasoning) {
            List<SubagentEntry> merged = reloadEntries();
            if (agentManager != null) {
                agentManager.replaceAgents(merged);
            }
            if (!merged.isEmpty()) {
                String section = SubagentsHook.renderSubagentSection(merged, false);
                preReasoning.appendSystemContent(section);
            }
            String taskSummary = SubagentsHook.buildTaskSummary(taskRepository, runtimeContext);
            if (taskSummary != null) {
                preReasoning.appendSystemContent(taskSummary);
            }
        }
        return Mono.just(event);
    }

    /**
     * Two-layer merge: programmatic static entries first, then dynamic declarations from Layer 1
     * (filesystem, namespaced) overriding Layer 2 (local workspace {@code subagents/} directory).
     */
    private List<SubagentEntry> reloadEntries() {
        // ---- Layer 2 (base): local workspace scan ----
        Map<String, SubagentDeclaration> declsByName = new LinkedHashMap<>();
        Path subagentsDir = mainWorkspace != null ? mainWorkspace.resolve(SUBAGENTS_DIR) : null;
        if (subagentsDir != null && Files.isDirectory(subagentsDir)) {
            for (SubagentDeclaration d :
                    AgentSpecLoader.loadFromDirectory(subagentsDir, mainWorkspace)) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- Layer 1 (override): filesystem with namespace ----
        if (filesystem != null) {
            for (SubagentDeclaration d : loadDeclarationsViaFilesystem()) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- Materialise factories ----
        List<SubagentEntry> dynamicEntries = new ArrayList<>(declsByName.size());
        for (SubagentDeclaration decl : declsByName.values()) {
            if (factoryBuilder == null) {
                continue;
            }
            try {
                SubagentFactory factory = factoryBuilder.apply(decl);
                if (factory == null) {
                    continue;
                }
                dynamicEntries.add(
                        new SubagentEntry(decl.getName(), decl.getDescription(), factory, decl));
            } catch (Exception e) {
                log.warn(
                        "Failed to build factory for declared subagent '{}': {}",
                        decl.getName(),
                        e.getMessage());
            }
        }

        // ---- Combine: static + dynamic, dynamic wins on name conflict ----
        Map<String, SubagentEntry> combined = new LinkedHashMap<>();
        for (SubagentEntry e : staticEntries) {
            combined.put(e.name(), e);
        }
        for (SubagentEntry e : dynamicEntries) {
            combined.put(e.name(), e);
        }
        return List.copyOf(combined.values());
    }

    private List<SubagentDeclaration> loadDeclarationsViaFilesystem() {
        RuntimeContext ctx = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        GlobResult glob;
        try {
            glob = filesystem.glob(ctx, SUBAGENT_GLOB, SUBAGENTS_DIR);
        } catch (Exception e) {
            log.debug("Filesystem glob for subagents failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubagentDeclaration> decls = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            String fileName = extractFileName(path);
            if (!fileName.endsWith(".md")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - 3);
            if (name.isEmpty()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                SubagentDeclaration decl =
                        AgentSpecLoader.parse(rr.fileData().content(), name, mainWorkspace);
                if (decl != null) {
                    decls.add(decl);
                }
            } catch (Exception e) {
                log.warn("Failed to load subagent declaration from '{}': {}", path, e.getMessage());
            }
        }
        return decls;
    }

    private static String extractFileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
