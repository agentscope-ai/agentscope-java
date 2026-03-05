/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill;

import io.agentscope.core.model.Model;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Map-based implementation of {@link SkillModelProvider} that resolves model references using
 * direct name lookup and alias mapping.
 *
 * <p>This provider supports multiple model reference formats:
 *
 * <ul>
 *   <li>Direct name: "qwen-turbo" - resolved directly from registered models
 *   <li>Alias: "fast-model" - resolved through alias mapping to actual model name
 *   <li>Provider format: "dashscope:qwen-plus" - used directly as registered key
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * MapBasedSkillModelProvider provider = MapBasedSkillModelProvider.builder()
 *     .register("qwen-turbo", qwenModel)
 *     .register("dashscope:qwen-plus", qwenPlusModel)
 *     .alias("qwen-turbo", "qwen-turbo")
 *     .alias("qwen-plus", "dashscope:qwen-plus")
 *     .defaultModel(defaultModel)
 *     .build();
 *
 * Model model = provider.getModel("qwen-turbo");  // Returns qwenModel
 * }</pre>
 */
public class MapBasedSkillModelProvider implements SkillModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MapBasedSkillModelProvider.class);

    private final Map<String, Model> modelsByName;
    private final Map<String, String> aliasMapping;
    private final Model defaultModel;

    private MapBasedSkillModelProvider(Builder builder) {
        this.modelsByName = new HashMap<>(builder.modelsByName);
        this.aliasMapping = new HashMap<>(builder.aliasMapping);
        this.defaultModel = builder.defaultModel;
    }

    /**
     * Creates a new builder for constructing MapBasedSkillModelProvider instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Model getModel(String modelRef) {
        // Handle null or blank input
        if (modelRef == null || modelRef.isBlank()) {
            log.debug("Model reference is null or blank, returning default model");
            return defaultModel;
        }

        // 1. Try direct lookup first
        Model directMatch = modelsByName.get(modelRef);
        if (directMatch != null) {
            log.debug("Found model by direct name: {}", modelRef);
            return directMatch;
        }

        // 2. Try alias lookup
        String aliasedName = aliasMapping.get(modelRef);
        if (aliasedName != null) {
            Model aliasedModel = modelsByName.get(aliasedName);
            if (aliasedModel != null) {
                log.debug("Resolved alias '{}' to model: {}", modelRef, aliasedName);
                return aliasedModel;
            }
        }

        // 3. Return default model if available
        if (defaultModel != null) {
            log.debug("Model '{}' not found, returning default model", modelRef);
            return defaultModel;
        }

        // 4. No model found
        log.debug("Model '{}' not found and no default model configured", modelRef);
        return null;
    }

    /** Builder for constructing MapBasedSkillModelProvider instances. */
    public static class Builder {
        private final Map<String, Model> modelsByName = new HashMap<>();
        private final Map<String, String> aliasMapping = new HashMap<>();
        private Model defaultModel;

        private Builder() {}

        /**
         * Sets the default model to use when a requested model is not found.
         *
         * @param defaultModel the default model
         * @return this builder
         */
        public Builder defaultModel(Model defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Registers a model with the given name.
         *
         * @param name the name to register the model under
         * @param model the model instance
         * @return this builder
         * @throws IllegalArgumentException if name is null or blank
         */
        public Builder register(String name, Model model) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Model name cannot be null or blank");
            }
            if (model == null) {
                throw new IllegalArgumentException("Model cannot be null");
            }
            this.modelsByName.put(name, model);
            return this;
        }

        /**
         * Registers all models from the given map.
         *
         * @param models map of model names to model instances
         * @return this builder
         */
        public Builder registerAll(Map<String, Model> models) {
            if (models != null) {
                this.modelsByName.putAll(models);
            }
            return this;
        }

        /**
         * Creates an alias from one name to another.
         *
         * @param alias the alias name
         * @param targetName the target model name that the alias points to
         * @return this builder
         * @throws IllegalArgumentException if alias or targetName is null or blank
         */
        public Builder alias(String alias, String targetName) {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("Alias cannot be null or blank");
            }
            if (targetName == null || targetName.isBlank()) {
                throw new IllegalArgumentException("Target name cannot be null or blank");
            }
            this.aliasMapping.put(alias, targetName);
            return this;
        }

        /**
         * Builds the MapBasedSkillModelProvider instance.
         *
         * @return a new MapBasedSkillModelProvider instance
         */
        public MapBasedSkillModelProvider build() {
            return new MapBasedSkillModelProvider(this);
        }
    }
}
