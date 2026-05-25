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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummarySchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsNullFields() {
        assertThrows(
                NullPointerException.class,
                () -> new SummarySchema(null, "current", "important", "next", "preserve"));
        assertThrows(
                NullPointerException.class,
                () -> new SummarySchema("overview", null, "important", "next", "preserve"));
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        SummarySchema sample =
                new SummarySchema(
                        "build a CLI",
                        "design phase",
                        "we use picocli",
                        "implement parser",
                        "user prefers terse output");
        String json = mapper.writeValueAsString(sample);
        SummarySchema decoded = mapper.readValue(json, SummarySchema.class);
        assertEquals(sample, decoded);
        assertTrue(json.contains("\"task_overview\""));
        assertTrue(json.contains("\"context_to_preserve\""));
    }

    @Test
    void jsonSchemaHasExpectedShape() {
        Map<String, Object> schema = SummarySchema.jsonSchema();
        assertEquals("object", schema.get("type"));
        assertEquals("SummarySchema", schema.get("title"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertEquals(5, properties.size());
        assertTrue(properties.containsKey("task_overview"));
        assertTrue(properties.containsKey("current_state"));
        assertTrue(properties.containsKey("important_discoveries"));
        assertTrue(properties.containsKey("next_steps"));
        assertTrue(properties.containsKey("context_to_preserve"));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskOverview = (Map<String, Object>) properties.get("task_overview");
        assertEquals("string", taskOverview.get("type"));
        assertEquals(SummarySchema.TASK_OVERVIEW_MAX_LENGTH, taskOverview.get("maxLength"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(5, required.size());
    }

    @Test
    void jsonSchemaReturnsFreshInstance() {
        Map<String, Object> first = SummarySchema.jsonSchema();
        Map<String, Object> second = SummarySchema.jsonSchema();
        assertNotSame(first, second);
        first.put("$injected", true);
        assertTrue(!second.containsKey("$injected"));
    }
}
