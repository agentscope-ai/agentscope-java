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
     * {@code components} keeps an explicit array type on purpose: with an untyped property the
     * provider hands us the value as a JSON <em>string</em>, and every submission then fails the
     * catalog check (observed in production right after this was loosened). Nested trees are
     * tolerated where they cost nothing — {@code children} is an ordinary item property and {@code
     * id} is optional, because {@link io.agentscope.extensions.a2ui.envelope.A2uiTreeNormalizer}
     * generates missing ids.
     */
    static Map<String, Object> componentsSchema() {
        Map<String, Object> item =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "id",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Unique id within this submission; generated when"
                                                + " omitted."),
                                "component",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Component name from the" + " catalog."),
                                "props",
                                Map.of(
                                        "type",
                                        "object",
                                        "description",
                                        "Component properties per catalog entry. Props written"
                                                + " directly beside `component` are collected the"
                                                + " same way."),
                                "children",
                                Map.of(
                                        "type",
                                        "array",
                                        "description",
                                        "Nested child components; the tree is flattened before"
                                                + " validation.")),
                        "required",
                        List.of("component"));
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "components",
                        Map.of(
                                "type",
                                "array",
                                "description",
                                "The A2UI tree as a list of {component, props} objects per the"
                                        + " component catalog; `children` nests sub-components and"
                                        + " is flattened here.",
                                "items",
                                item)),
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
