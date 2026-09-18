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
package io.agentscope.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral model creation context used by {@link ModelRegistry} and model provider SPI
 * implementations.
 *
 * <p>This type has two layers:
 *
 * <ul>
 *   <li>Standard fields such as {@code apiKey}, {@code baseUrl}, {@code endpointPath},
 *       {@code stream}, and {@code enableThinking}. These are common enough to be understood by
 *       multiple providers and can safely participate in automatic cache identity when
 *       {@link CachePolicy#ENABLED} is selected.
 *   <li>Provider-specific {@linkplain #option(String) options} and typed {@linkplain
 *       #component(Class) components}. These allow extension modules to consume advanced builder
 *       settings such as formatters, HTTP transports, credentials, proxies, and default generation
 *       options without adding provider types to core.
 * </ul>
 *
 * <p>Options and components are intentionally opaque to core. {@link ModelRegistry} never deep
 * hashes them or uses their {@code hashCode()} values for cache identity. When they are present and
 * caching is enabled, callers must provide an explicit {@link Builder#cacheId(String)}.
 */
public final class ModelCreationContext {

    private static final ModelCreationContext EMPTY = builder().build();

    private final String apiKey;
    private final String baseUrl;
    private final String endpointPath;
    private final Boolean stream;
    private final Boolean enableThinking;
    private final CachePolicy cachePolicy;
    private final String cacheId;
    private final Map<String, Object> options;
    private final Map<Class<?>, Object> components;

    private ModelCreationContext(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.endpointPath = builder.endpointPath;
        this.stream = builder.stream;
        this.enableThinking = builder.enableThinking;
        this.cachePolicy = builder.cachePolicy;
        this.cacheId = builder.cacheId;
        this.options = Map.copyOf(builder.options);
        this.components = Map.copyOf(builder.components);
    }

    public static ModelCreationContext empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a mutable builder initialized from this context.
     *
     * <p>This is useful when a caller wants to derive a tenant- or request-specific context from a
     * shared baseline without mutating the original instance.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** API key or token to pass to a provider. Providers may fall back to environment variables. */
    public String getApiKey() {
        return apiKey;
    }

    /** Provider base URL override, for self-hosted or compatible API endpoints. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** Provider endpoint path override, mainly for OpenAI-compatible APIs. */
    public String getEndpointPath() {
        return endpointPath;
    }

    /** Whether the created model should use streaming by default. {@code null} means provider default. */
    public Boolean getStream() {
        return stream;
    }

    /** Whether provider thinking/reasoning mode should be enabled. */
    public Boolean getEnableThinking() {
        return enableThinking;
    }

    /** Cache policy for {@link ModelRegistry#resolve(String, ModelCreationContext)}. */
    public CachePolicy getCachePolicy() {
        return cachePolicy;
    }

    /** Explicit cache identity used when {@link #getCachePolicy()} is {@link CachePolicy#ENABLED}. */
    public String getCacheId() {
        return cacheId;
    }

    /** Provider-specific scalar or value options keyed by extension-defined names. */
    public Map<String, Object> getOptions() {
        return options;
    }

    /** Returns a provider-specific option value, or {@code null}. */
    public Object option(String key) {
        return options.get(key);
    }

    /** Returns a provider-specific option cast to the requested type, or {@code null}. */
    public <T> T option(String key, Class<T> type) {
        Object value = options.get(key);
        return value == null ? null : type.cast(value);
    }

    /** Provider-specific component objects keyed by type. */
    public Map<Class<?>, Object> getComponents() {
        return components;
    }

    /** Returns a provider-specific component by type, or {@code null}. */
    public <T> T component(Class<T> type) {
        Object value = components.get(type);
        return value == null ? null : type.cast(value);
    }

    /**
     * Returns whether this context has no effective settings.
     *
     * <p>The empty context preserves legacy {@link ModelRegistry#resolve(String)} caching behavior.
     */
    public boolean isEmpty() {
        return apiKey == null
                && baseUrl == null
                && endpointPath == null
                && stream == null
                && enableThinking == null
                && cachePolicy == CachePolicy.DEFAULT
                && cacheId == null
                && options.isEmpty()
                && components.isEmpty();
    }

    /** Returns whether the context contains values that core cannot fingerprint safely. */
    public boolean hasOpaqueCacheInputs() {
        return !options.isEmpty() || !components.isEmpty();
    }

    @Override
    public String toString() {
        return "ModelCreationContext{"
                + "apiKey="
                + (apiKey == null ? "null" : "[REDACTED]")
                + ", baseUrl='"
                + baseUrl
                + '\''
                + ", endpointPath='"
                + endpointPath
                + '\''
                + ", stream="
                + stream
                + ", enableThinking="
                + enableThinking
                + ", cachePolicy="
                + cachePolicy
                + ", cacheId='"
                + cacheId
                + '\''
                + ", options="
                + options.keySet()
                + ", components="
                + components.keySet()
                + '}';
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String endpointPath;
        private Boolean stream;
        private Boolean enableThinking;
        private CachePolicy cachePolicy = CachePolicy.DEFAULT;
        private String cacheId;
        private final Map<String, Object> options = new LinkedHashMap<>();
        private final Map<Class<?>, Object> components = new LinkedHashMap<>();

        private Builder() {}

        private Builder(ModelCreationContext source) {
            this.apiKey = source.apiKey;
            this.baseUrl = source.baseUrl;
            this.endpointPath = source.endpointPath;
            this.stream = source.stream;
            this.enableThinking = source.enableThinking;
            this.cachePolicy = source.cachePolicy;
            this.cacheId = source.cacheId;
            this.options.putAll(source.options);
            this.components.putAll(source.components);
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = trimToNull(apiKey);
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = trimToNull(baseUrl);
            return this;
        }

        public Builder endpointPath(String endpointPath) {
            this.endpointPath = trimToNull(endpointPath);
            return this;
        }

        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder cachePolicy(CachePolicy cachePolicy) {
            this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
            return this;
        }

        public Builder cacheId(String cacheId) {
            this.cacheId = trimToNull(cacheId);
            return this;
        }

        public Builder option(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (value == null) {
                options.remove(key);
            } else {
                options.put(key, value);
            }
            return this;
        }

        public <T> Builder component(Class<T> type, T value) {
            Objects.requireNonNull(type, "type");
            if (value == null) {
                components.remove(type);
            } else {
                components.put(type, value);
            }
            return this;
        }

        public ModelCreationContext build() {
            return new ModelCreationContext(this);
        }

        private static String trimToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
