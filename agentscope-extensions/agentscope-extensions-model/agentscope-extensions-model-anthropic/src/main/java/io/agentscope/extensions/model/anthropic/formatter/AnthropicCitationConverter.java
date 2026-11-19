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

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.TextCitation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converts Anthropic document citations to provider-specific response metadata. */
final class AnthropicCitationConverter {

    static final String MD_CITATIONS = "anthropic.citations";

    private static final Logger log = LoggerFactory.getLogger(AnthropicCitationConverter.class);

    private AnthropicCitationConverter() {}

    static List<Map<String, Object>> convert(List<TextCitation> citations, long blockIndex) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream()
                .map(citation -> convert(citation, blockIndex))
                .flatMap(Optional::stream)
                .toList();
    }

    static Optional<Map<String, Object>> convert(TextCitation citation, long blockIndex) {
        if (citation == null) {
            return Optional.empty();
        }
        if (citation.isPageLocation()) {
            return Optional.of(convert(citation.asPageLocation(), blockIndex));
        }
        if (citation.isCharLocation()) {
            return Optional.of(convert(citation.asCharLocation(), blockIndex));
        }
        if (citation.isContentBlockLocation()) {
            return Optional.of(convert(citation.asContentBlockLocation(), blockIndex));
        }
        logUnsupportedCitation(
                citation.isWebSearchResultLocation(), citation.isSearchResultLocation());
        return Optional.empty();
    }

    static Optional<Map<String, Object>> convert(
            CitationsDelta.Citation citation, long blockIndex) {
        if (citation == null) {
            return Optional.empty();
        }
        if (citation.isPageLocation()) {
            return Optional.of(convert(citation.asPageLocation(), blockIndex));
        }
        if (citation.isCharLocation()) {
            return Optional.of(convert(citation.asCharLocation(), blockIndex));
        }
        if (citation.isContentBlockLocation()) {
            return Optional.of(convert(citation.asContentBlockLocation(), blockIndex));
        }
        logUnsupportedCitation(
                citation.isWebSearchResultLocation(), citation.isSearchResultLocation());
        return Optional.empty();
    }

    private static Map<String, Object> convert(CitationPageLocation citation, long blockIndex) {
        Map<String, Object> metadata = baseMetadata("page_location", blockIndex);
        populateDocumentMetadata(
                metadata,
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle(),
                citation.fileId());
        metadata.put("start_page_number", citation.startPageNumber());
        metadata.put("end_page_number", citation.endPageNumber());
        return metadata;
    }

    private static Map<String, Object> convert(CitationCharLocation citation, long blockIndex) {
        Map<String, Object> metadata = baseMetadata("char_location", blockIndex);
        populateDocumentMetadata(
                metadata,
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle(),
                citation.fileId());
        metadata.put("start_char_index", citation.startCharIndex());
        metadata.put("end_char_index", citation.endCharIndex());
        return metadata;
    }

    private static Map<String, Object> convert(
            CitationContentBlockLocation citation, long blockIndex) {
        Map<String, Object> metadata = baseMetadata("content_block_location", blockIndex);
        populateDocumentMetadata(
                metadata,
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle(),
                citation.fileId());
        metadata.put("start_block_index", citation.startBlockIndex());
        metadata.put("end_block_index", citation.endBlockIndex());
        return metadata;
    }

    private static Map<String, Object> baseMetadata(String type, long blockIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", type);
        metadata.put("block_index", blockIndex);
        return metadata;
    }

    private static void populateDocumentMetadata(
            Map<String, Object> metadata,
            String citedText,
            long documentIndex,
            Optional<String> documentTitle,
            Optional<String> fileId) {
        metadata.put("cited_text", citedText);
        metadata.put("document_index", documentIndex);
        documentTitle.ifPresent(value -> metadata.put("document_title", value));
        fileId.ifPresent(value -> metadata.put("file_id", value));
    }

    private static void logUnsupportedCitation(boolean webSearchResult, boolean searchResult) {
        String variant =
                webSearchResult
                        ? "web_search_result_location"
                        : searchResult ? "search_result_location" : "unknown";
        log.debug("Ignoring unsupported Anthropic citation variant: {}", variant);
    }
}
