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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope search options DTO.
 *
 * <p>This class represents the search strategy configuration in a DashScope API request.
 * It is used to control internet search behavior when enable_search is true.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "enable_source": true,
 *   "enable_citation": true,
 *   "citation_format": "[<number>]",
 *   "forced_search": false,
 *   "search_strategy": "turbo",
 *   "enable_search_extension": false,
 *   "prepend_search_result": false
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeSearchOptions {

    /**
     * Whether to display searched information in the return results.
     * Default value is false.
     */
    @JsonProperty("enable_source")
    private Boolean enableSource;

    /**
     * Whether to enable citation markers like [1] or [ref_1].
     * Only effective when enable_source is true.
     * Default value is false.
     */
    @JsonProperty("enable_citation")
    private Boolean enableCitation;

    /**
     * The style of citation markers.
     * Only effective when enable_citation is true.
     * Default value is "[<number>]".
     * Optional values: "[<number>]", "[ref_<number>]".
     */
    @JsonProperty("citation_format")
    private String citationFormat;

    /**
     * Whether to force enable search.
     * Default value is false.
     */
    @JsonProperty("forced_search")
    private Boolean forcedSearch;

    /**
     * The strategy for searching internet information.
     * Default value is "turbo".
     * Optional values: "turbo", "max", "agent", "agent_max".
     */
    @JsonProperty("search_strategy")
    private SearchStrategy searchStrategy;

    /**
     * Whether to enable specific domain enhancement.
     * Default value is false.
     */
    @JsonProperty("enable_search_extension")
    private Boolean enableSearchExtension;

    /**
     * In streaming output with enable_source true, configures whether the first
     * returned data packet contains only search source information.
     * Default value is false.
     * Note: Currently not supported in DashScope Java SDK according to docs,
     * but included for completeness based on API spec.
     */
    @JsonProperty("prepend_search_result")
    private Boolean prependSearchResult;

    public DashScopeSearchOptions() {}

    public Boolean getEnableSource() {
        return enableSource;
    }

    public void setEnableSource(Boolean enableSource) {
        this.enableSource = enableSource;
    }

    public Boolean getEnableCitation() {
        return enableCitation;
    }

    public void setEnableCitation(Boolean enableCitation) {
        this.enableCitation = enableCitation;
    }

    public String getCitationFormat() {
        return citationFormat;
    }

    public void setCitationFormat(String citationFormat) {
        this.citationFormat = citationFormat;
    }

    public Boolean getForcedSearch() {
        return forcedSearch;
    }

    public void setForcedSearch(Boolean forcedSearch) {
        this.forcedSearch = forcedSearch;
    }

    public SearchStrategy getSearchStrategy() {
        return searchStrategy;
    }

    public void setSearchStrategy(SearchStrategy searchStrategy) {
        this.searchStrategy = searchStrategy;
    }

    public Boolean getEnableSearchExtension() {
        return enableSearchExtension;
    }

    public void setEnableSearchExtension(Boolean enableSearchExtension) {
        this.enableSearchExtension = enableSearchExtension;
    }

    public Boolean getPrependSearchResult() {
        return prependSearchResult;
    }

    public void setPrependSearchResult(Boolean prependSearchResult) {
        this.prependSearchResult = prependSearchResult;
    }

    public enum SearchStrategy {
        /**
         * Taking into account both responsiveness and search effect, it is suitable for most scenarios.(default)
         */
        @JsonProperty("turbo")
        TURBO,

        /**
         * With a more comprehensive search strategy, call multi-source search engines for more exhaustive
         * search results, but response times may be longer
         */
        @JsonProperty("max")
        MAX,

        /**
         * Web search tools and large models can be called multiple times to achieve multiple rounds of information
         * retrieval and content integration.
         * <p>
         * This strategy only works with qwen3.5-plus, qwen3.5-plus-2026-02-15, qwen3.5-flash, qwen3.5-flash-2026-02-23,
         * qwen3-max with qwen3-max-2026-01-23 (streaming only), qwen3-max-2026-01-23 non-thinking mode, qwen3-max-2025-09-23.
         * When this strategy is enabled, only the search source (enable_source: true) is supported,
         * and other web search options are not available.
         */
        @JsonProperty("agent")
        AGENT,

        /**
         * Web extractor is supported on the basis of agent policies.
         * <p>
         * This strategy is only applicable to qwen3.5-plus, qwen3.5-plus-2026-02-15, qwen3.5-flash,
         * qwen3.5-flash-2026-02-23, and qwen3-max and qwen3-max-2026-01-23 thinking modes.
         * When this strategy is enabled, only the search source (enable_source: true) is supported,
         * and other web search options are not available.
         */
        @JsonProperty("agent_max")
        AGENT_MAX
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeSearchOptions searchOptions = new DashScopeSearchOptions();

        public Builder enableSource(Boolean enableSource) {
            searchOptions.setEnableSource(enableSource);
            return this;
        }

        public Builder enableCitation(Boolean enableCitation) {
            searchOptions.setEnableCitation(enableCitation);
            return this;
        }

        public Builder citationFormat(String citationFormat) {
            searchOptions.setCitationFormat(citationFormat);
            return this;
        }

        public Builder forcedSearch(Boolean forcedSearch) {
            searchOptions.setForcedSearch(forcedSearch);
            return this;
        }

        public Builder searchStrategy(SearchStrategy searchStrategy) {
            searchOptions.setSearchStrategy(searchStrategy);
            return this;
        }

        public Builder enableSearchExtension(Boolean enableSearchExtension) {
            searchOptions.setEnableSearchExtension(enableSearchExtension);
            return this;
        }

        public Builder prependSearchResult(Boolean prependSearchResult) {
            searchOptions.setPrependSearchResult(prependSearchResult);
            return this;
        }

        public DashScopeSearchOptions build() {
            return searchOptions;
        }
    }
}
