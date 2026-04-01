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
package io.agentscope.core.formatter.gemini.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiGenerationConfig Unit Tests")
class GeminiGenerationConfigTest {

    @Test
    @DisplayName("Should set all fields through builder")
    void testBuilder() {
        GeminiGenerationConfig.GeminiThinkingConfig thinkingConfig =
                GeminiGenerationConfig.GeminiThinkingConfig.builder()
                        .includeThoughts(true)
                        .thinkingBudget(256)
                        .thinkingLevel("medium")
                        .build();

        GeminiGenerationConfig config =
                GeminiGenerationConfig.builder()
                        .stopSequences(List.of("END"))
                        .responseMimeType("application/json")
                        .responseSchema(Map.of("type", "object"))
                        .candidateCount(2)
                        .maxOutputTokens(512)
                        .temperature(0.2)
                        .topP(0.8)
                        .topK(20.0)
                        .presencePenalty(0.1)
                        .frequencyPenalty(0.2)
                        .seed(7)
                        .thinkingConfig(thinkingConfig)
                        .build();

        assertEquals(List.of("END"), config.getStopSequences());
        assertEquals("application/json", config.getResponseMimeType());
        assertEquals(Map.of("type", "object"), config.getResponseSchema());
        assertEquals(2, config.getCandidateCount());
        assertEquals(512, config.getMaxOutputTokens());
        assertEquals(0.2, config.getTemperature());
        assertEquals(0.8, config.getTopP());
        assertEquals(20.0, config.getTopK());
        assertEquals(0.1, config.getPresencePenalty());
        assertEquals(0.2, config.getFrequencyPenalty());
        assertEquals(7, config.getSeed());
        assertEquals(thinkingConfig, config.getThinkingConfig());
    }

    @Test
    @DisplayName("Should set fields through setters")
    void testSetters() {
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        GeminiGenerationConfig.GeminiThinkingConfig thinkingConfig =
                new GeminiGenerationConfig.GeminiThinkingConfig();

        config.setStopSequences(List.of("S1", "S2"));
        config.setResponseMimeType("text/plain");
        config.setResponseSchema(Map.of("k", "v"));
        config.setCandidateCount(1);
        config.setMaxOutputTokens(1024);
        config.setTemperature(0.7);
        config.setTopP(0.9);
        config.setTopK(5.0);
        config.setPresencePenalty(0.3);
        config.setFrequencyPenalty(0.4);
        config.setSeed(42);
        config.setThinkingConfig(thinkingConfig);

        assertEquals(List.of("S1", "S2"), config.getStopSequences());
        assertEquals("text/plain", config.getResponseMimeType());
        assertEquals(Map.of("k", "v"), config.getResponseSchema());
        assertEquals(1, config.getCandidateCount());
        assertEquals(1024, config.getMaxOutputTokens());
        assertEquals(0.7, config.getTemperature());
        assertEquals(0.9, config.getTopP());
        assertEquals(5.0, config.getTopK());
        assertEquals(0.3, config.getPresencePenalty());
        assertEquals(0.4, config.getFrequencyPenalty());
        assertEquals(42, config.getSeed());
        assertEquals(thinkingConfig, config.getThinkingConfig());
    }

    @Test
    @DisplayName("Should set nested thinking config fields")
    void testThinkingConfigSetters() {
        GeminiGenerationConfig.GeminiThinkingConfig config =
                new GeminiGenerationConfig.GeminiThinkingConfig();
        config.setIncludeThoughts(false);
        config.setThinkingBudget(128);
        config.setThinkingLevel("low");

        assertEquals(false, config.getIncludeThoughts());
        assertEquals(128, config.getThinkingBudget());
        assertEquals("low", config.getThinkingLevel());
    }

    @Test
    @DisplayName("Should serialize using Gemini field names and omit null fields")
    void testSerialization() {
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.setResponseMimeType("application/json");
        config.setTopK(30.0);
        config.setThinkingConfig(
                GeminiGenerationConfig.GeminiThinkingConfig.builder()
                        .includeThoughts(true)
                        .thinkingBudget(32)
                        .build());

        String json = JsonUtils.getJsonCodec().toJson(config);

        assertTrue(json.contains("\"responseMimeType\":\"application/json\""));
        assertTrue(json.contains("\"topK\":30.0"));
        assertTrue(json.contains("\"thinkingConfig\""));
        assertTrue(json.contains("\"includeThoughts\":true"));
        assertTrue(json.contains("\"thinkingBudget\":32"));
        assertTrue(!json.contains("\"frequencyPenalty\""));
    }
}
