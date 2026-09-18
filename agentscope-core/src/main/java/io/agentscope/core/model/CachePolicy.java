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

/**
 * Controls whether {@link ModelRegistry} caches models created with a
 * {@link ModelCreationContext}.
 *
 * <p>Policy summary:
 *
 * <ul>
 *   <li>{@link #DEFAULT}: preserves legacy behavior. Empty contexts are cached by model id;
 *       non-empty contexts are not cached.
 *   <li>{@link #DISABLED}: never cache. Every resolution creates a new model instance.
 *   <li>{@link #ENABLED}: opt-in caching. The cache identity is either the explicit
 *       {@code cacheId} supplied by the caller or a fingerprint derived only from standard simple
 *       fields. Contexts containing opaque options/components require an explicit {@code cacheId}.
 * </ul>
 */
public enum CachePolicy {
    /**
     * Preserve registry defaults. Legacy {@link ModelRegistry#resolve(String)} calls are cached,
     * while non-empty context-based resolutions are not cached.
     */
    DEFAULT,

    /** Never cache the created model. */
    DISABLED,

    /** Cache the created model using an explicit or safely derived context cache identity. */
    ENABLED
}
