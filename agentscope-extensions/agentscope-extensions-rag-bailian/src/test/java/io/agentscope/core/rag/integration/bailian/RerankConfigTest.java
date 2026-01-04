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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RerankConfigTest {

    @Test
    void testDefaultBuilder() {
        RerankConfig config = RerankConfig.builder().build();
        assertNotNull(config);
        assertEquals("gte-rerank-hybrid", config.getModelName());
        assertNull(config.getRerankMinScore());
        assertNull(config.getRerankTopN());
    }

    @Test
    void testBuilderWithAllFields() {
        RerankConfig config =
                RerankConfig.builder()
                        .modelName("gte-rerank")
                        .rerankMinScore(0.5f)
                        .rerankTopN(10)
                        .build();

        assertNotNull(config);
        assertEquals("gte-rerank", config.getModelName());
        assertEquals(0.5f, config.getRerankMinScore());
        assertEquals(10, config.getRerankTopN());
    }

    @Test
    void testRerankMinScoreTooLow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RerankConfig.builder().rerankMinScore(0.001f).build());
    }

    @Test
    void testRerankMinScoreTooHigh() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RerankConfig.builder().rerankMinScore(1.5f).build());
    }

    @Test
    void testRerankMinScoreValid() {
        RerankConfig config = RerankConfig.builder().rerankMinScore(0.01f).build();
        assertEquals(0.01f, config.getRerankMinScore());

        config = RerankConfig.builder().rerankMinScore(1.0f).build();
        assertEquals(1.0f, config.getRerankMinScore());
    }

    @Test
    void testRerankTopNTooLow() {
        assertThrows(
                IllegalArgumentException.class, () -> RerankConfig.builder().rerankTopN(0).build());
    }

    @Test
    void testRerankTopNTooHigh() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RerankConfig.builder().rerankTopN(21).build());
    }

    @Test
    void testRerankTopNValid() {
        RerankConfig config = RerankConfig.builder().rerankTopN(1).build();
        assertEquals(1, config.getRerankTopN());

        config = RerankConfig.builder().rerankTopN(20).build();
        assertEquals(20, config.getRerankTopN());
    }
}
