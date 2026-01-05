/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaOptions.
 */
@DisplayName("OllamaOptions Unit Tests")
class OllamaOptionsTest {

    private OllamaOptions.Builder builder;

    @BeforeEach
    void setUp() {
        builder = OllamaOptions.builder();
    }

    @Test
    @DisplayName("Should create OllamaOptions with builder pattern")
    void testBuilderPattern() {
        OllamaOptions options = builder.temperature(0.7).topP(0.9).numCtx(2048).build();

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
        assertEquals(0.9, options.getTopP());
        assertEquals(Integer.valueOf(2048), options.getNumCtx());
    }

    @Test
    @DisplayName("Should create empty OllamaOptions with default builder")
    void testDefaultBuilder() {
        OllamaOptions options = OllamaOptions.builder().build();

        assertNotNull(options);
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getNumCtx());
    }

    @Test
    @DisplayName("Should copy OllamaOptions correctly")
    void testCopy() {
        OllamaOptions original =
                OllamaOptions.builder().temperature(0.7).topP(0.9).numCtx(2048).build();

        OllamaOptions copy = original.copy();

        assertNotNull(copy);
        assertEquals(original.getTemperature(), copy.getTemperature());
        assertEquals(original.getTopP(), copy.getTopP());
        assertEquals(original.getNumCtx(), copy.getNumCtx());
        assertNotSame(original, copy); // Should be different instances
    }

    @Test
    @DisplayName("Should convert from GenerateOptions correctly")
    void testFromGenerateOptions() {
        GenerateOptions genOptions =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topP(0.9)
                        .topK(40)
                        .maxTokens(100)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .seed(123L)
                        .build();

        OllamaOptions options = OllamaOptions.fromGenerateOptions(genOptions);

        assertNotNull(options);
        assertEquals(0.8, options.getTemperature());
        assertEquals(0.9, options.getTopP());
        assertEquals(Integer.valueOf(40), options.getTopK());
        assertEquals(Integer.valueOf(100), options.getNumPredict());
        assertEquals(0.5, options.getFrequencyPenalty());
        assertEquals(0.3, options.getPresencePenalty());
        assertEquals(Integer.valueOf(123), options.getSeed());
    }

    @Test
    @DisplayName("Should handle null GenerateOptions in fromGenerateOptions")
    void testFromNullGenerateOptions() {
        OllamaOptions options = OllamaOptions.fromGenerateOptions(null);

        assertNotNull(options);
        // Should create an empty options object
    }

    @Test
    @DisplayName("Should convert to GenerateOptions correctly")
    void testToGenerateOptions() {
        OllamaOptions options =
                OllamaOptions.builder()
                        .temperature(0.7)
                        .topP(0.9)
                        .numPredict(150)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .seed(42)
                        .build();

        GenerateOptions genOptions = options.toGenerateOptions();

        assertNotNull(genOptions);
        assertEquals(0.7, genOptions.getTemperature());
        assertEquals(0.9, genOptions.getTopP());
        assertEquals(Integer.valueOf(150), genOptions.getMaxTokens());
        assertEquals(0.2, genOptions.getFrequencyPenalty());
        assertEquals(0.1, genOptions.getPresencePenalty());
        assertEquals(Long.valueOf(42), genOptions.getSeed());
    }

    @Test
    @DisplayName("Should merge OllamaOptions correctly")
    void testMerge() {
        OllamaOptions baseOptions =
                OllamaOptions.builder().temperature(0.5).topP(0.8).numCtx(1024).build();

        OllamaOptions overrideOptions =
                OllamaOptions.builder()
                        .temperature(0.9) // This should override base value
                        .topK(60) // This should be added
                        .build();

        OllamaOptions merged = baseOptions.merge(overrideOptions);

        assertNotNull(merged);
        assertEquals(0.9, merged.getTemperature()); // From override
        assertEquals(0.8, merged.getTopP()); // From base
        assertEquals(Integer.valueOf(1024), merged.getNumCtx()); // From base
        assertEquals(Integer.valueOf(60), merged.getTopK()); // From override
    }

    @Test
    @DisplayName("Should merge with null other options")
    void testMergeWithNull() {
        OllamaOptions baseOptions = OllamaOptions.builder().temperature(0.5).build();

        OllamaOptions merged = baseOptions.merge((OllamaOptions) null);

        assertNotNull(merged);
        assertEquals(0.5, merged.getTemperature());
        // Should be a copy of the original
    }

    @Test
    @DisplayName("Should merge with GenerateOptions")
    void testMergeWithGenerateOptions() {
        OllamaOptions baseOptions = OllamaOptions.builder().temperature(0.5).topP(0.8).build();

        GenerateOptions genOptions =
                GenerateOptions.builder()
                        .temperature(0.9) // This should override
                        .topK(50)
                        .build();

        OllamaOptions merged = baseOptions.merge(genOptions);

        assertNotNull(merged);
        assertEquals(0.9, merged.getTemperature()); // From genOptions
        assertEquals(0.8, merged.getTopP()); // From base
        assertEquals(Integer.valueOf(50), merged.getTopK()); // From genOptions (via conversion)
    }

    @Test
    @DisplayName("Should convert to Map representation")
    void testToMap() {
        OllamaOptions options =
                OllamaOptions.builder().temperature(0.7).topP(0.9).numCtx(2048).build();

        Map<String, Object> map = options.toMap();

        assertNotNull(map);
        assertEquals(0.7, map.get("temperature"));
        assertEquals(0.9, map.get("top_p"));
        assertEquals(2048, map.get("num_ctx"));
    }

    @Test
    @DisplayName("Should handle all major configuration parameters")
    void testAllParameters() {
        List<String> stopSequences = Arrays.asList("stop1", "stop2");
        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(30)).maxAttempts(3).build();

        OllamaOptions options =
                OllamaOptions.builder()
                        .temperature(0.7)
                        .topP(0.9)
                        .topK(40)
                        .numPredict(150)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.1)
                        .repeatPenalty(1.1)
                        .seed(123)
                        .numCtx(4096)
                        .numBatch(512)
                        .numGPU(1)
                        .stop(stopSequences)
                        .executionConfig(executionConfig)
                        .build();

        assertEquals(0.7, options.getTemperature());
        assertEquals(0.9, options.getTopP());
        assertEquals(Integer.valueOf(40), options.getTopK());
        assertEquals(Integer.valueOf(150), options.getNumPredict());
        assertEquals(0.2, options.getFrequencyPenalty());
        assertEquals(0.1, options.getPresencePenalty());
        assertEquals(1.1, options.getRepeatPenalty());
        assertEquals(Integer.valueOf(123), options.getSeed());
        assertEquals(Integer.valueOf(4096), options.getNumCtx());
        assertEquals(Integer.valueOf(512), options.getNumBatch());
        assertEquals(Integer.valueOf(1), options.getNumGPU());
        assertEquals(stopSequences, options.getStop());
        assertEquals(executionConfig, options.getExecutionConfig());
    }

    @Test
    @DisplayName("Should handle boolean parameters")
    void testBooleanParameters() {
        OllamaOptions options =
                OllamaOptions.builder()
                        .useNUMA(true)
                        .lowVRAM(false)
                        .f16KV(true)
                        .logitsAll(false)
                        .vocabOnly(false)
                        .useMMap(true)
                        .useMLock(false)
                        .penalizeNewline(true)
                        .truncate(true)
                        .build();

        assertTrue(options.getUseNUMA());
        assertFalse(options.getLowVRAM());
        assertTrue(options.getF16KV());
        assertFalse(options.getLogitsAll());
        assertFalse(options.getVocabOnly());
        assertTrue(options.getUseMMap());
        assertFalse(options.getUseMLock());
        assertTrue(options.getPenalizeNewline());
        assertTrue(options.getTruncate());
    }

    @Test
    @DisplayName("Should handle ThinkOption parameter")
    void testThinkOptionParameter() {
        OllamaOptions options =
                OllamaOptions.builder().thinkOption(ThinkOption.ThinkBoolean.ENABLED).build();

        assertNotNull(options.getThinkOption());
        assertEquals(ThinkOption.ThinkBoolean.ENABLED, options.getThinkOption());
    }
}
