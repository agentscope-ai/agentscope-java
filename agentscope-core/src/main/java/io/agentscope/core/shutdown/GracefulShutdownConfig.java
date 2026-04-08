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
package io.agentscope.core.shutdown;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for graceful shutdown behavior of the agent system.
 *
 * <p>This record defines how the system should behave during shutdown, including:
 * <ul>
 *   <li>Maximum time to wait for ongoing operations to complete</li>
 *   <li>Policy for handling partial reasoning results</li>
 * </ul>
 *
 * @param shutdownTimeout       maximum duration to wait for shutdown completion;
 *                              if null, the system will wait indefinitely for all operations
 *                              to complete; if specified, must be a positive duration
 * @param partialReasoningPolicy policy for handling incomplete reasoning results during shutdown;
 *                              cannot be null
 */
public record GracefulShutdownConfig(
        Duration shutdownTimeout, PartialReasoningPolicy partialReasoningPolicy) {

    /**
     * Default configuration instance.
     *
     * <p>Uses the following settings:
     * <ul>
     *   <li>Shutdown timeout: null (wait indefinitely)</li>
     *   <li>Partial reasoning policy: {@link PartialReasoningPolicy#SAVE}</li>
     * </ul>
     */
    public static final GracefulShutdownConfig DEFAULT =
            new GracefulShutdownConfig(null, PartialReasoningPolicy.SAVE);

    /**
     * Compact constructor for validation.
     *
     * <p>Validates that:
     * <ul>
     *   <li>{@code partialReasoningPolicy} is not null</li>
     *   <li>{@code shutdownTimeout}, if specified, is a positive duration</li>
     * </ul>
     *
     * @throws NullPointerException     if partialReasoningPolicy is null
     * @throws IllegalArgumentException if shutdownTimeout is non-positive
     */
    public GracefulShutdownConfig {
        Objects.requireNonNull(partialReasoningPolicy, "partialReasoningPolicy cannot be null");
        if (shutdownTimeout != null && (shutdownTimeout.isNegative() || shutdownTimeout.isZero())) {
            throw new IllegalArgumentException("shutdownTimeout must be > 0 or null (infinite)");
        }
    }
}
