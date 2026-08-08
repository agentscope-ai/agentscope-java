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
package io.agentscope.extensions.model.anthropic.tool;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ToolUnion;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic-specific, versioned server tool definition.
 *
 * <p>Server tools (web search, web fetch, code execution, ...) are executed on Anthropic's
 * infrastructure within a single model call. The developer explicitly specifies the versioned
 * {@code type} string (e.g. {@code web_search_20250305}) plus tool parameters keyed by Anthropic
 * API field names. The definition is converted to the SDK {@link ToolUnion} through the SDK's own
 * JSON model, so every server tool type known to the underlying anthropic-java SDK is supported;
 * unknown types or malformed parameters throw {@link IllegalArgumentException} at build time.
 *
 * <p>Example:
 *
 * <pre>{@code
 * AnthropicServerTool.webSearch()
 *         .param("max_uses", 5)
 *         .param("allowed_domains", List.of("example.com"))
 *         .param("user_location", Map.of("city", "Hangzhou", "timezone", "Asia/Shanghai"))
 *         .build();
 * }</pre>
 */
public final class AnthropicServerTool {

    /** The versioned type string of the web search server tool. */
    public static final String TYPE_WEB_SEARCH_20250305 = "web_search_20250305";

    /** The versioned type string of the web fetch server tool. */
    public static final String TYPE_WEB_FETCH_20250910 = "web_fetch_20250910";

    /** The versioned type string of the code execution server tool. */
    public static final String TYPE_CODE_EXECUTION_20250825 = "code_execution_20250825";

    private final String type;
    private final Map<String, Object> params;

    private AnthropicServerTool(String type, Map<String, Object> params) {
        this.type = type;
        this.params = params;
    }

    /**
     * Gets the versioned server tool type (e.g. {@code web_search_20250305}).
     *
     * @return the versioned type string
     */
    public String getType() {
        return type;
    }

    /**
     * Gets the tool parameters keyed by Anthropic API field names.
     *
     * @return immutable parameter map
     */
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * Converts this definition to the SDK {@link ToolUnion} to be added to a request.
     *
     * <p>The tool is materialized through the SDK's JSON model ({@code type} + {@code name} +
     * params) and validated eagerly, so all server tool types known to the SDK are accepted.
     *
     * @return the SDK tool union
     * @throws IllegalArgumentException if the type is unknown to the SDK or a parameter is invalid
     */
    public ToolUnion toToolUnion() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", type);
        json.put("name", deriveNameFromType(type));
        json.putAll(params);
        try {
            return ObjectMappers.jsonMapper().convertValue(json, ToolUnion.class).validate();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid Anthropic server tool definition (type='"
                            + type
                            + "'): "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Derives the tool name from the versioned type by stripping the trailing date suffix, e.g.
     * {@code web_search_20250305} to {@code web_search}.
     */
    private static String deriveNameFromType(String type) {
        return type.replaceFirst("_\\d{8}$", "");
    }

    /**
     * Creates a builder pre-filled with the {@link #TYPE_WEB_SEARCH_20250305} type.
     *
     * @return a builder for a web search server tool
     */
    public static Builder webSearch() {
        return builder().type(TYPE_WEB_SEARCH_20250305);
    }

    /**
     * Creates a builder pre-filled with the {@link #TYPE_WEB_FETCH_20250910} type.
     *
     * @return a builder for a web fetch server tool
     */
    public static Builder webFetch() {
        return builder().type(TYPE_WEB_FETCH_20250910);
    }

    /**
     * Creates a builder pre-filled with the {@link #TYPE_CODE_EXECUTION_20250825} type.
     *
     * @return a builder for a code execution server tool
     */
    public static Builder codeExecution() {
        return builder().type(TYPE_CODE_EXECUTION_20250825);
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link AnthropicServerTool}. */
    public static final class Builder {
        private String type;
        private final Map<String, Object> params = new LinkedHashMap<>();

        /**
         * Sets the versioned server tool type (required), e.g. {@code web_search_20250305}.
         *
         * @param type the versioned type string
         * @return this builder
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Adds a tool parameter keyed by the Anthropic API field name (e.g. {@code max_uses},
         * {@code allowed_domains}, {@code user_location}).
         *
         * @param key the API field name
         * @param value the parameter value
         * @return this builder
         */
        public Builder param(String key, Object value) {
            this.params.put(key, value);
            return this;
        }

        /**
         * Builds the server tool definition, validating type and parameters eagerly.
         *
         * @return a new AnthropicServerTool
         * @throws IllegalArgumentException if type is missing/unknown or a parameter is invalid
         */
        public AnthropicServerTool build() {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException(
                        "Anthropic server tool 'type' is required (e.g. "
                                + TYPE_WEB_SEARCH_20250305
                                + ")");
            }
            AnthropicServerTool tool =
                    new AnthropicServerTool(
                            type, Collections.unmodifiableMap(new LinkedHashMap<>(params)));
            // Fail fast on unknown types and invalid parameters
            tool.toToolUnion();
            return tool;
        }
    }
}
