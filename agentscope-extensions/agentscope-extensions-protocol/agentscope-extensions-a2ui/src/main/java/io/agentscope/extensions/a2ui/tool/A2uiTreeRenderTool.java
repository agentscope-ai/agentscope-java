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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.a2ui.A2uiRenderer;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Internal component-tree tool, registered ONLY on the A2UI render sub-agent's toolkit (see
 * {@link io.agentscope.extensions.a2ui.A2uiRenderManager}): the child submits {id, component,
 * props} trees here, validation failures come back as error results for in-loop self-correction,
 * and an accepted envelope is additionally captured into {@code envelopeSink} so the outer intent
 * tool can return it verbatim without parsing the child's reply text.
 *
 * <p>Deliberately named {@code a2ui_present}, not the parent's {@code a2ui_render}: sharing one
 * name made parent/child events and validation errors indistinguishable in logs and in the stop
 * hook, which matches tool results by name.
 */
public class A2uiTreeRenderTool implements AgentTool {

    private final A2uiRenderer renderer;
    private final Consumer<String> envelopeSink;

    public A2uiTreeRenderTool(A2uiRenderer renderer, Consumer<String> envelopeSink) {
        this.renderer = renderer;
        this.envelopeSink = envelopeSink;
    }

    @Override
    public String getName() {
        return A2uiConstants.TOOL_PRESENT;
    }

    @Override
    public String getDescription() {
        return "Submit the A2UI component tree as {id, component, props} objects per the"
                + " component catalog. Fix reported validation errors and resubmit until it is"
                + " accepted; a successful call delivers the UI to the user.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return A2uiComponentSchema.componentsSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return A2uiComponentSchema.invokeRender(renderer, param, "A2UI render failed: ")
                .doOnNext(
                        result -> {
                            // An accepted call is the only result whose text IS the envelope JSON;
                            // error results are "Error: ..." prose. (ToolResultBlock.text() keeps
                            // the default RUNNING state, so the shape check is the marker.)
                            String text = textOf(result);
                            if (text != null && text.startsWith("{")) {
                                envelopeSink.accept(text);
                            }
                        });
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(output -> ((TextBlock) output).getText())
                .collect(Collectors.joining());
    }
}
