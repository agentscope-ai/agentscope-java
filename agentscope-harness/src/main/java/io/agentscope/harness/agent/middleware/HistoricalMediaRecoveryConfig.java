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

/** Configuration for recovering from unavailable URL-backed media in conversation history. */
public final class HistoricalMediaRecoveryConfig {

    public static final String DEFAULT_REPLACEMENT_TEXT =
            "[Historical media omitted because its source URL is no longer available]";

    private final String replacementText;

    private HistoricalMediaRecoveryConfig(Builder builder) {
        this.replacementText = builder.replacementText;
    }

    /** Creates a configuration with the default historical-media placeholder. */
    public static HistoricalMediaRecoveryConfig defaults() {
        return builder().build();
    }

    /** Creates a builder for historical-media recovery configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Text used in place of each unavailable historical URL-backed media block. */
    public String getReplacementText() {
        return replacementText;
    }

    /** Builder for {@link HistoricalMediaRecoveryConfig}. */
    public static final class Builder {

        private String replacementText = DEFAULT_REPLACEMENT_TEXT;

        /** Sets the non-blank text used to replace unavailable historical media. */
        public Builder replacementText(String replacementText) {
            this.replacementText = replacementText;
            return this;
        }

        public HistoricalMediaRecoveryConfig build() {
            if (replacementText == null || replacementText.isBlank()) {
                throw new IllegalArgumentException("replacementText must not be blank");
            }
            return new HistoricalMediaRecoveryConfig(this);
        }
    }
}
