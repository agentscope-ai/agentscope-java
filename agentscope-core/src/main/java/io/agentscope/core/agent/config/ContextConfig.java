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
package io.agentscope.core.agent.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Context-window management configuration controlling when memory is compressed and how
 * oversized tool results are trimmed.
 *
 * <p>Compression is triggered when token usage exceeds {@link #triggerRatio()} of the model's
 * context window; the agent then produces a structured summary (driven by {@link
 * #summarySchema()}) keyed off {@link #compressionPrompt()} and renders the resulting fields
 * into {@link #summaryTemplate()} as a {@code <system-info>} block prepended to the
 * post-compression memory.
 *
 * <p>{@link #toolResultLimit()} caps individual tool-result token length to avoid a single
 * runaway tool flooding the context.
 *
 * <p>Use {@link #defaults()} for the conventional starting point or {@link #builder()} to
 * customise.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ContextConfig {

    public static final double DEFAULT_TRIGGER_RATIO = 0.8;
    public static final double DEFAULT_RESERVE_RATIO = 0.1;
    public static final int DEFAULT_TOOL_RESULT_LIMIT = 3000;

    public static final String DEFAULT_COMPRESSION_PROMPT =
            "<system-hint>You have been working on the task described above "
                    + "but have not yet completed it. "
                    + "Now write a continuation summary that will allow you to resume "
                    + "work efficiently in a future context window where the "
                    + "conversation history will be replaced with this summary. "
                    + "Your summary should be structured, concise, and actionable."
                    + "</system-hint>";

    public static final String DEFAULT_SUMMARY_TEMPLATE =
            "<system-info>Here is a summary of your previous work\n"
                    + "# Task Overview\n"
                    + "{task_overview}\n\n"
                    + "# Current State\n"
                    + "{current_state}\n\n"
                    + "# Important Discoveries\n"
                    + "{important_discoveries}\n\n"
                    + "# Next Steps\n"
                    + "{next_steps}\n\n"
                    + "# Context to Preserve\n"
                    + "{context_to_preserve}"
                    + "</system-info>";

    private final double triggerRatio;
    private final double reserveRatio;
    private final String compressionPrompt;
    private final String summaryTemplate;
    private final Map<String, Object> summarySchema;
    private final int toolResultLimit;

    private ContextConfig(Builder builder) {
        if (!(builder.triggerRatio > 0.0 && builder.triggerRatio < 0.9)) {
            throw new IllegalArgumentException(
                    "triggerRatio must be in (0, 0.9): " + builder.triggerRatio);
        }
        if (!(builder.reserveRatio > 0.0 && builder.reserveRatio < 0.9)) {
            throw new IllegalArgumentException(
                    "reserveRatio must be in (0, 0.9): " + builder.reserveRatio);
        }
        if (builder.reserveRatio >= builder.triggerRatio) {
            throw new IllegalArgumentException(
                    "reserveRatio ("
                            + builder.reserveRatio
                            + ") must be smaller than triggerRatio ("
                            + builder.triggerRatio
                            + ")");
        }
        if (builder.toolResultLimit <= 0) {
            throw new IllegalArgumentException(
                    "toolResultLimit must be > 0: " + builder.toolResultLimit);
        }
        this.triggerRatio = builder.triggerRatio;
        this.reserveRatio = builder.reserveRatio;
        this.compressionPrompt =
                builder.compressionPrompt == null
                        ? DEFAULT_COMPRESSION_PROMPT
                        : builder.compressionPrompt;
        this.summaryTemplate =
                builder.summaryTemplate == null
                        ? DEFAULT_SUMMARY_TEMPLATE
                        : builder.summaryTemplate;
        this.summarySchema =
                builder.summarySchema == null
                        ? SummarySchema.jsonSchema()
                        : Map.copyOf(new LinkedHashMap<>(builder.summarySchema));
        this.toolResultLimit = builder.toolResultLimit;
    }

    @JsonCreator
    static ContextConfig fromJson(
            @JsonProperty("trigger_ratio") Double triggerRatio,
            @JsonProperty("reserve_ratio") Double reserveRatio,
            @JsonProperty("compression_prompt") String compressionPrompt,
            @JsonProperty("summary_template") String summaryTemplate,
            @JsonProperty("summary_schema") Map<String, Object> summarySchema,
            @JsonProperty("tool_result_limit") Integer toolResultLimit) {
        Builder b = builder();
        if (triggerRatio != null) {
            b.triggerRatio(triggerRatio);
        }
        if (reserveRatio != null) {
            b.reserveRatio(reserveRatio);
        }
        if (compressionPrompt != null) {
            b.compressionPrompt(compressionPrompt);
        }
        if (summaryTemplate != null) {
            b.summaryTemplate(summaryTemplate);
        }
        if (summarySchema != null) {
            b.summarySchema(summarySchema);
        }
        if (toolResultLimit != null) {
            b.toolResultLimit(toolResultLimit);
        }
        return b.build();
    }

    /** Returns a config initialised to all default values. */
    public static ContextConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonProperty("trigger_ratio")
    public double triggerRatio() {
        return triggerRatio;
    }

    @JsonProperty("reserve_ratio")
    public double reserveRatio() {
        return reserveRatio;
    }

    @JsonProperty("compression_prompt")
    public String compressionPrompt() {
        return compressionPrompt;
    }

    @JsonProperty("summary_template")
    public String summaryTemplate() {
        return summaryTemplate;
    }

    @JsonProperty("summary_schema")
    public Map<String, Object> summarySchema() {
        return summarySchema;
    }

    @JsonProperty("tool_result_limit")
    public int toolResultLimit() {
        return toolResultLimit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContextConfig other)) {
            return false;
        }
        return Double.compare(triggerRatio, other.triggerRatio) == 0
                && Double.compare(reserveRatio, other.reserveRatio) == 0
                && toolResultLimit == other.toolResultLimit
                && Objects.equals(compressionPrompt, other.compressionPrompt)
                && Objects.equals(summaryTemplate, other.summaryTemplate)
                && Objects.equals(summarySchema, other.summarySchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                triggerRatio,
                reserveRatio,
                compressionPrompt,
                summaryTemplate,
                summarySchema,
                toolResultLimit);
    }

    @Override
    public String toString() {
        return "ContextConfig{triggerRatio="
                + triggerRatio
                + ", reserveRatio="
                + reserveRatio
                + ", toolResultLimit="
                + toolResultLimit
                + '}';
    }

    public static final class Builder {
        private double triggerRatio = DEFAULT_TRIGGER_RATIO;
        private double reserveRatio = DEFAULT_RESERVE_RATIO;
        private String compressionPrompt;
        private String summaryTemplate;
        private Map<String, Object> summarySchema;
        private int toolResultLimit = DEFAULT_TOOL_RESULT_LIMIT;

        private Builder() {}

        public Builder triggerRatio(double triggerRatio) {
            this.triggerRatio = triggerRatio;
            return this;
        }

        public Builder reserveRatio(double reserveRatio) {
            this.reserveRatio = reserveRatio;
            return this;
        }

        public Builder compressionPrompt(String compressionPrompt) {
            this.compressionPrompt = compressionPrompt;
            return this;
        }

        public Builder summaryTemplate(String summaryTemplate) {
            this.summaryTemplate = summaryTemplate;
            return this;
        }

        public Builder summarySchema(Map<String, Object> summarySchema) {
            this.summarySchema = summarySchema;
            return this;
        }

        public Builder toolResultLimit(int toolResultLimit) {
            this.toolResultLimit = toolResultLimit;
            return this;
        }

        public ContextConfig build() {
            return new ContextConfig(this);
        }
    }
}
