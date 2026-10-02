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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
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
import reactor.core.publisher.Flux;

/**
 * Dynamic counterpart to {@link SubagentsMiddleware} that re-resolves the registered subagent
 * set on every reasoning step, supporting per-user isolation through the workspace
 * {@link AbstractFilesystem} (e.g. {@code CompositeFilesystem} routing user-scoped writes into
 * a remote Store).
 *
 * <p><strong>Two-layer load</strong> (mirrors the previous {@code DynamicSubagentsHook}
 * override semantics):
 *
 * <ol>
 *   <li><em>Layer 1 (override)</em> — {@code filesystem.glob("*.md", "subagents")} + per-file
 *       {@code filesystem.read}. The backend's {@code NamespaceFactory} is applied transparently
 *       so each user sees their own slice of the store.
 *   <li><em>Layer 2 (base)</em> — {@code AgentSpecLoader.loadFromDirectory} reads the local
 *       workspace {@code subagents/} directory directly.
 *   <li><em>Merge</em> — same-name entries from Layer 1 override Layer 2; programmatic
 *       (builder-registered) entries are preserved as the static prefix and same-name dynamic
 *       declarations override them too.
 * </ol>
 */
public class DynamicSubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSubagentsMiddleware.class);

    private static final String SUBAGENTS_DIR = "subagents";
    private static final String SUBAGENT_GLOB = "*.md";

    private final List<SubagentEntry> staticEntries;
    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;
    private volatile Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;

    public DynamicSubagentsMiddleware(
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
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool =
                subagentTool != null
                        ? subagentTool
                        : new AgentSpawnTool(agentManager, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
    }

    /**
     * Wires a gateway bridge into the internal {@link AgentSpawnTool}, enabling spawned subagents
     * to be exposed as user-addressable threads. Only effective when the middleware owns a
     * {@link DefaultAgentManager}.
     */
    public DynamicSubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (agentManager == null) {
            return this;
        }
        // Mutate the bridge on the live tool instead of replacing it: the toolkit binds
        // agent_spawn to the AgentSpawnTool instance returned by getTools() at orchestration
        // time, so a fresh instance here would never be invoked and exposure would silently
        // never fire.
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            this.subagentTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
        }
        return this;
    }

    /**
     * Returns the internal {@link DefaultAgentManager} that can re-materialize subagents, or
     * {@code null} when none is owned. Used to wire a gateway materializer for cross-node
     * exposed-subagent recovery.
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     */
    public List<Object> getTools() {
        return List.of(subagentTool, taskTool);
    }

    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        List<SubagentEntry> merged = reloadEntries(rc);
        if (agentManager != null) {
            agentManager.replaceAgents(merged);
        }
        StringBuilder addition = new StringBuilder();
        if (!merged.isEmpty()) {
            addition.append(SubagentsMiddleware.renderSubagentSection(merged, false));
        }
        String taskSummary = SubagentsMiddleware.buildTaskSummary(taskRepository, rc);
        if (taskSummary != null) {
            addition.append(taskSummary);
        }
        if (addition.length() == 0) {
            return next.apply(input);
        }
        List<Msg> rebuilt =
                SubagentsMiddleware.prependToSystemMessage(input.messages(), addition.toString());
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    private List<SubagentEntry> reloadEntries(RuntimeContext rc) {
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
            for (SubagentDeclaration d : loadDeclarationsViaFilesystem(rc)) {
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

    private List<SubagentDeclaration> loadDeclarationsViaFilesystem(RuntimeContext rc) {
        GlobResult glob;
        try {
            glob = filesystem.glob(rc, SUBAGENT_GLOB, SUBAGENTS_DIR);
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
                ReadResult rr = filesystem.read(rc, path, 0, 0);
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
