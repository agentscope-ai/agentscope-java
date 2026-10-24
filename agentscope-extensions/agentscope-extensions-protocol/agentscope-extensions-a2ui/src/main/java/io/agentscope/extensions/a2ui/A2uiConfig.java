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
package io.agentscope.extensions.a2ui;

/**
 * Configuration for the A2UI capability. Passed to the {@link A2uiMiddleware} constructor
 * (plain-middleware wiring, no builder API on the framework side); all fields carry sensible
 * defaults.
 */
public final class A2uiConfig {

    private final String catalogId;
    private final String catalogResource;
    private final boolean stopAfterPresent;
    private final int maxComponents;
    private final boolean surfacePersistEnabled;
    private final String renderPrompt;
    private final int renderMaxIters;
    private final boolean catalogInPrompt;
    private final String appendPreference;

    private A2uiConfig(Builder builder) {
        this.catalogId = builder.catalogId;
        this.catalogResource = builder.catalogResource;
        this.stopAfterPresent = builder.stopAfterPresent;
        this.maxComponents = builder.maxComponents;
        this.surfacePersistEnabled = builder.surfacePersistEnabled;
        this.renderPrompt = builder.renderPrompt;
        this.renderMaxIters = builder.renderMaxIters;
        this.catalogInPrompt = builder.catalogInPrompt;
        this.appendPreference = builder.appendPreference;
    }

    public static A2uiConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Identifier declared inside every envelope (e.g. {@code agentscope.io:a2ui/basic}). */
    public String catalogId() {
        return catalogId;
    }

    /** Classpath resource path of the component catalog JSON. */
    public String catalogResource() {
        return catalogResource;
    }

    /**
     * Whether a successful {@code a2ui_render} stops the current acting round (the delivered
     * surface then waits for user interaction). Name kept for config compatibility; {@code
     * a2ui_present} is now the render sub-agent's component-tree submit tool, not a parent tool.
     */
    public boolean stopAfterPresent() {
        return stopAfterPresent;
    }

    /**
     * Whether the component catalog guide is composed into the render sub-agent's SYSTEM prompt
     * (default) instead of being loaded through one {@code a2ui_catalog} call per render.
     */
    public boolean catalogInPrompt() {
        return catalogInPrompt;
    }

    /** Upper bound on components per envelope. */
    public int maxComponents() {
        return maxComponents;
    }

    /** Whether the surface registry is persisted across runs via {@code AgentStateStore}. */
    public boolean surfacePersistEnabled() {
        return surfacePersistEnabled;
    }

    /** SYSTEM prompt for the render sub-agent; {@code null} = SDK default prompt. */
    public String renderPrompt() {
        return renderPrompt;
    }

    /** ReAct iteration bound of one render sub-agent run. */
    public int renderMaxIters() {
        return renderMaxIters;
    }

    /**
     * User UI preference appended to both the hosting agent's system prompt and the render
     * sub-agent's system prompt; {@code null}/blank = none.
     */
    public String appendPreference() {
        return appendPreference;
    }

    /**
     * Renders {@link #appendPreference()} as a prompt block, or an empty string when unset.
     * Shared by the main-agent system prompt ({@code A2uiRenderStopMiddleware}) and the render
     * sub-agent system prompt ({@code A2uiRenderManager}).
     */
    public String preferenceBlock() {
        if (appendPreference == null || appendPreference.isBlank()) {
            return "";
        }
        return "\n\n## User UI preference\n"
                + "The user has stated a standing preference about the UI they see. Honor it in"
                + " every surface you produce, unless it conflicts with the current request:\n- "
                + appendPreference.trim().replace("\n", "\n- ")
                + "\n";
    }

    public static final class Builder {

        private String catalogId = "agentscope.io:a2ui/basic";
        private String catalogResource = "a2ui/basic-catalog.json";
        private boolean stopAfterPresent = true;
        private int maxComponents = 50;
        private boolean surfacePersistEnabled = true;
        private String renderPrompt = null;
        private int renderMaxIters = 6;
        private boolean catalogInPrompt = true;
        private String appendPreference = null;

        private Builder() {}

        public Builder catalogId(String catalogId) {
            this.catalogId = catalogId;
            return this;
        }

        public Builder catalogResource(String catalogResource) {
            this.catalogResource = catalogResource;
            return this;
        }

        public Builder stopAfterPresent(boolean stopAfterPresent) {
            this.stopAfterPresent = stopAfterPresent;
            return this;
        }

        public Builder maxComponents(int maxComponents) {
            this.maxComponents = maxComponents;
            return this;
        }

        public Builder surfacePersistEnabled(boolean surfacePersistEnabled) {
            this.surfacePersistEnabled = surfacePersistEnabled;
            return this;
        }

        public Builder renderPrompt(String renderPrompt) {
            this.renderPrompt = renderPrompt;
            return this;
        }

        public Builder renderMaxIters(int renderMaxIters) {
            this.renderMaxIters = renderMaxIters;
            return this;
        }

        /**
         * {@code true} (default) composes the catalog guide into the render sub-agent's SYSTEM
         * prompt: the child then submits on its first turn and keeps the guide visible through
         * validation-retry rounds. {@code false} keeps the on-demand {@code a2ui_catalog} tool
         * instead, which saves context for very large catalogs.
         */
        public Builder catalogInPrompt(boolean catalogInPrompt) {
            this.catalogInPrompt = catalogInPrompt;
            return this;
        }

        /**
         * User UI preference (free text, may be multi-line — each line is one preference).
         * Appended verbatim to the hosting agent's system prompt and to the render sub-agent's
         * system prompt.
         *
         * <p>Trust boundary: both targets are SYSTEM prompts, so pass host-authored static text
         * resolved at wiring time — never the user's request content or anything assembled per
         * request. Request content belongs in the tool arguments.
         */
        public Builder appendPreference(String appendPreference) {
            this.appendPreference = appendPreference;
            return this;
        }

        public A2uiConfig build() {
            return new A2uiConfig(this);
        }
    }
}
