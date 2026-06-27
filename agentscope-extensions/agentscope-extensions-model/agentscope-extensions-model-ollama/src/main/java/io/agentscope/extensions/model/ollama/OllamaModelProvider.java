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
import io.agentscope.core.model.spi.ModelProvider;
import java.util.regex.Pattern;

/** Ollama provider registered through {@link java.util.ServiceLoader}. */
public final class OllamaModelProvider implements ModelProvider {

    private static final String PREFIX = "ollama:";
    private static final Pattern MODEL_ID = Pattern.compile("ollama:.+");

    @Override
    public String providerId() {
        return "ollama";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    @Override
    public Model create(String modelId) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported Ollama model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = OllamaHttpClient.DEFAULT_BASE_URL;
        }
        return OllamaChatModel.builder().modelName(modelName).baseUrl(baseUrl).build();
    }
}
