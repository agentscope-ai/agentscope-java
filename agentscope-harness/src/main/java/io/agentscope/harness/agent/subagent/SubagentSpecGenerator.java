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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * LLM-driven generator for {@link SubagentDeclaration} markdown specs.
 *
 * <p>Given a free-form description of an agent's purpose, prompts the supplied {@link Model} to
 * emit a markdown document with YAML frontmatter conforming to {@link AgentSpecLoader}'s schema.
 * The output is parseable round-trip via {@link AgentSpecLoader#parse}.
 *
 * <p>This is the Java analogue of opencode's {@code Agent.generate} flow ({@code
 * opencode/packages/opencode/src/agent/agent.ts}). Intended to be called from a CLI scaffolder or
 * from {@link io.agentscope.harness.agent.tool.AgentGenerateTool} during a live session.
 *
 * <p>Reuses the same {@code model.stream(messages, null, null).reduce(...)} pattern used by
 * {@link io.agentscope.harness.agent.memory.compaction.ConversationCompactor} for one-shot LLM
 * calls, so behaviour stays consistent with other internal LLM users in the harness.
 */
public final class SubagentSpecGenerator {

    private static final String PROMPT_TEMPLATE =
            """
            You are designing a subagent specification for the agentscope-java framework.

            User description of the agent's purpose:
            ---
            %s
            ---

            Existing agent ids (DO NOT reuse, even with different casing): %s

            Produce a Markdown document with YAML frontmatter that matches this schema:

            ---
            description: <one sentence describing when the orchestrator should delegate to this agent>
            mode: subagent
            hidden: false
            # Optional model hyperparameters (omit to inherit parent):
            # temperature: <0.0..2.0>
            # top_p: <0.0..1.0>
            # steps: <positive integer, default 10>
            # tools: [<inherited tool name>, ...]   # optional allowlist; empty = inherit all
            ---

            <System-prompt body in Markdown: describe the agent's role, capabilities, output
             format, and constraints. Keep the body focused — this becomes the subagent's sysPrompt.>

            Output rules:
            - Return ONLY the markdown document. No prose, no code fences, no commentary.
            - Do NOT include a `name:` field — the caller derives the name from the filename.
            - The description must be at most 200 characters.
            - The body must not be empty when no workspace.path is specified.
            """;

    private final Model model;

    public SubagentSpecGenerator(Model model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Generates a markdown spec for a new subagent without validation. Prefer
     * {@link #generateAndValidate} unless you need to inspect or massage the markdown before it
     * goes through {@link AgentSpecLoader}.
     *
     * @param description free-form description of what the agent should do
     * @param existingIds names already registered; the model is told to avoid these
     * @return raw markdown returned by the model (trimmed; otherwise unprocessed)
     */
    public Mono<String> generateMarkdown(String description, Collection<String> existingIds) {
        String existingList =
                existingIds == null || existingIds.isEmpty()
                        ? "(none)"
                        : String.join(", ", existingIds);
        String prompt = String.format(PROMPT_TEMPLATE, description, existingList);
        List<Msg> input =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());
        return model.stream(input, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            if (resp.getContent() != null) {
                                for (ContentBlock block : resp.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .map(String::strip);
    }

    /**
     * Generates a markdown spec and validates it by round-tripping through
     * {@link AgentSpecLoader#parse}. Emits an {@link IllegalStateException} if the LLM output is
     * malformed (missing frontmatter terminator, missing required {@code description}, etc.).
     *
     * @param description free-form description
     * @param agentName name to assign the parsed declaration (also reserved against
     *     {@code existingIds})
     * @param existingIds existing agent ids
     * @param mainWorkspace workspace root for resolving any relative {@code workspace.path}; may
     *     be {@code null}
     * @return tuple of the raw markdown and the parsed {@link SubagentDeclaration}
     */
    public Mono<GeneratedSpec> generateAndValidate(
            String description,
            String agentName,
            Collection<String> existingIds,
            Path mainWorkspace) {
        return generateMarkdown(description, existingIds)
                .map(
                        md -> {
                            SubagentDeclaration decl =
                                    AgentSpecLoader.parse(md, agentName, mainWorkspace);
                            if (decl == null) {
                                throw new IllegalStateException(
                                        "LLM produced a malformed subagent spec for '"
                                                + agentName
                                                + "'; could not parse frontmatter");
                            }
                            return new GeneratedSpec(md, decl);
                        });
    }

    /** Pair of the raw markdown and the parsed {@link SubagentDeclaration}. */
    public record GeneratedSpec(String markdown, SubagentDeclaration declaration) {}
}
