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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsMatchDocumentedValues() {
        ModelConfig cfg = ModelConfig.defaults();
        assertEquals(3, cfg.maxRetries());
        assertNull(cfg.fallbackModel());
    }

    @Test
    void rejectsNonPositiveMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig(0, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig(-2, null));
    }

    @Test
    void jsonSerializationOmitsFallbackModel() throws Exception {
        ModelConfig cfg = new ModelConfig(5, null);
        String json = mapper.writeValueAsString(cfg);
        assertTrue(json.contains("\"maxRetries\":5"));
        assertTrue(!json.contains("fallbackModel"));
    }
}
