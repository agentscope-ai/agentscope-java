/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.util;

/**
 * Utility class for accessing the global {@link JsonCodec} instance.
 *
 * <p>This class provides a centralized way to access JSON serialization/deserialization
 * functionality throughout the framework. By default, it uses {@link JacksonJsonCodec},
 * but users can replace it with a custom implementation.
 *
 * <p>Usage:
 * <pre>{@code
 * // Basic usage
 * JsonUtils.getJsonCodec().toJson(obj);
 * JsonUtils.getJsonCodec().fromJson(json, MyClass.class);
 *
 * // Cache reference for frequent calls
 * JsonCodec codec = JsonUtils.getJsonCodec();
 * codec.toJson(obj1);
 * codec.toJson(obj2);
 *
 * // Replace with custom implementation
 * JsonUtils.setJsonCodec(new MyCustomJsonCodec());
 * }</pre>
 *
 * @see JsonCodec
 * @see JacksonJsonCodec
 */
public final class JsonUtils {

    private static volatile JsonCodec codec = new JacksonJsonCodec();

    private JsonUtils() {
        // Utility class, no instantiation
    }

    /**
     * Get the global JsonCodec instance.
     *
     * @return the JsonCodec instance
     */
    public static JsonCodec getJsonCodec() {
        return codec;
    }

    /**
     * Set a custom JsonCodec implementation.
     *
     * <p>This allows users to replace the default Jackson-based implementation
     * with their own implementation (e.g., Gson, Fastjson, etc.).
     *
     * @param jsonCodec the custom JsonCodec implementation
     * @throws IllegalArgumentException if jsonCodec is null
     */
    public static void setJsonCodec(JsonCodec jsonCodec) {
        if (jsonCodec == null) {
            throw new IllegalArgumentException("JsonCodec cannot be null");
        }
        codec = jsonCodec;
    }

    /**
     * Reset to the default JacksonJsonCodec implementation.
     *
     * <p>This is useful for testing or when you want to restore the default behavior.
     */
    public static void resetToDefault() {
        codec = new JacksonJsonCodec();
    }
}
