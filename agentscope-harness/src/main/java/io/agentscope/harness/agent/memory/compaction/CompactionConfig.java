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
package io.agentscope.harness.agent.memory.compaction;

/**
 * Configuration for conversation compaction (summarization).
 *
 * <ul>
 *   <li><b>trigger</b> — when to run compaction (by token count or message count)</li>
 *   <li><b>keep</b> — how many recent messages to preserve verbatim after compaction</li>
 * </ul>
 *
 * <p>Defaults:
 * <ul>
 *   <li>Trigger at 50 messages or 80 000 estimated tokens (whichever comes first)</li>
 *   <li>Keep the 20 most recent messages verbatim</li>
 *   <li>Summarization is enabled; memory flush and offload are both enabled before summary</li>
 * </ul>
 */
public class CompactionConfig {

    /** Default summary prompt with structured format. */
    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            <role>
            Context Extraction Assistant
            </role>

            <primary_objective>
            Your sole objective in this task is to extract the highest quality/most relevant \
            context from the conversation history below.
            </primary_objective>

            <objective_information>
            You're nearing the total number of input tokens you can accept, so you must extract \
            the highest quality/most relevant pieces of information from your conversation history.
            This context will then overwrite the conversation history presented below. Because of \
            this, ensure the context you extract is only the most important information to \
            continue working toward your overall goal.
            </objective_information>

            <instructions>
            The conversation history below will be replaced with the context you extract in this \
            step. You want to ensure that you don't repeat any actions you've already completed, \
            so the context you extract from the conversation history should be focused on the \
            most important information to your overall goal.

            Structure your summary using these sections (populate each or write "None"):

            ## SESSION INTENT
            What is the user's primary goal or request?

            ## SUMMARY
            The most important context, decisions, reasoning, and rejected options.

            ## ARTIFACTS
            Files or resources created, modified, or accessed (with specific paths and changes).

            ## NEXT STEPS
            Specific tasks remaining to achieve the session intent.
            </instructions>

            Carefully read through the entire conversation history below and extract the most \
            important context. Respond ONLY with the extracted context.

