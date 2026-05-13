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

import java.nio.file.Path;
import java.util.List;

/**
 * Declares a subagent: its identity, workspace resolution strategy, and optional capability
 * allowlist.
 *
 * <p>A declaration binds to exactly one of two <em>source modes</em>:
 *
 * <ol>
 *   <li><b>Definition workspace</b> — {@link #getWorkspacePath()} points to a workspace directory
 *       containing at least {@code AGENTS.md}. That file is used as the subagent's system-prompt
 *       body. Skills, knowledge, and MEMORY in the definition directory are available when the
 *       {@link WorkspaceMode} is {@link WorkspaceMode#ISOLATED}.
 *   <li><b>Inline</b> — no external workspace; {@link #getInlineAgentsBody()} is the system-prompt
 *       body directly (equivalent to writing the body in a {@code subagents/&lt;name&gt;.md} file
 *       with no {@code workspace.path} in the front matter).
 * </ol>
 *
 * <p>The two source modes are mutually exclusive: setting both {@link Builder#workspace(Path)} and
 * a non-blank {@link Builder#inlineAgentsBody(String)} is rejected at build time.
 *
 * <p>Workspace resolution follows the five-row decision table in {@link WorkspaceMode}.
 *
 * <p>The {@code tools} list, when non-empty, acts as an <em>allowlist filter</em>: only tools
 * whose names appear in the list are kept on the subagent's toolkit. It cannot add tools that the
 * parent agent does not have.
 *
 * <p>Obtain instances via {@link #builder()}.
 *
 * <p>Example (programmatic):
 *
 * <pre>{@code
 * SubagentDeclaration decl = SubagentDeclaration.builder()
 *     .name("code-reviewer")
 *     .description("Reviews code for security, performance, and readability issues.")
 *     .workspace(Path.of("./defs/code-reviewer"))
 *     .workspaceMode(WorkspaceMode.ISOLATED)
 *     .model("qwen3-max")
 *     .tools(List.of("read_file", "grep_files", "edit_file"))
 *     .build();
 * }</pre>
 */
public final class SubagentDeclaration {

    private final String name;
    private final String description;
    private final WorkspaceMode workspaceMode;
    private final Path workspacePath;
    private final String inlineAgentsBody;
    private final String model;
    private final int maxIters;
    private final List<String> tools;

    private SubagentDeclaration(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.workspaceMode = b.workspaceMode;
        this.workspacePath = b.workspacePath;
        this.inlineAgentsBody = b.inlineAgentsBody;
        this.model = b.model;
        this.maxIters = b.maxIters;
        this.tools = b.tools != null ? List.copyOf(b.tools) : List.of();
    }

    /** Factory method for a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Unique name / agent-id used to reference this subagent. */
    public String getName() {
        return name;
    }

    /** Human-readable description; the main agent uses this to decide when to delegate. */
    public String getDescription() {
        return description;
    }

    /**
     * Workspace resolution strategy. Defaults to {@link WorkspaceMode#ISOLATED} when not
     * specified.
     */
    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    /**
     * Path to the definition workspace directory (contains at least {@code AGENTS.md}). When
     * {@code null} this declaration is in inline mode and {@link #getInlineAgentsBody()} provides
     * the system prompt.
     */
    public Path getWorkspacePath() {
        return workspacePath;
    }

    /**
     * Inline system-prompt body used when {@link #getWorkspacePath()} is {@code null}. May be
     * {@code null} or blank if neither a definition workspace nor an inline body is provided.
     */
    public String getInlineAgentsBody() {
        return inlineAgentsBody;
    }

    /**
     * Optional model override (e.g. {@code "qwen3-max"} or {@code "openai:gpt-4o-mini"}). When
     * {@code null} or blank, the parent model is used.
     */
    public String getModel() {
        return model;
    }

    /** Maximum reasoning iterations. Defaults to 10. */
    public int getMaxIters() {
        return maxIters;
    }

    /**
     * Optional tool allowlist. When non-empty, only the listed tool names remain on the subagent's
     * toolkit. Empty means inherit all tools from the parent configuration.
     */
    public List<String> getTools() {
        return tools;
    }

    /** Returns {@code true} when this declaration points at an external definition workspace. */
    public boolean hasDefinitionWorkspace() {
        return workspacePath != null;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        private String name;
        private String description;
        private WorkspaceMode workspaceMode = WorkspaceMode.ISOLATED;
        private Path workspacePath;
        private String inlineAgentsBody;
        private String model;
        private int maxIters = 10;
        private List<String> tools;

        private Builder() {}

        /** Sets the unique name / agent-id for this subagent (required). */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the human-readable description the orchestrator uses to decide when to delegate
         * (required).
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the workspace resolution mode. Defaults to {@link WorkspaceMode#ISOLATED}.
         *
         * @param mode workspace mode; {@code null} is treated as {@link WorkspaceMode#ISOLATED}
         */
        public Builder workspaceMode(WorkspaceMode mode) {
            this.workspaceMode = mode != null ? mode : WorkspaceMode.ISOLATED;
            return this;
        }

        /**
         * Points this declaration at an external definition workspace.
         *
         * <p>Mutually exclusive with {@link #inlineAgentsBody(String)}: passing both a non-null
         * path <em>and</em> a non-blank inline body will cause {@link #build()} to throw.
         *
         * @param workspacePath absolute path, or path relative to {@code mainWorkspace} when set
         *     via a Markdown front matter file
         */
        public Builder workspace(Path workspacePath) {
            this.workspacePath = workspacePath;
            return this;
        }

        /**
         * Sets the inline system-prompt body for lightweight subagents that do not need a
         * dedicated definition workspace.
         *
         * <p>Mutually exclusive with {@link #workspace(Path)}.
         *
         * @param body the system-prompt body text (Markdown); may be {@code null} or blank
         */
        public Builder inlineAgentsBody(String body) {
            this.inlineAgentsBody = body;
            return this;
        }

        /**
         * Optional model override resolved via {@link io.agentscope.core.model.ModelRegistry}.
         * Falls back to the parent model when blank or unresolvable.
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Maximum reasoning iterations (default 10). */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * Tool allowlist: when non-empty, only the listed tool names are kept on the subagent's
         * toolkit. Cannot grant tools that the parent does not have.
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * Builds the {@link SubagentDeclaration}.
         *
         * @throws IllegalArgumentException if {@code name} or {@code description} is blank, or
         *     both {@code workspace(Path)} and a non-blank {@code inlineAgentsBody()} are set
         */
        public SubagentDeclaration build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SubagentDeclaration requires a non-blank name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "SubagentDeclaration requires a non-blank description");
            }
            if (workspacePath != null && inlineAgentsBody != null && !inlineAgentsBody.isBlank()) {
                throw new IllegalArgumentException(
                        "workspace(Path) and inlineAgentsBody() are mutually exclusive;"
                                + " set at most one for subagent '"
                                + name
                                + "'");
            }
            return new SubagentDeclaration(this);
        }
    }
}
