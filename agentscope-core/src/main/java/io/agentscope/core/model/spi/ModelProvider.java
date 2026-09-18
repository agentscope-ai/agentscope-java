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
package io.agentscope.core.model.spi;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;

/** Service Provider Interface for model adapters discovered from extension modules. */
public interface ModelProvider {

    /** Provider identifier, for example {@code openai}. */
    String providerId();

    /**
     * Returns whether this provider can create a model for the full model id,
     * for example {@code openai:gpt-4o}.
     */
    boolean supports(String modelId);

    /** Returns whether this provider can create a model for the full model id and context. */
    default boolean supports(String modelId, ModelCreationContext context) {
        return supports(modelId);
    }

    /** Creates a model for the full model id. */
    Model create(String modelId);

    /** Creates a model for the full model id using a provider-neutral context. */
    default Model create(String modelId, ModelCreationContext context) {
        return create(modelId);
    }
}
