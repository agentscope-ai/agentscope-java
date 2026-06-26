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
package io.agentscope.extensions.model.openai;

import io.agentscope.core.model.Model;

/** Factory used by integration layers that should not depend on OpenAI internals directly. */
public final class OpenAIChatModelFactory {

    private OpenAIChatModelFactory() {}

    /**
     * Creates an OpenAI chat model with the first-phase Spring Boot configuration surface.
     *
     * @param apiKey OpenAI API key
     * @param modelName OpenAI model name
     * @param stream whether streaming is enabled
     * @param baseUrl optional custom base URL
     * @param endpointPath optional custom endpoint path
     * @return created model
     */
    public static Model create(
            String apiKey, String modelName, boolean stream, String baseUrl, String endpointPath) {
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(stream);

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        if (endpointPath != null && !endpointPath.isEmpty()) {
            builder.endpointPath(endpointPath);
        }

        return builder.build();
    }
}
