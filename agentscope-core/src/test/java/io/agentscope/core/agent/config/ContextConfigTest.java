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
package io.agentscope.core.agent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsMatchDocumentedValues() {
        ContextConfig cfg = ContextConfig.defaults();
        assertEquals(0.8, cfg.triggerRatio());
        assertEquals(0.1, cfg.reserveRatio());
        assertEquals(3000, cfg.toolResultLimit());
        assertTrue(cfg.compressionPrompt().contains("<system-hint>"));
        assertTrue(cfg.summaryTemplate().contains("{task_overview}"));
        assertNotNull(cfg.summarySchema());
        assertEquals("object", cfg.summarySchema().get("type"));
    }

    @Test
    void builderOverridesIndividualFields() {
        Map<String, Object> customSchema = Map.of("type", "object", "title", "Custom");
        ContextConfig cfg =
                ContextConfig.builder()
                        .triggerRatio(0.5)
                        .reserveRatio(0.05)
                        .compressionPrompt("custom prompt")
                        .summaryTemplate("custom template")
                        .summarySchema(customSchema)
                        .toolResultLimit(1500)
                        .build();
        assertEquals(0.5, cfg.triggerRatio());
        assertEquals(0.05, cfg.reserveRatio());
        assertEquals("custom prompt", cfg.compressionPrompt());
        assertEquals("custom template", cfg.summaryTemplate());
        assertEquals(customSchema, cfg.summarySchema());
        assertEquals(1500, cfg.toolResultLimit());
    }

    @Test
    void rejectsInvalidTriggerRatio() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().triggerRatio(0.0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().triggerRatio(0.95).build());
    }

    @Test
    void rejectsReserveAtOrAboveTrigger() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().triggerRatio(0.5).reserveRatio(0.5).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().triggerRatio(0.5).reserveRatio(0.6).build());
    }

    @Test
    void rejectsNonPositiveToolResultLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().toolResultLimit(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextConfig.builder().toolResultLimit(-1).build());
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        ContextConfig original =
                ContextConfig.builder()
                        .triggerRatio(0.7)
                        .reserveRatio(0.05)
                        .compressionPrompt("zip it")
                        .summaryTemplate("{task_overview}")
                        .toolResultLimit(500)
                        .build();
        String json = mapper.writeValueAsString(original);
        ContextConfig decoded = mapper.readValue(json, ContextConfig.class);
        assertEquals(original, decoded);
        assertTrue(json.contains("\"trigger_ratio\""));
        assertTrue(json.contains("\"tool_result_limit\""));
    }

    @Test
    void jsonDeserializationOmittingFieldsUsesDefaults() throws Exception {
        ContextConfig decoded = mapper.readValue("{\"trigger_ratio\":0.6}", ContextConfig.class);
        assertEquals(0.6, decoded.triggerRatio());
        assertEquals(0.1, decoded.reserveRatio());
        assertEquals(3000, decoded.toolResultLimit());
    }
}
