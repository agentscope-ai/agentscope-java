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
package io.agentscope.core.model;

/**
 * Enum representing the endpoint type for DashScope models.
 *
 * <p>This allows developers to explicitly specify which API endpoint to use,
 * overriding the automatic model name-based detection.
 *
 * <p>Usage example:
 * <pre>{@code
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("qwen3.5-plus")
 *     .endpointType(EndpointType.MULTIMODAL)  // Explicitly use multimodal API
 *     .build();
 * }</pre>
 */
public enum EndpointType {
    /**
     * Automatically determine endpoint type based on model name.
     *
     * <p>This is the default behavior.
     *
     * @see DashScopeHttpClient#isMultimodalModel(String)
     */
    AUTO,

    /**
     * Force use of text generation API.
     */
    TEXT,

    /**
     * Force use of multimodal generation API.
     */
    MULTIMODAL
}
