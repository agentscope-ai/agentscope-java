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
import reactor.core.publisher.Mono;

/** Framework-owned, domain-neutral context instructions, loaded once from a versioned resource. */
public final class ContextConventionsMiddleware implements HarnessRuntimeMiddleware {
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

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromSupplier(
                () -> {
                    // Installed before the other framework providers: every agent call gets a
                    // fresh snapshot, even when the caller reuses a RuntimeContext or a child
                    // inherits one. Disabled providers must not retain a previous agent's data.
                    RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
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
