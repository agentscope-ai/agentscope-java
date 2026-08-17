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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReactConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsMatchDocumentedValues() {
        ReactConfig cfg = ReactConfig.defaults();
        assertEquals(20, cfg.maxIters());
        assertFalse(cfg.stopOnReject());
    }

    @Test
    void rejectsNonPositiveMaxIters() {
        assertThrows(IllegalArgumentException.class, () -> new ReactConfig(0, false));
        assertThrows(IllegalArgumentException.class, () -> new ReactConfig(-5, false));
    }

    @Test
    void jsonRoundTripPreservesFields() throws Exception {
        ReactConfig original = new ReactConfig(50, true);
        String json = mapper.writeValueAsString(original);
        ReactConfig decoded = mapper.readValue(json, ReactConfig.class);
        assertEquals(original, decoded);
        assertTrue(json.contains("\"max_iters\":50"));
        assertTrue(json.contains("\"stop_on_reject\":true"));
    }

    @Test
    void jsonDeserializationOmittingFieldsUsesDefaults() throws Exception {
        ReactConfig decoded = mapper.readValue("{}", ReactConfig.class);
        assertEquals(20, decoded.maxIters());
        assertFalse(decoded.stopOnReject());
    }
}
