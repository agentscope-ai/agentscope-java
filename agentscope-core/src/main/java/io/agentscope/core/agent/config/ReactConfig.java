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
 * back into the next reasoning round).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReactConfig(
        @JsonProperty("max_iters") int maxIters,
        @JsonProperty("stop_on_reject") boolean stopOnReject) {

    public static final int DEFAULT_MAX_ITERS = 20;
    public static final boolean DEFAULT_STOP_ON_REJECT = false;

    public ReactConfig {
        if (maxIters <= 0) {
            throw new IllegalArgumentException("maxIters must be > 0: " + maxIters);
        }
    }

    /** Returns a config initialised to all default values. */
    public static ReactConfig defaults() {
        return new ReactConfig(DEFAULT_MAX_ITERS, DEFAULT_STOP_ON_REJECT);
    }

    @JsonCreator
    static ReactConfig fromJson(
            @JsonProperty("max_iters") Integer maxIters,
            @JsonProperty("stop_on_reject") Boolean stopOnReject) {
        return new ReactConfig(
                maxIters == null ? DEFAULT_MAX_ITERS : maxIters,
                stopOnReject == null ? DEFAULT_STOP_ON_REJECT : stopOnReject);
    }
}
