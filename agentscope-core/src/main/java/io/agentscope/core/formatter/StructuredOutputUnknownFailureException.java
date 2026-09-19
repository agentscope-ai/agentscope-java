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

/**
 * The structured-output retry loop exhausted its budget on a non-model (platform/internal)
 * fault — e.g. a registry race or a validator hiccup — rather than on a model-output
 * problem. The original exception is preserved as the cause.
 *
 * <p>This type exists so failure domains stay distinguishable by type: the caller gets this
 * wrapper (never a model-blaming {@link StructuredOutputValidationException}), and the
 * fallback router can short-circuit without burning a synthetic-tool round trip.
 */
public class StructuredOutputUnknownFailureException extends RuntimeException {

    public StructuredOutputUnknownFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
