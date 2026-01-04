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
package io.agentscope.core.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared ObjectMapper instance for AG-UI components.
 *
 * <p>This class provides a thread-safe, shared ObjectMapper instance to avoid
 * the overhead of creating new ObjectMapper instances for each request.
 *
 * <p>ObjectMapper is thread-safe for serialization/deserialization operations,
 * so sharing a single instance across the application is the recommended approach.
 */
public final class AguiObjectMapper {

    private static final ObjectMapper INSTANCE;

    static {
        INSTANCE = new ObjectMapper();
        // Disable pretty printing for compact wire format
        INSTANCE.disable(SerializationFeature.INDENT_OUTPUT);
    }

    private AguiObjectMapper() {
        // Utility class, no instantiation
    }

    /**
     * Get the shared ObjectMapper instance.
     *
     * @return The shared ObjectMapper
     */
    public static ObjectMapper get() {
        return INSTANCE;
    }
}
