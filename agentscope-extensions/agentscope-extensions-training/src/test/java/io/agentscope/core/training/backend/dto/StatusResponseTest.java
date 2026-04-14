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

package io.agentscope.core.training.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("StatusResponse Unit Tests")
class StatusResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.shared();

    @Test
    @DisplayName("Should deserialize from JSON")
    void shouldDeserializeFromJson() {
        // Arrange
        String json = "{\"status\": \"success\", \"message\": \"OK\"}";

        // Act
        StatusResponse response = jsonMapper.readValue(json, StatusResponse.class);

        // Assert
        assertNotNull(response);
        assertEquals("success", response.getStatus());
        assertEquals("OK", response.getMessage());
        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("Should deserialize error response")
    void shouldDeserializeErrorResponse() {
        // Arrange
        String json = "{\"status\": \"error\", \"message\": \"Internal Server Error\"}";

        // Act
        StatusResponse response = jsonMapper.readValue(json, StatusResponse.class);

        // Assert
        assertNotNull(response);
        assertEquals("error", response.getStatus());
        assertEquals("Internal Server Error", response.getMessage());
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("Should create with constructor")
    void shouldCreateWithConstructor() {
        // Act
        StatusResponse response = new StatusResponse("success", "Operation completed");

        // Assert
        assertEquals("success", response.getStatus());
        assertEquals("Operation completed", response.getMessage());
        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("Should create with default constructor and setters")
    void shouldCreateWithDefaultConstructorAndSetters() {
        // Act
        StatusResponse response = new StatusResponse();
        response.setStatus("success");
        response.setMessage("Done");

        // Assert
        assertEquals("success", response.getStatus());
        assertEquals("Done", response.getMessage());
    }

    @Test
    @DisplayName("Should check isSuccess case insensitively")
    void shouldCheckIsSuccessCaseInsensitively() {
        // Arrange
        StatusResponse response1 = new StatusResponse("SUCCESS", "OK");
        StatusResponse response2 = new StatusResponse("Success", "OK");
        StatusResponse response3 = new StatusResponse("failed", "Error");

        // Assert
        assertTrue(response1.isSuccess());
        assertTrue(response2.isSuccess());
        assertFalse(response3.isSuccess());
    }
}