            <messages>
            {messages}
            </messages>\
            """;

    private final int triggerMessages;
    private final int triggerTokens;
    private final int keepMessages;
    private final int keepTokens;
    private final String summaryPrompt;
    private final boolean flushBeforeCompact;
    private final boolean offloadBeforeCompact;
    private final TruncateArgsConfig truncateArgsConfig;

    private CompactionConfig(Builder b) {
        this.triggerMessages = b.triggerMessages;
        this.triggerTokens = b.triggerTokens;
        this.keepMessages = b.keepMessages;
        this.keepTokens = b.keepTokens;
        this.summaryPrompt = b.summaryPrompt;
        this.flushBeforeCompact = b.flushBeforeCompact;
        this.offloadBeforeCompact = b.offloadBeforeCompact;
        this.truncateArgsConfig = b.truncateArgsConfig;
    }

    /** Message count above which compaction is triggered (0 = disabled). */
    public int getTriggerMessages() {
        return triggerMessages;
    }

    /** Estimated token count above which compaction is triggered (0 = disabled). */
    public int getTriggerTokens() {
        return triggerTokens;
    }

    /**
     * Number of recent <em>conversation</em> messages (non-SYSTEM) to preserve verbatim.
     * Used when {@link #getKeepTokens()} is 0.
     */
    public int getKeepMessages() {
        return keepMessages;
    }

    /**
     * Token budget for the preserved tail. When non-zero, the keep boundary is determined by
     * scanning from the end until the token budget is exhausted rather than by a fixed count.
     */
    public int getKeepTokens() {
        return keepTokens;
    }

    /** Prompt template used for the summarization LLM call. Must contain {@code {messages}}. */
    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    /** Whether to flush long-term memories from the prefix before compaction. */
    public boolean isFlushBeforeCompact() {
        return flushBeforeCompact;
    }

    /** Whether to offload raw messages to the session JSONL before compaction. */
    public boolean isOffloadBeforeCompact() {
        return offloadBeforeCompact;
    }

    /**
     * Configuration for the lightweight pre-summarization argument truncation pass.
     * When {@code null}, argument truncation is disabled.
     */
    public TruncateArgsConfig getTruncateArgsConfig() {
        return truncateArgsConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int triggerMessages = 50;
        private int triggerTokens = 80_000;
        private int keepMessages = 20;
        private int keepTokens = 0;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private boolean flushBeforeCompact = true;
        private boolean offloadBeforeCompact = true;
        private TruncateArgsConfig truncateArgsConfig = null;

        /** Trigger compaction when conversation has at least this many messages (0 = disabled). */
        public Builder triggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
            return this;
        }

        /** Trigger compaction when estimated token count exceeds this value (0 = disabled). */
        public Builder triggerTokens(int triggerTokens) {
            this.triggerTokens = triggerTokens;
            return this;
        }

        /** Number of recent messages to keep verbatim after compaction. */
        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        /**
         * Token budget for the preserved tail (overrides {@code keepMessages} when non-zero).
         */
        public Builder keepTokens(int keepTokens) {
            this.keepTokens = keepTokens;
            return this;
        }

        /** Custom summary prompt. Must contain {@code {messages}} placeholder. */
        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = summaryPrompt;
            return this;
        }

        /** Whether to flush long-term memories before compaction (default true). */
        public Builder flushBeforeCompact(boolean flushBeforeCompact) {
            this.flushBeforeCompact = flushBeforeCompact;
            return this;
        }

        /** Whether to offload raw messages to session JSONL before compaction (default true). */
        public Builder offloadBeforeCompact(boolean offloadBeforeCompact) {
            this.offloadBeforeCompact = offloadBeforeCompact;
            return this;
        }

        /**
         * Enables lightweight pre-summarization argument truncation. When set, large
         * {@code ToolUseBlock} argument values in old messages are shortened before every
         * model call (no LLM required). This fires at a separate, typically lower threshold
         * than full compaction.
         *
         * <p>Pass {@code null} (default) to disable.
         */
        public Builder truncateArgs(TruncateArgsConfig config) {
            this.truncateArgsConfig = config;
            return this;
        }

        public CompactionConfig build() {
            return new CompactionConfig(this);
        }
    }

    // -------------------------------------------------------------------------
    // TruncateArgsConfig
    // -------------------------------------------------------------------------

    /**
     * Configuration for the lightweight argument-truncation pass that runs before
     * summarization.
     *
     * <p>When triggered, large string arguments of {@code ToolUseBlock}s in older messages
     * (before the keep window) are clipped to {@link #getMaxArgLength()} characters.
     * This is a cheap, non-LLM operation that prevents context ballooning from verbose
     * tool invocations (e.g., {@code write_file}, {@code edit_file}).
     *
     * <p>Defaults (when enabled via {@link Builder#truncateArgs(TruncateArgsConfig)}):
     * <ul>
     *   <li>Trigger at 25 messages or 40 000 tokens</li>
     *   <li>Keep the 20 most recent messages untouched</li>
     *   <li>Max argument length: 2 000 characters</li>
     * </ul>
     */
    public static class TruncateArgsConfig {

        private final int triggerMessages;
        private final int triggerTokens;
        private final int keepMessages;
        private final int keepTokens;
        private final int maxArgLength;
        private final String truncationText;

        private TruncateArgsConfig(TruncateArgsBuilder b) {
            this.triggerMessages = b.triggerMessages;
            this.triggerTokens = b.triggerTokens;
            this.keepMessages = b.keepMessages;
            this.keepTokens = b.keepTokens;
            this.maxArgLength = b.maxArgLength;
            this.truncationText = b.truncationText;
        }

        public int getTriggerMessages() {
            return triggerMessages;
        }

        public int getTriggerTokens() {
            return triggerTokens;
        }

        public int getKeepMessages() {
            return keepMessages;
        }

        public int getKeepTokens() {
            return keepTokens;
        }

        /** Maximum character length of any single tool argument value (default 2 000). */
        public int getMaxArgLength() {
            return maxArgLength;
        }

        /** Suffix appended after the first 20 characters of a truncated argument. */
        public String getTruncationText() {
            return truncationText;
        }

        public static TruncateArgsBuilder builder() {
            return new TruncateArgsBuilder();
        }

        public static class TruncateArgsBuilder {

            private int triggerMessages = 25;
            private int triggerTokens = 40_000;
            private int keepMessages = 20;
            private int keepTokens = 0;
            private int maxArgLength = 2_000;
            private String truncationText = "...(argument truncated)";

            public TruncateArgsBuilder triggerMessages(int triggerMessages) {
                this.triggerMessages = triggerMessages;
                return this;
            }

            public TruncateArgsBuilder triggerTokens(int triggerTokens) {
                this.triggerTokens = triggerTokens;
                return this;
            }

            public TruncateArgsBuilder keepMessages(int keepMessages) {
                this.keepMessages = keepMessages;
                return this;
            }

            public TruncateArgsBuilder keepTokens(int keepTokens) {
                this.keepTokens = keepTokens;
                return this;
            }

            public TruncateArgsBuilder maxArgLength(int maxArgLength) {
                this.maxArgLength = maxArgLength;
                return this;
            }

            public TruncateArgsBuilder truncationText(String truncationText) {
                this.truncationText = truncationText;
                return this;
            }

            public TruncateArgsConfig build() {
                return new TruncateArgsConfig(this);
            }
        }
    }
}
