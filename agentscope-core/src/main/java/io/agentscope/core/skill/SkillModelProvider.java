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
package io.agentscope.core.skill;

import io.agentscope.core.model.Model;

/**
 * Provider for creating Model instances based on model references.
 *
 * <p>Supports multiple model reference formats:
 *
 * <ul>
 *   <li>Short alias: "qwen-turbo", "qwen-plus" - mapped to full model names
 *   <li>Full name: "qwen-turbo" - used directly
 *   <li>Provider format: "dashscope:qwen-plus" - uses specified provider
 * </ul>
 */
public interface SkillModelProvider {

    /**
     * Get a Model instance for the given model reference.
     *
     * @param modelRef The model reference (e.g., "qwen-turbo", "dashscope:qwen-plus")
     * @return Model instance, or null if not available
     */
    Model getModel(String modelRef);

    /**
     * Check if a model reference is available.
     *
     * @param modelRef The model reference
     * @return true if the model can be provided
     */
    default boolean isAvailable(String modelRef) {
        return getModel(modelRef) != null;
    }
}
