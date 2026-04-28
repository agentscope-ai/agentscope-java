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
package io.agentscope.core.formatter.dashscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DashScopeSearchOptions} class.
 */
class DashScopeSearchOptionsTest {

    @Test
    void testConstructor() {
        DashScopeSearchOptions searchOptions = new DashScopeSearchOptions();

        assertNotNull(searchOptions);
        assertNull(searchOptions.getEnableSource());
        assertNull(searchOptions.getEnableCitation());
        assertNull(searchOptions.getCitationFormat());
        assertNull(searchOptions.getForcedSearch());
        assertNull(searchOptions.getSearchStrategy());
        assertNull(searchOptions.getEnableSearchExtension());
    }

    @Test
    void testGettersAndSetters() {
        DashScopeSearchOptions searchOptions = new DashScopeSearchOptions();
        searchOptions.setEnableSource(true);
        searchOptions.setEnableCitation(true);
        searchOptions.setCitationFormat("[<number>]");
        searchOptions.setForcedSearch(true);
        searchOptions.setSearchStrategy(DashScopeSearchOptions.SearchStrategy.TURBO);
        searchOptions.setEnableSearchExtension(true);
        searchOptions.setPrependSearchResult(true);

        assertNotNull(searchOptions);
        assertTrue(searchOptions.getEnableSource());
        assertTrue(searchOptions.getEnableCitation());
        assertEquals("[<number>]", searchOptions.getCitationFormat());
        assertTrue(searchOptions.getForcedSearch());
        assertSame(DashScopeSearchOptions.SearchStrategy.TURBO, searchOptions.getSearchStrategy());
        assertTrue(searchOptions.getEnableSearchExtension());
        assertTrue(searchOptions.getPrependSearchResult());
    }

    @Test
    void testBuilderDefault() {
        DashScopeSearchOptions searchOptions = DashScopeSearchOptions.builder().build();

        assertNotNull(searchOptions);
        assertNull(searchOptions.getEnableSource());
        assertNull(searchOptions.getEnableCitation());
        assertNull(searchOptions.getCitationFormat());
        assertNull(searchOptions.getForcedSearch());
        assertNull(searchOptions.getSearchStrategy());
        assertNull(searchOptions.getEnableSearchExtension());
    }

    @Test
    void testBuilderWithValues() {
        DashScopeSearchOptions searchOptions =
                DashScopeSearchOptions.builder()
                        .enableSource(true)
                        .enableCitation(true)
                        .citationFormat("[<number>]")
                        .forcedSearch(true)
                        .searchStrategy(DashScopeSearchOptions.SearchStrategy.TURBO)
                        .enableSearchExtension(true)
                        .prependSearchResult(true)
                        .build();

        assertNotNull(searchOptions);
        assertTrue(searchOptions.getEnableSource());
        assertTrue(searchOptions.getEnableCitation());
        assertEquals("[<number>]", searchOptions.getCitationFormat());
        assertTrue(searchOptions.getForcedSearch());
        assertSame(DashScopeSearchOptions.SearchStrategy.TURBO, searchOptions.getSearchStrategy());
        assertTrue(searchOptions.getEnableSearchExtension());
        assertTrue(searchOptions.getPrependSearchResult());
    }
}
