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
package io.agentscope.core.rag.integration.dify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RerankConfigTest {

    @Test
    void testBuilderDefaults() {
        RerankConfig config = RerankConfig.builder().build();
        assertNotNull(config);
        assertNull(config.getModelName());
    }

    @Test
    void testBuilderWithAllFields() {
        RerankConfig config = RerankConfig.builder().modelName("rerank-model").build();
        assertEquals("rerank-model", config.getModelName());
    }

    @Test
    void testToString() {
        RerankConfig config = RerankConfig.builder().modelName("test-model").build();
        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("test-model"));
    }
}
