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
package io.agentscope.extensions.model.gemini;

import io.agentscope.core.model.Model;

/** Factory used by integration layers that should not depend on Gemini internals directly. */
public final class GeminiChatModelFactory {

    private GeminiChatModelFactory() {}

    /**
     * Creates a Gemini chat model with the first-phase Spring Boot configuration surface.
     *
     * @param apiKey Gemini API key
     * @param modelName Gemini model name
     * @param stream whether streaming is enabled
     * @param baseUrl optional custom base URL
     * @param project optional Google Cloud project ID for Vertex AI
     * @param location optional Google Cloud location for Vertex AI
     * @param vertexAI optional flag for using Vertex AI APIs
     * @return created model
     */
    public static Model create(
            String apiKey,
            String modelName,
            boolean stream,
            String baseUrl,
            String project,
            String location,
            Boolean vertexAI) {
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .streamEnabled(stream)
                        .project(project)
                        .location(location);

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        if (vertexAI != null) {
            builder.vertexAI(vertexAI);
        }

        return builder.build();
    }
}
