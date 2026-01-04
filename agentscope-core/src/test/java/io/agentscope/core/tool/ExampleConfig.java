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
package io.agentscope.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple configuration helper for examples.
 * Reads configuration from environment variables.
 */
public class ExampleConfig {

    private static final Logger log = LoggerFactory.getLogger(ExampleConfig.class);
    private static final ExampleConfig INSTANCE = new ExampleConfig();

    private ExampleConfig() {}

    public static ExampleConfig getInstance() {
        return INSTANCE;
    }

    public String getOpenAIApiKey() {
        return System.getenv("OPENAI_API_KEY");
    }

    public String getOpenAIBaseUrl() {
        String baseUrl = System.getenv("OPENAI_BASE_URL");
        return baseUrl != null ? baseUrl : "https://api.openai.com";
    }

    public String getOpenAIModelName() {
        String modelName = System.getenv("OPENAI_MODEL_NAME");
        return modelName != null ? modelName : "gpt-4o";
    }

    // DashScope config
    public String getDashScopeApiKey() {
        return System.getenv("DASHSCOPE_API_KEY");
    }

    public String getDashScopeModelName() {
        String modelName = System.getenv("DASHSCOPE_MODEL_NAME");
        return modelName != null ? modelName : "qwen-turbo";
    }

    public String getDashScopeBaseUrl() {
        return System.getenv("DASHSCOPE_BASE_URL");
    }

    public boolean isValidApiKey() {
        String apiKey = getOpenAIApiKey();
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public void printConfiguration() {
        log.info("=== Configuration ===");
        log.info("OpenAI Base URL: {}", getOpenAIBaseUrl());
        log.info("OpenAI Model: {}", getOpenAIModelName());
        log.info("OpenAI API Key configured: {}", isValidApiKey());
        log.info("DashScope Model: {}", getDashScopeModelName());
        log.info("DashScope Base URL: {}", getDashScopeBaseUrl());
        log.info(
                "DashScope API Key configured: {}",
                getDashScopeApiKey() != null && !getDashScopeApiKey().isEmpty());
        log.info("=====================");
    }
}
