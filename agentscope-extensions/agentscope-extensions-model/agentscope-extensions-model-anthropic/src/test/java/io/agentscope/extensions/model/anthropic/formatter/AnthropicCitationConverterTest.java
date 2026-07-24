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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.CitationsSearchResultLocation;
import com.anthropic.models.messages.CitationsWebSearchResultLocation;
import com.anthropic.models.messages.TextCitation;
import io.agentscope.core.message.Citation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnthropicCitationConverterTest {

    @Test
    void charLocationRoundTrip() {
        CitationCharLocation providerCitation =
                CitationCharLocation.builder()
                        .citedText("source text")
                        .documentIndex(1)
                        .documentTitle("guide.txt")
                        .fileId(Optional.empty())
                        .startCharIndex(4)
                        .endCharIndex(15)
                        .build();

        Citation.CharLocation coreCitation =
                assertInstanceOf(
                        Citation.CharLocation.class,
                        AnthropicCitationConverter.convert(
                                        TextCitation.ofCharLocation(providerCitation))
                                .orElseThrow());
        var convertedBack =
                AnthropicCitationConverter.convertToParams(List.of(coreCitation))
                        .get(0)
                        .asCharLocation();

        assertEquals("source text", coreCitation.citedText());
        assertEquals("guide.txt", coreCitation.documentTitle());
        assertEquals(4, coreCitation.startCharIndex());
        assertEquals(15, coreCitation.endCharIndex());
        assertEquals(coreCitation.citedText(), convertedBack.citedText());
        assertEquals(coreCitation.documentIndex(), convertedBack.documentIndex());
        assertEquals(coreCitation.startCharIndex(), convertedBack.startCharIndex());
        assertEquals(coreCitation.endCharIndex(), convertedBack.endCharIndex());
    }

    @Test
    void contentBlockLocationRoundTrip() {
        CitationContentBlockLocation providerCitation =
                CitationContentBlockLocation.builder()
                        .citedText("source blocks")
                        .documentIndex(2)
                        .documentTitle(Optional.empty())
                        .fileId("file-2")
                        .startBlockIndex(3)
                        .endBlockIndex(6)
                        .build();

        Citation.ContentBlockLocation coreCitation =
                assertInstanceOf(
                        Citation.ContentBlockLocation.class,
                        AnthropicCitationConverter.convert(
                                        TextCitation.ofContentBlockLocation(providerCitation))
                                .orElseThrow());
        var convertedBack =
                AnthropicCitationConverter.convertToParams(List.of(coreCitation))
                        .get(0)
                        .asContentBlockLocation();

        assertEquals("file-2", coreCitation.fileId());
        assertEquals(3, coreCitation.startBlockIndex());
        assertEquals(6, coreCitation.endBlockIndex());
        assertEquals(coreCitation.citedText(), convertedBack.citedText());
        assertEquals(coreCitation.documentIndex(), convertedBack.documentIndex());
        assertEquals(coreCitation.startBlockIndex(), convertedBack.startBlockIndex());
        assertEquals(coreCitation.endBlockIndex(), convertedBack.endBlockIndex());
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
                                TextCitation.ofWebSearchResultLocation(webSearchCitation))
                        .isEmpty());
        assertTrue(
                AnthropicCitationConverter.convert(
                                CitationsDelta.Citation.ofSearchResultLocation(searchCitation))
                        .isEmpty());
    }
}
