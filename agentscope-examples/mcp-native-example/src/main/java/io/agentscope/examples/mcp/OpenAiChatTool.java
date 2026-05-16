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
package io.agentscope.examples.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.mcp.tool.Tool;
import java.util.HashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Tool that calls OpenAI Chat API.
 *
 * <p>Requires the environment variable: OPENAI_API_KEY
 */
public class OpenAiChatTool implements Tool {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiChatTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String getName() {
        return "openai.chat";
    }

    @Override
    public String getDescription() {
        return "Calls OpenAI Chat API to generate responses";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        properties.put("prompt", Map.of("type", "string", "description", "The user prompt"));
        properties.put(
                "model",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "OpenAI model (default: gpt-3.5-turbo)",
                        "default",
                        "gpt-3.5-turbo"));
        properties.put(
                "temperature",
                Map.of(
                        "type",
                        "number",
                        "description",
                        "Temperature for randomness (0-2, default: 0.7)",
                        "default",
                        0.7));
        properties.put(
                "max_tokens",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Max tokens in response (default: 100)",
                        "default",
                        100));

        schema.put("properties", properties);
        schema.put("required", new String[] {"prompt"});
        return schema;
    }

    @Override
    public Object execute(Object arguments) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI API key not configured. Set OPENAI_API_KEY environment variable.");
        }

        if (!(arguments instanceof Map)) {
            throw new IllegalArgumentException("Arguments must be a map");
        }

        Map<String, Object> args = (Map<String, Object>) arguments;
        String prompt = (String) args.get("prompt");
        String model = (String) args.getOrDefault("model", "gpt-3.5-turbo");
        double temperature = ((Number) args.getOrDefault("temperature", 0.7)).doubleValue();
        int maxTokens = ((Number) args.getOrDefault("max_tokens", 100)).intValue();

        // Build request payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", new Object[] {Map.of("role", "user", "content", prompt)});

        String jsonPayload = objectMapper.writeValueAsString(payload);

        // Send request to OpenAI
        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request req =
                new Request.Builder()
                        .url(OPENAI_API_URL)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String errorBody = resp.body() == null ? "" : resp.body().string();
                throw new RuntimeException("OpenAI API error (" + resp.code() + "): " + errorBody);
            }

            String respBody = resp.body() == null ? "" : resp.body().string();
            Map<String, Object> result = objectMapper.readValue(respBody, Map.class);

            // Extract the assistant's message from the response
            if (result.containsKey("choices")) {
                java.util.List<Map<String, Object>> choices =
                        (java.util.List<Map<String, Object>>) result.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    return Map.of("response", message.get("content"), "model", model);
                }
            }

            return Map.of("response", "No response from OpenAI", "model", model);
        }
    }
}
