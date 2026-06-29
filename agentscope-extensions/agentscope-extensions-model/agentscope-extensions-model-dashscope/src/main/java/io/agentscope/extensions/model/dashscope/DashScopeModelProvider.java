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
package io.agentscope.extensions.model.dashscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.spi.ModelProvider;
import java.util.regex.Pattern;

/** DashScope provider registered through {@link java.util.ServiceLoader}. */
public final class DashScopeModelProvider implements ModelProvider {

    private static final String PREFIX = "dashscope:";
    private static final Pattern DASH_SCOPE_MODEL_ID = Pattern.compile("dashscope:.+");
    private static final Pattern QWEN_SHORT_MODEL_ID = Pattern.compile("qwen.+");

    @Override
    public String providerId() {
        return "dashscope";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null
                && (DASH_SCOPE_MODEL_ID.matcher(modelId).matches()
                        || QWEN_SHORT_MODEL_ID.matcher(modelId).matches());
    }

    @Override
    public Model create(String modelId) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported DashScope model id: " + modelId);
        }
        String modelName =
                modelId.startsWith(PREFIX) ? modelId.substring(PREFIX.length()) : modelId;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable DASHSCOPE_API_KEY is required to auto-create model: "
                            + modelId);
        }
        return DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                .build();
    }
}
