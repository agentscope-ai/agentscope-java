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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool that generates a new subagent spec from a natural-language description and either persists
 * it to {@code subagents/<name>.md} (where {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware}
 * will pick it up on the next reasoning step) or returns the markdown for human review.
 *
 * <p>Wraps {@link SubagentSpecGenerator}; takes care of name validation, collision detection
 * against the live {@link DefaultAgentManager} registry, and dry-run handling.
 *
 * <p>Not registered by {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} by
 * default; callers must opt in via {@code SubagentsMiddleware#enableAgentGenerateTool(...)}.
 */
public class AgentGenerateTool {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerateTool.class);

    /** Subagent ids must be kebab-case identifiers; matches {@code AgentSpecLoader} expectations. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final SubagentSpecGenerator generator;
    private final DefaultAgentManager agentManager;
    private final AbstractFilesystem filesystem;

    public AgentGenerateTool(
            SubagentSpecGenerator generator,
            DefaultAgentManager agentManager,
            AbstractFilesystem filesystem) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.filesystem = filesystem;
    }

    @Tool(
            name = "agent_generate",
            description =
                    "Generate a new subagent spec from a natural-language description. Validates"
                        + " the LLM output against the SubagentDeclaration schema and writes it to"
                        + " subagents/<name>.md so DynamicSubagentsMiddleware picks it up on the"
                        + " next reasoning step. Use dry_run=true to preview the markdown without"
                        + " writing.")
    public Mono<String> agentGenerate(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "name",
                            description =
                                    "Kebab-case subagent id (e.g. code-reviewer). Must not"
                                            + " collide with an existing agent.")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "What the agent should do — its purpose, expected output, and"
                                            + " when to use it.")
                    String description,
            @ToolParam(
                            name = "dry_run",
                            description =
                                    "If true, return the generated markdown without persisting it"
                                            + " (default false).",
                            required = false)
                    Boolean dryRun) {

        if (name == null || name.isBlank()) {
            return Mono.just("Error: name is required");
        }
        String trimmed = name.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            return Mono.just(
                    "Error: name '"
                            + trimmed
                            + "' is not a valid kebab-case identifier (lowercase, digits, '-')");
        }
        if (description == null || description.isBlank()) {
            return Mono.just("Error: description is required");
        }
        if (agentManager.hasAgent(trimmed)) {
            return Mono.just("Error: agent '" + trimmed + "' already exists");
        }
        boolean dry = Boolean.TRUE.equals(dryRun);

        return generator
                .generateAndValidate(
                        description,
                        trimmed,
                        agentManager.getAgentFactories().keySet(),
                        agentManager.getWorkspaceManager() != null
                                ? agentManager.getWorkspaceManager().getWorkspace()
                                : null)
                .map(
                        spec -> {
                            if (dry) {
                                return "dry_run=true; spec below was NOT persisted\n\n"
                                        + spec.markdown();
                            }
                            if (filesystem == null) {
                                return "Error: no filesystem configured for AgentGenerateTool —"
                                        + " cannot persist spec. Use dry_run=true to preview.";
                            }
                            String path = "subagents/" + trimmed + ".md";
                            WriteResult wr =
                                    filesystem.write(runtimeContext, path, spec.markdown());
                            if (!wr.isSuccess()) {
                                log.warn(
                                        "Failed to write generated subagent spec to {}: {}",
                                        path,
                                        wr.error());
                                return "Error: write failed for "
                                        + path
                                        + ": "
                                        + wr.error()
                                        + "\n\nGenerated spec:\n"
                                        + spec.markdown();
                            }
                            return "Wrote subagent spec to " + wr.path() + "\n\n" + spec.markdown();
                        })
                .onErrorResume(
                        e -> {
                            String msg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            log.warn("agent_generate failed for name={}: {}", trimmed, msg);
                            return Mono.just("Error: " + msg);
                        });
    }
}
