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
import com.anthropic.models.messages.CitationCharLocationParam;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationContentBlockLocationParam;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.CitationPageLocationParam;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.TextCitationParam;
import io.agentscope.core.message.Citation;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converts Anthropic document citation unions to provider-neutral AgentScope citations. */
final class AnthropicCitationConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCitationConverter.class);

    private AnthropicCitationConverter() {}

    static List<Citation> convert(List<TextCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream()
                .map(AnthropicCitationConverter::convert)
                .flatMap(Optional::stream)
                .toList();
    }

    static Optional<Citation> convert(TextCitation citation) {
        if (citation == null) {
            return Optional.empty();
        }
        if (citation.isPageLocation()) {
            return Optional.of(convert(citation.asPageLocation()));
        }
        if (citation.isCharLocation()) {
            return Optional.of(convert(citation.asCharLocation()));
        }
        if (citation.isContentBlockLocation()) {
            return Optional.of(convert(citation.asContentBlockLocation()));
        }
        logUnsupportedCitation(
                citation.isWebSearchResultLocation(), citation.isSearchResultLocation());
        return Optional.empty();
    }

    static Optional<Citation> convert(CitationsDelta.Citation citation) {
        if (citation == null) {
            return Optional.empty();
        }
        if (citation.isPageLocation()) {
            return Optional.of(convert(citation.asPageLocation()));
        }
        if (citation.isCharLocation()) {
            return Optional.of(convert(citation.asCharLocation()));
        }
        if (citation.isContentBlockLocation()) {
            return Optional.of(convert(citation.asContentBlockLocation()));
        }
        logUnsupportedCitation(
                citation.isWebSearchResultLocation(), citation.isSearchResultLocation());
        return Optional.empty();
    }

    static List<TextCitationParam> convertToParams(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream()
                .map(AnthropicCitationConverter::convertToParam)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<TextCitationParam> convertToParam(Citation citation) {
        if (citation instanceof Citation.PageLocation page) {
            CitationPageLocationParam.Builder builder =
                    CitationPageLocationParam.builder()
                            .citedText(page.citedText())
                            .documentIndex(page.documentIndex())
                            .documentTitle(Optional.ofNullable(page.documentTitle()))
                            .startPageNumber(page.startPageNumber())
                            .endPageNumber(page.endPageNumber());
            return Optional.of(TextCitationParam.ofPageLocation(builder.build()));
        }
        if (citation instanceof Citation.CharLocation character) {
            CitationCharLocationParam.Builder builder =
                    CitationCharLocationParam.builder()
                            .citedText(character.citedText())
                            .documentIndex(character.documentIndex())
                            .documentTitle(Optional.ofNullable(character.documentTitle()))
                            .startCharIndex(character.startCharIndex())
                            .endCharIndex(character.endCharIndex());
            return Optional.of(TextCitationParam.ofCharLocation(builder.build()));
        }
        if (citation instanceof Citation.ContentBlockLocation contentBlock) {
            CitationContentBlockLocationParam.Builder builder =
                    CitationContentBlockLocationParam.builder()
                            .citedText(contentBlock.citedText())
                            .documentIndex(contentBlock.documentIndex())
                            .documentTitle(Optional.ofNullable(contentBlock.documentTitle()))
                            .startBlockIndex(contentBlock.startBlockIndex())
                            .endBlockIndex(contentBlock.endBlockIndex());
            return Optional.of(TextCitationParam.ofContentBlockLocation(builder.build()));
        }
        return Optional.empty();
    }

    private static Citation.PageLocation convert(CitationPageLocation citation) {
        return new Citation.PageLocation(
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle().orElse(null),
                citation.fileId().orElse(null),
                citation.startPageNumber(),
                citation.endPageNumber());
    }

    private static Citation.CharLocation convert(CitationCharLocation citation) {
        return new Citation.CharLocation(
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle().orElse(null),
                citation.fileId().orElse(null),
                citation.startCharIndex(),
                citation.endCharIndex());
    }

    private static Citation.ContentBlockLocation convert(CitationContentBlockLocation citation) {
        return new Citation.ContentBlockLocation(
                citation.citedText(),
                citation.documentIndex(),
                citation.documentTitle().orElse(null),
                citation.fileId().orElse(null),
                citation.startBlockIndex(),
                citation.endBlockIndex());
    }

    private static void logUnsupportedCitation(boolean webSearchResult, boolean searchResult) {
        String variant =
                webSearchResult
                        ? "web_search_result_location"
                        : searchResult ? "search_result_location" : "unknown";
        log.debug("Ignoring unsupported Anthropic citation variant: {}", variant);
    }
}
