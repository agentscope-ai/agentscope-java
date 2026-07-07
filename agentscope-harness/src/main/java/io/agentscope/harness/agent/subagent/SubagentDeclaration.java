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
import java.util.Map;

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
 *   <li><b>Remote HTTP</b> — {@link #getUrl()} points to an AgentScope task HTTP server. No local
 *       definition workspace or inline body; the subagent runs out-of-process. Mutually exclusive
 *       with definition workspace and inline body.
 * </ol>
 *
 * <p>The three source modes are mutually exclusive: at most one of {@link Builder#workspace(Path)},
 * a non-blank {@link Builder#inlineAgentsBody(String)}, or a non-blank {@link Builder#url(String)}
 * may be set.
 *
 * <p>Workspace resolution follows the five-row decision table in {@link WorkspaceMode}.
 *
 * <p>The {@code tools} list, when non-empty, acts as an <em>allowlist filter</em> for inherited
 * parent tools: only inherited tools whose names appear in the list are kept. Child-local tool
 * registrations may still be added by the child builder.
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

    /**
     * Whether a declaration can be used as a top-level primary agent, only as a delegated
     * subagent, or both.
     *
     * <p>The {@link io.agentscope.harness.agent.subagent.DefaultAgentManager#createAgentIfPresent}
     * path rejects spawn requests for {@link #PRIMARY}-only declarations so they can never be
     * invoked as workers; conversely, top-level launchers may want to reject {@link #SUBAGENT}
     * declarations as entry points (current core does not own that check — top-level launch goes
     * through {@code HarnessAgent.builder()} directly, not through a declaration).
     */
    public enum Mode {
        PRIMARY,
        SUBAGENT,
        ALL
    }

    private final String name;
    private final String description;
    private final WorkspaceMode workspaceMode;
    private final Path workspacePath;
    private final String inlineAgentsBody;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final String variant;
    private final int steps;
    private final Mode mode;
    private final boolean hidden;
    private final boolean persistSession;
    private final boolean inheritParentPermissions;
    private final Boolean exposeToUser;
    private final List<String> tools;
    private final List<String> skills;

    /** Base URL of the remote task server (e.g. {@code http://host:8080}). */
    private final String url;

    private final Map<String, String> headers;

    private SubagentDeclaration(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.workspaceMode = b.workspaceMode;
        this.workspacePath = b.workspacePath;
        this.inlineAgentsBody = b.inlineAgentsBody;
        this.model = b.model;
        this.temperature = b.temperature;
        this.topP = b.topP;
        this.variant = b.variant;
        this.steps = b.steps;
        this.mode = b.mode != null ? b.mode : Mode.ALL;
        this.hidden = b.hidden;
        this.persistSession = b.persistSession;
        this.inheritParentPermissions = b.inheritParentPermissions;
        this.exposeToUser = b.exposeToUser;
        this.tools = b.tools != null ? List.copyOf(b.tools) : List.of();
        this.skills = b.skills != null ? List.copyOf(b.skills) : List.of();
        this.url = b.url;
        this.headers = b.headers != null && !b.headers.isEmpty() ? Map.copyOf(b.headers) : null;
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

    /**
     * Maximum reasoning iterations. Defaults to 10.
     *
     * @deprecated since Phase A — use {@link #getSteps()}. Returns the same value; kept for source
     *     compatibility with callers built before the {@code steps} field existed.
     */
    @Deprecated
    public int getMaxIters() {
        return steps;
    }

    /** Maximum reasoning iterations (default 10). Replaces the historical {@code maxIters} field. */
    public int getSteps() {
        return steps;
    }

    /**
     * Optional sampling temperature override (e.g. {@code 0.0} for deterministic compaction-like
     * tasks, {@code 0.7} for creative generation). When {@code null}, the parent's
     * {@link io.agentscope.core.model.GenerateOptions#getTemperature()} applies unchanged.
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Optional nucleus-sampling override. When {@code null}, the parent's
     * {@link io.agentscope.core.model.GenerateOptions#getTopP()} applies unchanged.
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * Optional model variant identifier (e.g. {@code "thinking"} for DashScope thinking-mode
     * variants). When {@code null} or blank, no variant transform is applied; the parent's
     * variant — if any — is inherited via builder copy.
     */
    public String getVariant() {
        return variant;
    }

    /**
     * The {@link Mode} of this declaration. Defaults to {@link Mode#ALL} when not specified —
     * both spawnable and primary-capable.
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Whether this declaration should be hidden from the LLM's view of available subagents.
     * Used for internal subagents (e.g. compaction, summary, title) that the orchestrator should
     * not directly delegate to.
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Whether the subagent's session state should persist across parent calls. When {@code true},
     * the spawn key is derived deterministically from (parentSessionId, agentId, label), enabling
     * state recovery after process restarts. When {@code false} (default), a random UUID is used.
     */
    public boolean isPersistSession() {
        return persistSession;
    }

    /**
     * Whether the subagent inherits parent DENY permission rules. When {@code true} (default),
     * all DENY rules from the parent's permission context are propagated to the child's permission
     * engine at spawn time, preventing the child from circumventing parent-level restrictions.
     */
    public boolean isInheritParentPermissions() {
        return inheritParentPermissions;
    }

    /**
     * Per-type policy for exposing spawned instances of this subagent as user-addressable threads.
     *
     * <p>Tri-state:
     *
     * <ul>
     *   <li>{@code TRUE} — always expose, regardless of what the LLM requests on {@code agent_spawn}
     *   <li>{@code FALSE} — never expose (hard opt-out), overriding an LLM {@code expose_to_user=true}
     *   <li>{@code null} (default) — no opinion; defer to the per-call {@code RuntimeContext} override
     *       and then the LLM's {@code expose_to_user} argument
     * </ul>
     *
     * <p>This is overridden at runtime by a {@code RuntimeContext} value keyed
     * {@code AgentSpawnTool#CTX_EXPOSE_TO_USER}. See {@code AgentSpawnTool} for the full
     * resolution precedence.
     */
    public Boolean getExposeToUser() {
        return exposeToUser;
    }

    /**
     * Optional tool allowlist. When non-empty, only inherited parent tools whose names are listed
     * remain on the subagent's inherited toolkit. Empty means inherit all parent tools.
     */
    public List<String> getTools() {
        return tools;
    }

    public List<String> getSkills() {
        return skills;
    }

    /** Returns {@code true} when this declaration targets a remote task HTTP server. */
    public boolean isRemote() {
        return url != null && !url.isBlank();
    }

    /**
     * Base URL of the remote task server. Non-blank only in {@linkplain #isRemote() remote} mode.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Optional HTTP headers (e.g. auth) sent to the remote task server. Never empty when
     * non-null.
     */
    public Map<String, String> getHeaders() {
        return headers;
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
        private Double temperature;
        private Double topP;
        private String variant;
        private int steps = 10;
        private Mode mode = Mode.ALL;
        private boolean hidden = false;
        private boolean persistSession = false;
        private boolean inheritParentPermissions = true;
        private Boolean exposeToUser;
        private List<String> tools;
        private List<String> skills;
        private String url;
        private Map<String, String> headers;

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

        /**
         * Maximum reasoning iterations (default 10).
         *
         * @deprecated since Phase A — use {@link #steps(int)}. Equivalent in behaviour.
         */
        @Deprecated
        public Builder maxIters(int maxIters) {
            this.steps = maxIters;
            return this;
        }

        /** Maximum reasoning iterations (default 10). */
        public Builder steps(int steps) {
            this.steps = steps;
            return this;
        }

        /**
         * Optional sampling temperature override. {@code null} (default) means inherit the parent
         * agent's value. Typical range {@code 0.0 – 2.0}.
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Optional nucleus-sampling (top-p) override. {@code null} (default) means inherit the
         * parent. Typical range {@code 0.0 – 1.0}.
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Optional model-variant identifier (e.g. {@code "thinking"} for DashScope thinking-mode
         * variants). Blank / {@code null} means no variant transform; parent variant — if any —
         * is inherited via builder copy.
         */
        public Builder variant(String variant) {
            this.variant = variant;
            return this;
        }

        /**
         * Sets the {@link Mode}. {@code null} is treated as {@link Mode#ALL}.
         */
        public Builder mode(Mode mode) {
            this.mode = mode != null ? mode : Mode.ALL;
            return this;
        }

        /**
         * Hide this declaration from the LLM's available-subagent list. Defaults to {@code false}.
         */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        /**
         * When {@code true}, the subagent's spawn key is derived deterministically from
         * (parentSessionId, agentId, label), enabling state recovery across parent calls and
         * process restarts. Defaults to {@code false}.
         */
        public Builder persistSession(boolean persistSession) {
            this.persistSession = persistSession;
            return this;
        }

        /**
         * When {@code true} (default), parent DENY permission rules are propagated to the child
         * at spawn time. Set to {@code false} only when the child requires permissions that the
         * parent explicitly denies (rare).
         */
        public Builder inheritParentPermissions(boolean inheritParentPermissions) {
            this.inheritParentPermissions = inheritParentPermissions;
            return this;
        }

        /**
         * Per-type policy for exposing spawned instances as user-addressable threads.
         *
         * <p>{@code TRUE} forces exposure, {@code FALSE} forbids it (overriding an LLM request),
         * and {@code null} (default) defers to the {@code RuntimeContext} override and then the
         * LLM's {@code expose_to_user} argument.
         */
        public Builder exposeToUser(Boolean exposeToUser) {
            this.exposeToUser = exposeToUser;
            return this;
        }

        /**
         * Tool allowlist: when non-empty, only inherited parent tools with listed names are kept.
         * Child-local tool registrations are unaffected.
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder skills(List<String> skills) {
            this.skills = skills;
            return this;
        }

        /**
         * Remote task server base URL. Mutually exclusive with {@link #workspace(Path)} and a
         * non-blank {@link #inlineAgentsBody(String)}.
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Optional HTTP headers for the remote task server (e.g. {@code Authorization}). Only used
         * when {@link #url(String)} is set.
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * Builds the {@link SubagentDeclaration}.
         *
         * @throws IllegalArgumentException if {@code name} or {@code description} is blank, or
         *     mutually exclusive fields are combined (workspace vs inline vs remote URL)
         */
        public SubagentDeclaration build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SubagentDeclaration requires a non-blank name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "SubagentDeclaration requires a non-blank description");
            }
            boolean remote = url != null && !url.isBlank();
            if (remote) {
                if (workspacePath != null) {
                    throw new IllegalArgumentException(
                            "url() and workspace(Path) are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
                if (inlineAgentsBody != null && !inlineAgentsBody.isBlank()) {
                    throw new IllegalArgumentException(
                            "url() and inlineAgentsBody() are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
            } else if (workspacePath != null
                    && inlineAgentsBody != null
                    && !inlineAgentsBody.isBlank()) {
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
