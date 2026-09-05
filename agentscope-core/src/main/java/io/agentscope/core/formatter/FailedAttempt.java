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
package io.agentscope.core.formatter;

import java.util.List;
import java.util.Optional;

/**
 * A recorded failed attempt of a structured-output generation cycle.
 *
 * <p>Failed attempts are observable in two ways: via {@code onFailedAttempt}
 * listeners on {@link StructuredOutputRetryPolicy} (streaming visibility), and
 * accumulated on the final {@link StructuredOutputValidationException} for
 * post-hoc inspection.
 *
 * <p>The attempt's raw output is retained verbatim so callers can audit what
 * the model actually produced — never a synthesized substitute.
 */
public record FailedAttempt(
        int attemptNumber,
        Kind kind,
        List<StructuredOutputValidator.ValidationError> validationErrors,
        String parseErrorMessage,
        String rawOutput,
        Long promptTokens,
        Long completionTokens) {

    /** Normalizes a null error list to empty so consumers never see null. */
    public FailedAttempt {
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    /** Failure stage of an attempt. */
    public enum Kind {
        /** The model output was not valid JSON or not a JSON object. */
        PARSE_ERROR,
        /** The model output was valid JSON but failed schema validation. */
        VALIDATION_ERROR
    }

    public Optional<Long> totalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return Optional.empty();
        }
        return Optional.of(
                (promptTokens == null ? 0 : promptTokens)
                        + (completionTokens == null ? 0 : completionTokens));
    }
}
