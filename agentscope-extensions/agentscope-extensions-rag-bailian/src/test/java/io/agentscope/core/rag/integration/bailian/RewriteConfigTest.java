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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class RewriteConfigTest {

    @Test
    void testDefaultBuilder() {
        RewriteConfig config = RewriteConfig.builder().build();

        assertNotNull(config);
        assertEquals("conv-rewrite-qwen-1.8b", config.getModelName());
    }

    @Test
    void testCustomModelName() {
        RewriteConfig config = RewriteConfig.builder().modelName("custom-model").build();

        assertNotNull(config);
        assertEquals("custom-model", config.getModelName());
    }

    @Test
    void testGetModelName() {
        RewriteConfig config = RewriteConfig.builder().modelName("conv-rewrite-qwen-1.8b").build();

        assertEquals("conv-rewrite-qwen-1.8b", config.getModelName());
    }

    @Test
    void testBuilderChaining() {
        RewriteConfig config =
                RewriteConfig.builder().modelName("model1").modelName("model2").build();

        assertNotNull(config);
        assertEquals("model2", config.getModelName());
    }
}
