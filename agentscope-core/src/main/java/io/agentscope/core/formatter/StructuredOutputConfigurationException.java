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
 * A structured-output configuration error (missing schema, a schema that fails to compile, a
 * misconfigured validator registry) — not a model-output problem. Callers must propagate it
 * instead of retrying (which would bill model calls for a configuration fault) or degrading
 * to the synthetic-tool fallback (which reuses the same schema and fails the same way).
 *
 * <p>Call-contract note: {@code ReActAgent.call(...)} previously surfaced a broken schema as
 * a {@link StructuredOutputValidationException} after exhausting its retry budget; it now
 * fails fast with this exception on the first attempt. Downstream error mapping (AG-UI error
 * surfaces, HTTP-status translation in starters) should account for the new type when
 * upgrading.
 */
public class StructuredOutputConfigurationException extends IllegalArgumentException {

    public StructuredOutputConfigurationException(String message) {
        super(message);
    }

    public StructuredOutputConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
