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
package io.agentscope.extensions.a2ui.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.a2ui.A2uiRenderer;
import io.agentscope.extensions.a2ui.envelope.A2uiValidationException;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Shared JSON schema and invocation pipeline for the component-tree submission used inside the
 * A2UI render sub-agent (see {@link A2uiTreeRenderTool}). Validation failures never throw out of
 * the tool: they are returned as {@code error} results so the child's ReAct loop self-corrects.
 */
final class A2uiComponentSchema {

    private A2uiComponentSchema() {}

    /**
     * Deliberately loose on the tree's shape: the framework validates tool arguments against this
     * schema before we see them, so a typed array here would reject the nested {@code root /
     * children} form that some models produce. The shape is accepted first and then checked against
     * the catalog by {@link io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator}, whose
     * errors the child self-corrects against.
     */
    static Map<String, Object> componentsSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "components",
                        Map.of(
                                "description",
                                "The A2UI tree: prefer a flat array of {id, component, props}"
                                        + " objects per the component catalog. A nested"
                                        + " {root: {component, props, children: [...]}} tree is"
                                        + " also accepted and flattened; ids are generated when"
                                        + " omitted.")),
                "required",
                List.of("components"));
    }

    static Mono<ToolResultBlock> invokeRender(
            A2uiRenderer renderer, ToolCallParam param, String failurePrefix) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> input = param.getInput();
                            List<Map<String, Object>> components =
                                    A2uiRenderer.componentsOf(
                                            input == null ? null : input.get("components"));
                            return ToolResultBlock.text(
                                    renderer.renderEnvelope(param.getRuntimeContext(), components));
                        })
                .onErrorResume(
                        Exception.class,
                        e -> {
                            if (e instanceof A2uiValidationException) {
                                return Mono.just(
                                        ToolResultBlock.error("Invalid A2UI: " + e.getMessage()));
                            }
                            return Mono.just(ToolResultBlock.error(failurePrefix + e.getMessage()));
                        });
    }
}
