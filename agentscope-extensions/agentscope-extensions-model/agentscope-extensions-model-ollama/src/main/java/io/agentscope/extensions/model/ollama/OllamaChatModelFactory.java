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
package io.agentscope.extensions.model.ollama;

import io.agentscope.core.model.Model;

/** Factory used by integration layers that should not depend on Ollama internals directly. */
public final class OllamaChatModelFactory {

    private OllamaChatModelFactory() {}

    /**
     * Creates an Ollama chat model with the stable first-phase configuration surface.
     *
     * @param modelName Ollama model name
     * @param baseUrl optional Ollama server base URL
     * @return created model
     */
    public static Model create(String modelName, String baseUrl) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder().modelName(modelName);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
