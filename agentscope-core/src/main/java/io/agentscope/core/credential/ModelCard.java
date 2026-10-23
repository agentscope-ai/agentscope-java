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
package io.agentscope.core.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Description of one candidate chat model under a provider.
 *
 * <p>This is a minimal placeholder so {@link CredentialBase#listModels()} has a return type.
 * Capability flags and parameter schemas will be added as the model-discovery infrastructure
 * matures.
 */
public record ModelCard(
        @JsonProperty("model_name") String modelName,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("context_size") Integer contextSize) {

    @JsonCreator
    public ModelCard {}
}
