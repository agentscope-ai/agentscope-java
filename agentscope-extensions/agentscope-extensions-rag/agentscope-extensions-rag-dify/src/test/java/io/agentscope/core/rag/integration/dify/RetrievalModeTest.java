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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RetrievalModeTest {

    @Test
    void testGetValue() {
        assertEquals("keyword_search", RetrievalMode.KEYWORD_SEARCH.getValue());
        assertEquals("semantic_search", RetrievalMode.SEMANTIC_SEARCH.getValue());
        assertEquals("hybrid_search", RetrievalMode.HYBRID_SEARCH.getValue());
        assertEquals("full_text_search", RetrievalMode.FULL_TEXT_SEARCH.getValue());
    }

    @Test
    void testFromValue() {
        assertEquals(RetrievalMode.KEYWORD_SEARCH, RetrievalMode.fromValue("keyword_search"));
        assertEquals(RetrievalMode.SEMANTIC_SEARCH, RetrievalMode.fromValue("semantic_search"));
        assertEquals(RetrievalMode.HYBRID_SEARCH, RetrievalMode.fromValue("hybrid_search"));
        assertEquals(RetrievalMode.FULL_TEXT_SEARCH, RetrievalMode.fromValue("full_text_search"));
    }

    @Test
    void testFromValueCaseInsensitive() {
        assertEquals(RetrievalMode.KEYWORD_SEARCH, RetrievalMode.fromValue("KEYWORD_SEARCH"));
        assertEquals(RetrievalMode.SEMANTIC_SEARCH, RetrievalMode.fromValue("Semantic_Search"));
        assertEquals(RetrievalMode.HYBRID_SEARCH, RetrievalMode.fromValue("HyBrId_SeArCh"));
    }

    @Test
    void testFromValueInvalid() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalMode.fromValue("invalid"));
        assertThrows(IllegalArgumentException.class, () -> RetrievalMode.fromValue(null));
        assertThrows(IllegalArgumentException.class, () -> RetrievalMode.fromValue(""));
    }

    @Test
    void testToString() {
        assertEquals("keyword_search", RetrievalMode.KEYWORD_SEARCH.toString());
        assertEquals("semantic_search", RetrievalMode.SEMANTIC_SEARCH.toString());
        assertEquals("hybrid_search", RetrievalMode.HYBRID_SEARCH.toString());
        assertEquals("full_text_search", RetrievalMode.FULL_TEXT_SEARCH.toString());
    }
}
