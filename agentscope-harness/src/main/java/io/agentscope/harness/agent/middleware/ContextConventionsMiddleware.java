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
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Framework-owned, domain-neutral context instructions, loaded once from a versioned resource. */
public final class ContextConventionsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ContextConventionsMiddleware.class);
    private static final String INSTRUCTIONS = load();

    private static String load() {
        try (var stream =
                ContextConventionsMiddleware.class.getResourceAsStream(
                        "/io/agentscope/harness/context-conventions.md")) {
            if (stream == null)
                throw new IllegalStateException("Missing context conventions resource");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load context conventions", error);
        }
    }

    /**
     * Runs before user middlewares: the per-call materials reset must happen
     * before any user {@code WorkspaceContextMaterials.register(...)}, or those
     * registrations are wiped by the reset (#3829).
     */
    @Override
    public int order() {
        return Integer.MAX_VALUE - 1000;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromSupplier(
                () -> {
                    // Installed before the other framework providers: every agent call gets a
                    // fresh snapshot, even when the caller reuses a RuntimeContext or a child
                    // inherits one. Disabled providers must not retain a previous agent's data.
                    RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
                    // The reset intentionally drops a previous call's leftovers — but if a
                    // non-framework item is present here it was registered by another
                    // middleware that ran before us (e.g. registration order placed it
                    // ahead, or a yet-lower order); warn instead of silently discarding so
                    // the loss is diagnosable (#3829).
                    var previousMaterials = rc.get(WorkspaceContextMaterials.class);
                    if (previousMaterials != null) {
                        previousMaterials.items().stream()
                                .filter(item -> !item.sourceId().startsWith("harness:"))
                                .forEach(
                                        item ->
                                                LOGGER.warn(
                                                        "Discarding non-framework context item"
                                                                + " '{}' registered before the"
                                                                + " per-call materials reset; use"
                                                                + " a lower order() (< {}) so"
                                                                + " this middleware runs after"
                                                                + " the reset and the item"
                                                                + " survives",
                                                        item.sourceId(),
                                                        order()));
                    }
                    rc.put(
                            WorkspaceContextMaterials.class,
                            new WorkspaceContextMaterials(
                                    List.of(
                                            ContextItem.instruction(
                                                    "instruction_rules",
                                                    "harness:context-conventions",
                                                    INSTRUCTIONS))));
                    return prompt == null ? "" : prompt;
                });
    }
}
