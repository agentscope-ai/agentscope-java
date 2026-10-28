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
 * Shared JSON schema and invocation pipeline for the intent-based UI tool ({@code a2ui_render}):
 * the main agent submits a natural-language description and the render sub-agent turn produces the
 * component tree. Failures never throw out of the tool: they are returned as {@code error} results
 * so the ReAct loop can report or self-correct.
 */
final class A2uiIntentSchema {

    private A2uiIntentSchema() {}

    static Map<String, Object> intentSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "description",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Natural-language spec of the UI to render: purpose, sections,"
                                        + " fields and the data to show. The render model only"
                                        + " sees this text, so be complete."),
                        "context",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Optional supporting data the UI must display (records,"
                                        + " numbers, prior answers).")),
                "required",
                List.of("description"));
    }

    static Mono<ToolResultBlock> invokeRender(
            A2uiRenderer renderer, ToolCallParam param, String failurePrefix) {
        return Mono.defer(
                        () -> {
                            Map<String, Object> input = param.getInput();
                            String description =
                                    input == null ? null : asText(input.get("description"));
                            if (description == null || description.isBlank()) {
                                return Mono.just(
                                        ToolResultBlock.error(
                                                "`description` must be a non-blank"
                                                        + " natural-language UI spec."));
                            }
                            String context = input == null ? null : asText(input.get("context"));
                            return renderer.renderFromIntent(
                                            param.getRuntimeContext(), description, context)
                                    .map(ToolResultBlock::text);
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

    private static String asText(Object value) {
        return value instanceof String s ? s : null;
    }
}
