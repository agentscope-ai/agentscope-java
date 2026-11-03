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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.CitationsSearchResultLocation;
import com.anthropic.models.messages.CitationsWebSearchResultLocation;
import com.anthropic.models.messages.TextCitation;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnthropicCitationConverterTest {

    @Test
    void charLocationBecomesProviderMetadata() {
        CitationCharLocation providerCitation =
                CitationCharLocation.builder()
                        .citedText("source text")
                        .documentIndex(1)
                        .documentTitle("guide.txt")
                        .fileId(Optional.empty())
                        .startCharIndex(4)
                        .endCharIndex(15)
                        .build();

        Map<String, Object> citation =
                AnthropicCitationConverter.convert(TextCitation.ofCharLocation(providerCitation), 3)
                        .orElseThrow();

        assertEquals("anthropic.citations", AnthropicCitationConverter.MD_CITATIONS);
        assertEquals("char_location", citation.get("type"));
        assertEquals(3L, citation.get("block_index"));
        assertEquals("source text", citation.get("cited_text"));
        assertEquals(1L, citation.get("document_index"));
        assertEquals("guide.txt", citation.get("document_title"));
        assertEquals(4L, citation.get("start_char_index"));
        assertEquals(15L, citation.get("end_char_index"));
        assertFalse(citation.containsKey("file_id"));
    }

    @Test
    void contentBlockLocationBecomesProviderMetadata() {
        CitationContentBlockLocation providerCitation =
                CitationContentBlockLocation.builder()
                        .citedText("source blocks")
                        .documentIndex(2)
                        .documentTitle(Optional.empty())
                        .fileId("file-2")
                        .startBlockIndex(3)
                        .endBlockIndex(6)
                        .build();

        Map<String, Object> citation =
                AnthropicCitationConverter.convert(
                                CitationsDelta.Citation.ofContentBlockLocation(providerCitation), 4)
                        .orElseThrow();

        assertEquals("content_block_location", citation.get("type"));
        assertEquals(4L, citation.get("block_index"));
        assertEquals("source blocks", citation.get("cited_text"));
        assertEquals(2L, citation.get("document_index"));
        assertEquals("file-2", citation.get("file_id"));
        assertEquals(3L, citation.get("start_block_index"));
        assertEquals(6L, citation.get("end_block_index"));
        assertFalse(citation.containsKey("document_title"));
    }

    @Test
    void unsupportedSearchCitationVariantsAreIgnored() {
        CitationsWebSearchResultLocation webSearchCitation =
                CitationsWebSearchResultLocation.builder()
                        .citedText("web result")
                        .encryptedIndex("encrypted-index")
                        .title(Optional.empty())
                        .url("https://example.com")
                        .build();
        CitationsSearchResultLocation searchCitation =
                CitationsSearchResultLocation.builder()
                        .citedText("search result")
                        .startBlockIndex(0)
                        .endBlockIndex(1)
                        .searchResultIndex(0)
                        .source("knowledge-base")
                        .title(Optional.empty())
                        .build();

        assertTrue(
                AnthropicCitationConverter.convert(
                                TextCitation.ofWebSearchResultLocation(webSearchCitation), 0)
                        .isEmpty());
        assertTrue(
                AnthropicCitationConverter.convert(
                                CitationsDelta.Citation.ofSearchResultLocation(searchCitation), 0)
                        .isEmpty());
    }
}
