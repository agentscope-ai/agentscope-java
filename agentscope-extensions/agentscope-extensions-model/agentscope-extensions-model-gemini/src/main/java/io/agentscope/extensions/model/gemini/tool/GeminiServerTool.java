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
package io.agentscope.extensions.model.gemini.tool;

import com.google.genai.types.AuthConfig;
import com.google.genai.types.GoogleMaps;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Interval;
import com.google.genai.types.PhishBlockThreshold;
import com.google.genai.types.SearchTypes;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolCodeExecution;
import com.google.genai.types.UrlContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a Gemini built-in tool executed by the model provider.
 *
 * <p>Use the predefined factory methods for Google Search, Google Maps, and URL Context. Code
 * execution can be configured with {@link #builder()} and {@link #CODE_EXECUTION}. Parameters are
 * validated when {@link #toTool()} converts this configuration to the Google GenAI SDK {@link
 * Tool} type.
 *
 * <p>Example:
 *
 * <pre>{@code
 * GeminiServerTool search = GeminiServerTool.googleSearch()
 *     .param("excludeDomains", List.of("example.com"))
 *     .build();
 * }</pre>
 */
public class GeminiServerTool {

    /** Tool type for Gemini URL Context. */
    public static final String URL_CONTEXT = "url_context";

    /** Tool type for Gemini Google Search grounding. */
    public static final String GOOGLE_SEARCH = "google_search";

    /** Tool type for Gemini Google Maps grounding. */
    public static final String GOOGLE_MAP = "google_map";

    /** Tool type for Gemini code execution. */
    public static final String CODE_EXECUTION = "code_execution";

    private final String type;

    private final Map<String, Object> params;

    /**
     * Creates a Gemini server-side tool configuration.
     *
     * <p>Prefer the predefined factory methods when available. The tool type and parameter values
     * are validated by {@link #toTool()}.
     *
     * @param type Tool type, such as {@link #GOOGLE_SEARCH}, {@link #GOOGLE_MAP}, {@link
     *     #URL_CONTEXT}, or {@link #CODE_EXECUTION}
     * @param params Tool-specific parameters; copied defensively, or treated as empty when null
     */
    public GeminiServerTool(String type, Map<String, Object> params) {
        this.type = type;
        this.params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * Converts this configuration to a Google GenAI SDK tool.
     *
     * @return The configured Google GenAI SDK tool
     * @throws IllegalArgumentException if the tool type is unknown or a parameter name, value, or
     *     type is unsupported
     */
    public Tool toTool() {
        if (type == null) {
            throw new IllegalArgumentException("Unknown tool type: null");
        }
        return switch (type) {
            case URL_CONTEXT -> {
                requireNoParams();
                yield Tool.builder().urlContext(UrlContext.builder()).build();
            }
            case GOOGLE_SEARCH -> Tool.builder().googleSearch(buildGoogleSearch()).build();
            case GOOGLE_MAP -> Tool.builder().googleMaps(buildGoogleMaps()).build();
            case CODE_EXECUTION -> {
                requireNoParams();
                yield Tool.builder().codeExecution(ToolCodeExecution.builder()).build();
            }
            default -> throw new IllegalArgumentException("Unknown tool type: " + type);
        };
    }

    private GoogleSearch buildGoogleSearch() {
        GoogleSearch.Builder builder = GoogleSearch.builder();
        params.forEach(
                (name, value) -> {
                    switch (name) {
                        case "searchTypes" ->
                                builder.searchTypes(requireParam(name, value, SearchTypes.class));
                        case "blockingConfidence" -> applyBlockingConfidence(builder, name, value);
                        case "excludeDomains" ->
                                builder.excludeDomains(requireStringList(name, value));
                        case "timeRangeFilter" ->
                                builder.timeRangeFilter(requireCompleteInterval(name, value));
                        default -> throw unsupportedParam(name);
                    }
                });
        return builder.build();
    }

    private Interval requireCompleteInterval(String name, Object value) {
        Interval interval = requireParam(name, value, Interval.class);
        if (interval.startTime().isEmpty() || interval.endTime().isEmpty()) {
            throw new IllegalArgumentException(
                    "Parameter '"
                            + name
                            + "' for Gemini server tool '"
                            + type
                            + "' must contain both startTime and endTime");
        }
        return interval;
    }

    private GoogleMaps buildGoogleMaps() {
        GoogleMaps.Builder builder = GoogleMaps.builder();
        params.forEach(
                (name, value) -> {
                    switch (name) {
                        case "authConfig" ->
                                builder.authConfig(requireParam(name, value, AuthConfig.class));
                        case "enableWidget" ->
                                builder.enableWidget(requireParam(name, value, Boolean.class));
                        default -> throw unsupportedParam(name);
                    }
                });
        return builder.build();
    }

    private void applyBlockingConfidence(GoogleSearch.Builder builder, String name, Object value) {
        if (value instanceof PhishBlockThreshold threshold) {
            builder.blockingConfidence(threshold);
        } else if (value instanceof PhishBlockThreshold.Known known) {
            builder.blockingConfidence(known);
        } else if (value instanceof String stringValue) {
            builder.blockingConfidence(stringValue);
        } else {
            throw invalidParamType(name, "String or PhishBlockThreshold", value);
        }
    }

    private List<String> requireStringList(String name, Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalidParamType(name, "List<String>", value);
        }
        for (Object item : list) {
            if (!(item instanceof String)) {
                throw invalidParamType(name, "List<String>", value);
            }
        }
        @SuppressWarnings("unchecked")
        List<String> strings = (List<String>) list;
        return List.copyOf(strings);
    }

    private <T> T requireParam(String name, Object value, Class<T> expectedType) {
        if (!expectedType.isInstance(value)) {
            throw invalidParamType(name, expectedType.getSimpleName(), value);
        }
        return expectedType.cast(value);
    }

    private void requireNoParams() {
        if (!params.isEmpty()) {
            throw unsupportedParam(params.keySet().iterator().next());
        }
    }

    private IllegalArgumentException unsupportedParam(String name) {
        return new IllegalArgumentException(
                "Unsupported parameter '" + name + "' for Gemini server tool '" + type + "'");
    }

    private IllegalArgumentException invalidParamType(
            String name, String expectedType, Object value) {
        String actualType = value == null ? "null" : value.getClass().getSimpleName();
        return new IllegalArgumentException(
                "Parameter '"
                        + name
                        + "' for Gemini server tool '"
                        + type
                        + "' must be "
                        + expectedType
                        + ", but was "
                        + actualType);
    }

    /**
     * Creates a builder for Google Search grounding.
     *
     * <p>Supported parameters are {@code searchTypes} ({@link SearchTypes}), {@code
     * blockingConfidence} ({@link PhishBlockThreshold}, {@link PhishBlockThreshold.Known}, or
     * {@link String}), {@code excludeDomains} ({@code List<String>}), and {@code timeRangeFilter}
     * ({@link Interval} with both start and end times).
     *
     * @return A Google Search server-tool builder
     */
    public static Builder googleSearch() {
        return builder().type(GOOGLE_SEARCH);
    }

    /**
     * Creates a builder for Google Maps grounding.
     *
     * <p>Supported parameters are {@code authConfig} ({@link AuthConfig}) and {@code enableWidget}
     * ({@link Boolean}).
     *
     * @return A Google Maps server-tool builder
     */
    public static Builder googleMap() {
        return builder().type(GOOGLE_MAP);
    }

    /**
     * Creates a builder for URL Context.
     *
     * <p>URL Context does not accept parameters.
     *
     * @return A URL Context server-tool builder
     */
    public static Builder urlContext() {
        return builder().type(URL_CONTEXT);
    }

    /**
     * Creates an empty Gemini server-tool builder.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link GeminiServerTool} configurations. */
    public static final class Builder {
        private String type;
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Sets the Gemini server-tool type.
         *
         * @param type Tool type, normally one of the constants defined by {@link GeminiServerTool}
         * @return This builder
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Adds or replaces one tool-specific parameter.
         *
         * @param key Parameter name
         * @param value Parameter value; validated when the tool is converted
         * @return This builder
         * @throws NullPointerException if {@code key} is null
         */
        public Builder param(String key, Object value) {
            params.put(Objects.requireNonNull(key, "Parameter name is required"), value);
            return this;
        }

        /**
         * Adds or replaces multiple tool-specific parameters.
         *
         * @param params Parameters to add
         * @return This builder
         * @throws NullPointerException if {@code params} is null
         */
        public Builder params(Map<String, Object> params) {
            this.params.putAll(Objects.requireNonNull(params, "Parameters are required"));
            return this;
        }

        /**
         * Builds an immutable Gemini server-tool configuration.
         *
         * @return A new Gemini server tool
         */
        public GeminiServerTool build() {
            return new GeminiServerTool(type, params);
        }
    }
}
