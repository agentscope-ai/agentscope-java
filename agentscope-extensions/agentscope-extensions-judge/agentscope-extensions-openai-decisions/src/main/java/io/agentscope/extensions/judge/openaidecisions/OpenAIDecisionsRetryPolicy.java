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

package io.agentscope.extensions.judge.openaidecisions;

import java.time.Duration;
import java.util.Objects;

/** Bounded retries for transient Decisions failures, in addition to the first attempt. */
public record OpenAIDecisionsRetryPolicy(int maxRetries, Duration initialBackoff) {
    public OpenAIDecisionsRetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        if (maxRetries < 0 || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException(
                    "require maxRetries >= 0 and positive initialBackoff");
        }
    }

    public static OpenAIDecisionsRetryPolicy defaults() {
        return new OpenAIDecisionsRetryPolicy(2, Duration.ofMillis(500));
    }

    public static OpenAIDecisionsRetryPolicy disabled() {
        return new OpenAIDecisionsRetryPolicy(0, Duration.ofMillis(500));
    }
}
