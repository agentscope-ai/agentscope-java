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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.state.State;
import java.util.Objects;

/**
 * A source location cited by a model-generated {@link TextBlock}.
 *
 * <p>Citations are attached to the text block whose claim they support. Location indices follow
 * the source provider's conventions; end indices are exclusive.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Citation.PageLocation.class, name = "page_location"),
    @JsonSubTypes.Type(value = Citation.CharLocation.class, name = "char_location"),
    @JsonSubTypes.Type(value = Citation.ContentBlockLocation.class, name = "content_block_location")
})
public sealed interface Citation extends State
        permits Citation.PageLocation, Citation.CharLocation, Citation.ContentBlockLocation {

    /**
     * A citation into a PDF document, using 1-based page numbers and an exclusive end page.
     *
     * @param citedText text extracted from the cited source range
     * @param documentIndex zero-based document index in the model request
     * @param documentTitle optional document title
     * @param fileId optional provider file identifier
     * @param startPageNumber one-based first cited page
     * @param endPageNumber exclusive one-based end page
     */
    record PageLocation(
            String citedText,
            long documentIndex,
            String documentTitle,
            String fileId,
            long startPageNumber,
            long endPageNumber)
            implements Citation {

        public PageLocation {
            Objects.requireNonNull(citedText, "citedText cannot be null");
        }
    }

    /**
     * A citation into a plain-text document, using zero-based character indices.
     *
     * @param citedText text extracted from the cited source range
     * @param documentIndex zero-based document index in the model request
     * @param documentTitle optional document title
     * @param fileId optional provider file identifier
     * @param startCharIndex zero-based first cited character
     * @param endCharIndex exclusive end character
     */
    record CharLocation(
            String citedText,
            long documentIndex,
            String documentTitle,
            String fileId,
            long startCharIndex,
            long endCharIndex)
            implements Citation {

        public CharLocation {
            Objects.requireNonNull(citedText, "citedText cannot be null");
        }
    }

    /**
     * A citation into a custom-content document, using zero-based content-block indices.
     *
     * @param citedText text extracted from the cited source blocks
     * @param documentIndex zero-based document index in the model request
     * @param documentTitle optional document title
     * @param fileId optional provider file identifier
     * @param startBlockIndex zero-based first cited content block
     * @param endBlockIndex exclusive end content block
     */
    record ContentBlockLocation(
            String citedText,
            long documentIndex,
            String documentTitle,
            String fileId,
            long startBlockIndex,
            long endBlockIndex)
            implements Citation {

        public ContentBlockLocation {
            Objects.requireNonNull(citedText, "citedText cannot be null");
        }
    }
}
