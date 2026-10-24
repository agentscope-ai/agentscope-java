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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiValidationException;
import io.agentscope.extensions.a2ui.tool.A2uiCatalogTool;
import io.agentscope.extensions.a2ui.tool.A2uiTreeRenderTool;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * The A2UI render sub-agent: spins up a dedicated, throwaway {@link ReActAgent} per tool call that
 * owns the UI DSL — it submits component trees through the internal {@code a2ui_present} tool and
 * self-corrects against validation results inside its own ReAct loop — so the main agent only ever
 * speaks natural language. Same "agent-as-tool" shape as the core {@code SubAgentTool} (fresh agent
 * per call, {@code interrupt(ctx)} on cancellation).
 *
 * <p>The catalog guide is composed into this agent's SYSTEM prompt at build time ({@link
 * A2uiConfig#catalogInPrompt()}), which is what makes it survive the validation-retry rounds
 * without re-fetching; the render manager is constructed once per agent build and its prompt is
 * immutable, so no framework hook is needed for the "inject before every reasoning round" effect.
 * With {@code catalogInPrompt} off the child instead reads the guide from {@code a2ui_catalog} at
 * the cost of one iteration.
 *
 * <p>The accepted envelope (produced by the shared {@link A2uiRenderer} pipeline inside the child's
 * submit tool) is captured through an {@link AtomicReference} and returned as this call's result;
 * the child's reply text is never parsed. The child gets its own bare {@link Toolkit} —
 * {@link A2uiMiddleware} is NOT attached to it, which is what keeps the capability from recursing.
 */
public final class A2uiRenderManager {

    /**
     * Default SYSTEM prompt of the render sub-agent. Exposed publicly so callers can extend
     * (e.g. append product-specific style rules) via {@link A2uiConfig.Builder#renderPrompt}. The
     * manager appends the catalog guide (or the {@code a2ui_catalog} load instruction, per {@link
     * A2uiConfig#catalogInPrompt()}) and the user preference block to whichever prompt it uses.
     */
    public static final String DEFAULT_RENDER_PROMPT =
            """
            You are a dedicated A2UI interface generator. You receive a natural-language \
            description of the UI the user should see, and you deliver it as a structured A2UI \
            surface.

            Workflow (strict):
            1. Design the smallest UI that fulfills the request and submit it by calling \
            `a2ui_present` with a `components` array of {id, component, props} objects.
            2. If `a2ui_present` reports validation errors, correct the tree and resubmit.
            3. After a successful submission, stop and reply with exactly: done

            Rules:
            - The only accepted channel for UI is the `a2ui_present` tool — never write A2UI JSON \
            in reply text.
            - Use only the components and props listed in the component catalog.
            - Put all content (headings, texts, options, data) directly into props; nobody sees \
            this conversation.
            - Prefer read-only components for information and form components only to collect \
            input; use Row/Column composition as the catalog describes.\
            """;

    private final A2uiConfig config;
    private final A2uiCatalog catalog;
    private final Model model;
    private final A2uiRenderer renderer;
    private final String renderPrompt;

    public A2uiRenderManager(
            A2uiConfig config, A2uiCatalog catalog, Model model, A2uiRenderer renderer) {
        this(config, catalog, model, renderer, config.renderPrompt());
    }

    /**
     * @param renderPrompt SYSTEM prompt for the render sub-agent; {@code null} falls back to
     *     {@link #DEFAULT_RENDER_PROMPT}.
     */
    public A2uiRenderManager(
            A2uiConfig config,
            A2uiCatalog catalog,
            Model model,
            A2uiRenderer renderer,
            String renderPrompt) {
        this.config = config;
        this.catalog = catalog;
        this.model = model;
        this.renderer = renderer;
        this.renderPrompt = catalogBlock(renderPrompt, catalog, config) + config.preferenceBlock();
    }

    /** The render sub-agent's base SYSTEM prompt plus the catalog block it needs to submit. */
    private static String catalogBlock(
            String renderPrompt, A2uiCatalog catalog, A2uiConfig config) {
        String base = renderPrompt != null ? renderPrompt : DEFAULT_RENDER_PROMPT;
        return config.catalogInPrompt()
                ? base + "\n" + catalog.guideText()
                : base
                        + "\n\nComponent catalog: call `a2ui_catalog` once, before your first"
                        + " `a2ui_present` submission, to load it.\n";
    }

    /** Effective SYSTEM prompt of the render sub-agent (base prompt + catalog + preference). */
    String renderPrompt() {
        return renderPrompt;
    }

    /**
     * Runs the render sub-agent for one intent.
     *
     * @return Mono of the A2UI envelope JSON the child submitted; fails with
     *     {@link A2uiValidationException} when the child ended without an accepted submission.
     */
    public Mono<String> generate(
            RuntimeContext runtimeContext, String description, String context) {
        return Mono.defer(
                () -> {
                    AtomicReference<String> submitted = new AtomicReference<>();
                    Toolkit toolkit = new Toolkit();
                    if (!config.catalogInPrompt()) {
                        toolkit.registerTool(new A2uiCatalogTool(catalog));
                    }
                    toolkit.registerTool(new A2uiTreeRenderTool(renderer, submitted::set));
                    ReActAgent renderAgent =
                            ReActAgent.builder()
                                    .name("a2ui_render_agent")
                                    .description("Dedicated A2UI interface generator")
                                    .sysPrompt(renderPrompt)
                                    .model(model)
                                    .toolkit(toolkit)
                                    .maxIters(config.renderMaxIters())
                                    .build();
                    Msg userMsg =
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(
                                            TextBlock.builder()
                                                    .text(userPrompt(description, context))
                                                    .build())
                                    .build();
                    return renderAgent
                            .call(List.of(userMsg), runtimeContext)
                            .flatMap(
                                    reply -> {
                                        String envelope = submitted.get();
                                        if (envelope == null) {
                                            String lastWords =
                                                    reply.getTextContent() == null
                                                            ? ""
                                                            : reply.getTextContent();
                                            if (lastWords.length() > 300) {
                                                lastWords = lastWords.substring(0, 300) + "...";
                                            }
                                            return Mono.error(
                                                    new A2uiValidationException(
                                                            "the A2UI render agent ended without"
                                                                    + " an accepted component"
                                                                    + " submission"
                                                                    + (lastWords.isBlank()
                                                                            ? ""
                                                                            : "; its last reply"
                                                                                    + " was: "
                                                                                    + lastWords)));
                                        }
                                        return Mono.just(envelope);
                                    })
                            // Same orphan-agent guard as SubAgentTool: a cancelled subscription
                            // (e.g. timeout retry) must not leave the child burning tokens.
                            .doFinally(
                                    signal -> {
                                        if (signal == SignalType.CANCEL) {
                                            renderAgent.interrupt(runtimeContext);
                                        }
                                    });
                });
    }

    private static String userPrompt(String description, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build the A2UI for this request:\n").append(description);
        if (context != null && !context.isBlank()) {
            sb.append("\n\nSupporting context:\n").append(context);
        }
        return sb.toString();
    }
}
