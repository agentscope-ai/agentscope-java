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
package io.agentscope.extensions.model.anthropic;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.spi.ModelProvider;

/** Anthropic provider registered through {@link java.util.ServiceLoader}. */
public final class AnthropicModelProvider implements ModelProvider {

    private static final String PREFIX = "anthropic:";

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && modelId.startsWith(PREFIX) && modelId.length() > PREFIX.length();
    }

    @Override
    public Model create(String modelId) {
        String modelName = modelId.substring(PREFIX.length());
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return AnthropicChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                .build();
    }
}
