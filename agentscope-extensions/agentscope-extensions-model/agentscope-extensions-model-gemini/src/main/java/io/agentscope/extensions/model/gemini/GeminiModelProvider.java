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
import io.agentscope.core.model.spi.ModelProvider;
import java.util.regex.Pattern;

/** Gemini provider registered through {@link java.util.ServiceLoader}. */
public final class GeminiModelProvider implements ModelProvider {

    private static final String PREFIX = "gemini:";
    private static final Pattern MODEL_ID = Pattern.compile("gemini:.+");

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    @Override
    public Model create(String modelId) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported Gemini model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable GEMINI_API_KEY is required to auto-create model: "
                            + modelId);
        }
        return GeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .streamEnabled(true)
                .build();
    }
}
