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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates the per-call {@link SkillCatalog}, the singleton {@link SkillLoadTool}, and the
 * {@link SkillPromptBuilder}. {@link io.agentscope.harness.agent.middleware.HarnessSkillMiddleware}
 * owns one instance and updates the catalog every {@code onSystemPrompt} pass.
 *
 * <p>The {@code load_skill_through_path} tool is registered on the toolkit exactly once, on the
 * first install. The tool instance holds the {@link AtomicReference} to the catalog, so swapping
 * the catalog from later rounds takes effect immediately without re-registering.
 */
public final class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final AtomicReference<SkillCatalog> catalogRef =
            new AtomicReference<>(SkillCatalog.empty());
    private final AtomicReference<Toolkit> toolkitRef = new AtomicReference<>();
    private final AtomicBoolean toolInstalled = new AtomicBoolean(false);
    private final SkillLoadTool loadTool;
    private final SkillPromptBuilder promptBuilder;

    public SkillRuntime() {
        this(new SkillPromptBuilder());
    }

    public SkillRuntime(SkillPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder != null ? promptBuilder : new SkillPromptBuilder();
        this.loadTool = new SkillLoadTool(catalogRef, toolkitRef);
    }

    /** Snapshot accessor mainly for tests; not for runtime mutation. */
    public SkillCatalog currentCatalog() {
        return catalogRef.get();
    }

    /** Underlying tool instance. Use {@link #install(SkillCatalog, Toolkit)} for normal flow. */
    public AgentTool loadTool() {
        return loadTool;
    }

    /**
     * Update the current catalog and ensure the load tool is registered on the toolkit (idempotent).
     *
     * @param catalog the new snapshot; pass {@link SkillCatalog#empty()} to clear visibility
     * @param toolkit the toolkit to install onto; may be {@code null} (then only catalog is updated)
     */
    public void install(SkillCatalog catalog, Toolkit toolkit) {
        catalogRef.set(catalog != null ? catalog : SkillCatalog.empty());
        toolkitRef.set(toolkit);
        if (toolkit == null) {
            return;
        }
        if (toolInstalled.compareAndSet(false, true)) {
            try {
                AgentTool existing = toolkit.getTool(SkillLoadTool.TOOL_NAME);
                if (existing != null && existing != loadTool) {
                    toolkit.removeTool(SkillLoadTool.TOOL_NAME);
                }
                toolkit.registerAgentTool(loadTool);
            } catch (Exception e) {
                // Don't permanently latch installed=true if the registration failed: allow a
                // retry on the next call.
                toolInstalled.set(false);
                log.warn("Failed to register {}: {}", SkillLoadTool.TOOL_NAME, e.getMessage());
            }
        }
    }

    /** Renders the {@code <available_skills>} block + (when applicable) the code-execution prompt. */
    public String renderPrompt(SkillCatalog catalog, SkillFilter filter) {
        return promptBuilder.render(
                catalog != null ? catalog : SkillCatalog.empty(),
                filter != null ? filter : SkillFilter.all());
    }
}
