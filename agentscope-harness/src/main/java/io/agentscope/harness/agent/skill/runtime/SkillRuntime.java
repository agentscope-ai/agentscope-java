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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates the singleton {@link SkillLoadTool} and the {@link SkillPromptBuilder}. {@link
 * io.agentscope.harness.agent.middleware.HarnessSkillMiddleware} owns one instance and installs
 * each filtered {@link SkillCatalog} into that call's {@link RuntimeContext}.
 *
 * <p>The {@code load_skill_through_path} tool is prepared during agent construction and verified
 * idempotently on later installs. Normal tool calls resolve the catalog from their {@code
 * RuntimeContext}, so concurrent sessions cannot overwrite or observe one another's filtered skill
 * view. A separate compatibility catalog is retained only for deprecated, context-free callers.
 */
public final class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final RuntimeContext legacyContext = RuntimeContext.empty();
    private final AtomicReference<SkillCatalog> legacyCatalogRef =
            new AtomicReference<>(SkillCatalog.empty());
    private final SkillLoadTool loadTool;
    private final SkillPromptBuilder promptBuilder;

    public SkillRuntime() {
        this(new SkillPromptBuilder());
    }

    public SkillRuntime(SkillPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder != null ? promptBuilder : new SkillPromptBuilder();
        this.loadTool = new SkillLoadTool(legacyCatalogRef, false);
    }

    /**
     * Returns the catalog installed through the deprecated context-free API.
     *
     * @deprecated Use {@link #currentCatalog(RuntimeContext)} so catalog lookup is request-scoped.
     */
    @Deprecated(since = "2.2.0")
    public SkillCatalog currentCatalog() {
        return currentCatalog(legacyContext);
    }

    /** Returns the catalog bound to {@code context}, or an empty catalog when none is bound. */
    public SkillCatalog currentCatalog(RuntimeContext context) {
        return catalogFor(context);
    }

    static SkillCatalog catalogFor(RuntimeContext context) {
        if (context == null) {
            return SkillCatalog.empty();
        }
        SkillCatalog catalog = context.get(SkillCatalog.class);
        return catalog != null ? catalog : SkillCatalog.empty();
    }

    /**
     * Underlying stateless tool instance. Use {@link #install(SkillCatalog, RuntimeContext,
     * Toolkit)} for normal flow.
     */
    public AgentTool loadTool() {
        return loadTool;
    }

    /**
     * Ensures the skill loading tool is present as an ungrouped tool.
     *
     * <p>Harness calls this while the agent toolkit is still being assembled. The toolkit copy
     * made by {@code ReActAgent.Builder} therefore contains the tool before its first model call.
     * Keeping the loader ungrouped is intentional: ungrouped tools remain visible regardless of a
     * session's persisted active-group list, including sessions created before the loader existed.
     */
    public void prepareToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            return;
        }
        try {
            AgentTool existing = toolkit.getTool(SkillLoadTool.TOOL_NAME);
            if (existing == loadTool) {
                return;
            }
            if (existing != null) {
                toolkit.removeTool(SkillLoadTool.TOOL_NAME);
            }
            toolkit.registration().agentTool(loadTool).apply();
        } catch (Exception e) {
            log.warn("Failed to register {}: {}", SkillLoadTool.TOOL_NAME, e.getMessage());
        }
    }

    /**
     * Bind a catalog to the current call and ensure the load tool is registered on the toolkit
     * (idempotent).
     *
     * <p>The catalog is deliberately stored on {@code context}, not on this shared runtime. This is
     * the authorization boundary used by {@link SkillLoadTool}; a skill omitted from the call's
     * filtered catalog cannot be loaded even while another session exposes it.
     *
     * @param catalog the call snapshot; pass {@link SkillCatalog#empty()} to clear visibility
     * @param context the current call context; may be {@code null}, in which case no catalog is
     *     exposed
     * @param toolkit the toolkit to install onto; may be {@code null} (then only catalog is updated)
     */
    public void install(SkillCatalog catalog, RuntimeContext context, Toolkit toolkit) {
        if (context != null) {
            context.put(SkillCatalog.class, catalog != null ? catalog : SkillCatalog.empty());
        }
        prepareToolkit(toolkit);
    }

    /**
     * Installs a catalog for legacy context-free callers.
     *
     * <p>This overload is retained for source and binary compatibility. Its catalog is used only
     * when {@link SkillLoadTool} is invoked without a {@link RuntimeContext}; context-bearing calls
     * never fall back to it and therefore remain isolated.
     *
     * @deprecated Use {@link #install(SkillCatalog, RuntimeContext, Toolkit)}.
     */
    @Deprecated(since = "2.2.0")
    public void install(SkillCatalog catalog, Toolkit toolkit) {
        SkillCatalog normalized = catalog != null ? catalog : SkillCatalog.empty();
        legacyCatalogRef.set(normalized);
        install(normalized, legacyContext, toolkit);
    }

    /** Renders the {@code <available_skills>} block + (when applicable) the code-execution prompt. */
    public String renderPrompt(SkillCatalog catalog, SkillFilter filter) {
        return promptBuilder.render(
                catalog != null ? catalog : SkillCatalog.empty(),
                filter != null ? filter : SkillFilter.all());
    }
}
