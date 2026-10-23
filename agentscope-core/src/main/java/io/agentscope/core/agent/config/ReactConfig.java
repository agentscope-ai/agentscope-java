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

/**
 * Reasoning-loop configuration. {@link #maxIters()} caps the number of
 * reasoning→acting iterations within a single reply; {@link #stopOnReject()} controls whether
 * a permission rejection of any tool call terminates the loop (instead of feeding the rejection
 * back into the next reasoning round); {@link #maxToolErrorRecoveries()} caps the number of
 * consecutive recoveries from provider-level "unknown tool" streaming errors within a single
 * reply (each recovery lets the acting phase return "Tool not found" so the model can
 * self-correct); {@code 0} disables recovery entirely so provider tool errors keep
 * failing the turn out of the box for users who rely on fail-fast.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReactConfig(
        @JsonProperty("max_iters") int maxIters,
        @JsonProperty("stop_on_reject") boolean stopOnReject,
        @JsonProperty("max_tool_error_recoveries") int maxToolErrorRecoveries) {

    public static final int DEFAULT_MAX_ITERS = 20;
    public static final boolean DEFAULT_STOP_ON_REJECT = false;
    public static final int DEFAULT_MAX_TOOL_ERROR_RECOVERIES = 3;

    /**
     * Convenience constructor keeping the default tool-error recovery cap.
     *
     * @param maxIters maximum reasoning→acting iterations
     * @param stopOnReject whether a permission rejection terminates the loop
     */
    public ReactConfig(int maxIters, boolean stopOnReject) {
        this(maxIters, stopOnReject, DEFAULT_MAX_TOOL_ERROR_RECOVERIES);
    }

    public ReactConfig {
        if (maxIters <= 0) {
            throw new IllegalArgumentException("maxIters must be > 0: " + maxIters);
        }
        if (maxToolErrorRecoveries < 0) {
            throw new IllegalArgumentException(
                    "maxToolErrorRecoveries must be >= 0 (0 disables recovery): "
                            + maxToolErrorRecoveries);
        }
    }

    /** Returns a config initialised to all default values. */
    public static ReactConfig defaults() {
        return new ReactConfig(
                DEFAULT_MAX_ITERS, DEFAULT_STOP_ON_REJECT, DEFAULT_MAX_TOOL_ERROR_RECOVERIES);
    }

    @JsonCreator
    static ReactConfig fromJson(
            @JsonProperty("max_iters") Integer maxIters,
            @JsonProperty("stop_on_reject") Boolean stopOnReject,
            @JsonProperty("max_tool_error_recoveries") Integer maxToolErrorRecoveries) {
        return new ReactConfig(
                maxIters == null ? DEFAULT_MAX_ITERS : maxIters,
                stopOnReject == null ? DEFAULT_STOP_ON_REJECT : stopOnReject,
                maxToolErrorRecoveries == null
                        ? DEFAULT_MAX_TOOL_ERROR_RECOVERIES
                        : maxToolErrorRecoveries);
    }
}
