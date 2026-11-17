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
     * catalog check (observed in production right after this was loosened). The same applies to each
     * {@code oneOf} branch below.
     *
     * <p>The declared shape is the official one — properties beside {@code component}, children as
     * id references — while remaining tolerant where {@link
     * io.agentscope.extensions.a2ui.envelope.A2uiTreeNormalizer} can repair the submission: a {@code
     * props} object, inline child objects and missing ids all still pass, because undeclared
     * properties are allowed and only the emitted envelope has to be strict.
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
                                        "Unique id within this submission; the root component"
                                                + " must use id \"root\". Generated when omitted."),
                                "component",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Component name from the catalog."),
                                "child",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Id of the single child component (Card, Button,"
                                                + " Modal)."),
                                "children",
                                Map.of(
                                        "description",
                                        "Ids of the child components in order, or a template"
                                                + " object generating them from a data-model"
                                                + " list.",
                                        "oneOf",
                                        List.of(
                                                Map.of("type", "array"),
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of(
                                                                "path",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "JSON Pointer to the list"
                                                                                + " in the data"
                                                                                + " model."),
                                                                "componentId",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "Id of the component"
                                                                                + " used as the"
                                                                                + " template.")),
                                                        "required",
                                                        List.of("path", "componentId"))))),
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
                                "The surface as a flat list of {id, component, ...props} objects"
                                        + " per the component catalog. Properties sit directly"
                                        + " beside `component`; children are referenced by id, and"
                                        + " one entry must have id \"root\".",
                                "items",
                                item),
                        "dataModel",
                        Map.of(
                                "type",
                                "object",
                                "description",
                                "Optional initial data model the components bind to via"
                                        + " {\"@path\": \"/key\"}. Omit it when every value is"
                                        + " literal.")),
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
                                    renderer.renderEnvelope(
                                            param.getRuntimeContext(),
                                            components,
                                            dataModelOf(input)));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataModelOf(Map<String, Object> input) {
        Object value = input == null ? null : input.get("dataModel");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
