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
package io.agentscope.extensions.a2ui;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.extensions.a2ui.tool.AskUserQuestionTool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The A2UI capability as a single plain middleware (see the A2UI spec): attach with {@code
 * HarnessAgent.builder().middleware(new A2uiMiddleware(config))} — no framework-side seam.
 *
 * <p>Registers only the parent-facing tools {@code a2ui_render} (natural-language intent — the
 * single UI entry; one successful call delivers the surface) and {@code ask_user_question} with its
 * A2UI form flavour ({@code enableA2ui=true}; the plain clarification flavour lives on {@link
 * ClarificationMiddleware} and is independent of this toggle). A tool the host already binds under
 * one of these names is left alone (logged, not overwritten). The component DSL itself is
 * quarantined in the {@link A2uiRenderManager} sub-agent, whose throwaway toolkit carries only the
 * internal {@code a2ui_present} tree-submit tool — the catalog guide ships in that agent's SYSTEM
 * prompt, so the main model never sees the DSL at all.
 *
 * <p>The round is deliberately not stopped after a successful render: the model keeps the turn and
 * closes it itself, which costs one extra inference but avoids the extension depending on
 * framework-side acting-stop behaviour. {@link #onSystemPrompt} carries the constraints that keep
 * the delivered surface from being paraphrased back as text.
 *
 * <p>Uses only existing public surfaces: {@link #installTools} as the install point (the assembler
 * calls it once per agent build, after {@code build()}, against the agent's own deep-copied {@link
 * Toolkit} — the framework no longer callbacks into middleware to install tools), {@link #onAgent}
 * to capture the agent-resolved {@link AgentStateStore} and model lazily.
 *
 * <p>The render model is the one passed to {@link #A2uiMiddleware(A2uiConfig, Model)} — for a
 * lighter/cheaper UI model than the conversation model — or, when {@code null}, the hosting
 * agent's own model resolved at run time (same fallback shape as the harness memory config).
 *
 * <p>The catalog resource is loaded in the constructor — a missing/broken catalog fails fast at
 * the call site that builds the middleware. One instance is bound to one agent build; reusing the
 * same instance across agents installs per toolkit but shares the resolved state store, so prefer
 * one middleware per agent.
 */
public final class A2uiMiddleware implements MiddlewareBase, ToolInstallingMiddleware {

    private static final Logger logger = LoggerFactory.getLogger(A2uiMiddleware.class);

    private static final String SYSTEM_PROMPT_APPEND =
            "\n\n"
                + "## A2UI structured UI\n"
                + "- Show structured UI with `a2ui_render` — it is the only UI entry; it creates or"
                + " updates the surface from your request. Never hand-write A2UI envelope JSON in"
                + " reply text.\n"
                + "- The tool takes a natural-language `description` of the UI (plus optional"
                + " `context` data it must show); a dedicated render agent builds the component"
                + " tree — you never handle components or props yourself.\n"
                + "- `surfaceId` is managed by the system: never generate, guess, or modify it.\n"
                + "- Once `a2ui_render` succeeds, the surface is on screen: end your turn there"
                + " instead of restating it in words.\n"
                + "- Collect user input with `ask_user_question` instead of plain-text follow-up"
                + " questions.\n";

    private final A2uiConfig config;
    private final A2uiCatalog catalog;
    private final AtomicReference<AgentStateStore> stateStore = new AtomicReference<>();
    private final AtomicReference<Model> renderModel = new AtomicReference<>();

    public A2uiMiddleware() {
        this(A2uiConfig.defaults());
    }

    public A2uiMiddleware(A2uiConfig config) {
        this(config, null);
    }

    /**
     * @param renderModel model backing the render sub-agent; {@code null} falls back to the host
     *     agent's own model at run time.
     */
    public A2uiMiddleware(A2uiConfig config, Model renderModel) {
        this.config = config != null ? config : A2uiConfig.defaults();
        this.catalog = A2uiCatalog.load(this.config.catalogResource(), this.config.catalogId());
        this.renderModel.set(renderModel);
    }

    @Override
    public void installTools(Toolkit toolkit) {
        A2uiRenderer renderer =
                new A2uiRenderer(
                        config,
                        catalog,
                        new A2uiSurfaceRegistry(config, stateStore::get),
                        renderModel::get);
        installUnlessTaken(toolkit, new A2uiRenderTool(renderer));
        installUnlessTaken(toolkit, new AskUserQuestionTool(renderer, true));
    }

    /**
     * Registers an A2UI tool unless the toolkit already binds a foreign tool under the same name —
     * overwriting a host tool would silently remove behaviour the host depends on. A previous
     * binding of the same type is this capability's own, so it is replaced to rebind the fresh
     * renderer.
     */
    private static void installUnlessTaken(Toolkit toolkit, AgentTool tool) {
        AgentTool existing = toolkit.getTool(tool.getName());
        if (existing != null && !tool.getClass().isInstance(existing)) {
            logger.warn(
                    "Skip A2UI tool \"{}\": the toolkit already binds {} under that name",
                    tool.getName(),
                    existing.getClass().getName());
            return;
        }
        toolkit.registerTool(tool);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (agent instanceof ReActAgent react) {
            stateStore.compareAndSet(null, react.getStateStore());
            renderModel.compareAndSet(null, react.getModel());
        }
        return next.apply(input);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt + SYSTEM_PROMPT_APPEND + config.preferenceBlock());
    }
}
