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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiApiException Unit Tests")
class GeminiApiExceptionTest {

    @Test
    @DisplayName("Should expose status code and response body")
    void testConstructorAndGetters() {
        GeminiApiException exception = new GeminiApiException(429, "{\"error\":\"rate_limited\"}");

        assertEquals(429, exception.getStatusCode());
        assertEquals("{\"error\":\"rate_limited\"}", exception.getBody());
        assertTrue(exception.getMessage().contains("429"));
        assertTrue(exception.getMessage().contains("rate_limited"));
    }
}
