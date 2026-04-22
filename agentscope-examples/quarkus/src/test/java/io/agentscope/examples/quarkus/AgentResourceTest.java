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
package io.agentscope.examples.quarkus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentResource}.
 *
 * <p>These tests verify the REST endpoints including successful requests, error handling, and
 * response formatting. Uses REST-assured for HTTP testing.
 */
@QuarkusTest
@TestProfile(AgentResourceTest.TestAgentProfile.class)
class AgentResourceTest {

    /**
     * Test the health endpoint returns agent status.
     */
    @Test
    void testHealthEndpoint() {
        given().when()
                .get("/agent/health")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("status", is("AgentScope agent is ready"))
                .body("agentName", is("TestAgent"));
    }

    /**
     * Test successful chat with valid message.
     */
    @Test
    void testChatEndpointSuccess() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Hello\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("response", notNullValue());
    }

    /**
     * Test chat endpoint rejects empty message.
     */
    @Test
    void testChatEndpointEmptyMessage() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(400)
                .contentType(MediaType.APPLICATION_JSON)
                .body("error", is("Message cannot be empty"));
    }

    /**
     * Test chat endpoint rejects null message.
     */
    @Test
    void testChatEndpointNullMessage() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(400)
                .contentType(MediaType.APPLICATION_JSON)
                .body("error", is("Message cannot be empty"));
    }

    /**
     * Test chat endpoint handles invalid JSON gracefully.
     */
    @Test
    void testChatEndpointInvalidJson() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("invalid json")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(400); // Quarkus returns 400 for malformed JSON
    }

    /**
     * Test chat endpoint response format contains expected fields.
     */
    @Test
    void testChatEndpointResponseFormat() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"What is your name?\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("response", notNullValue())
                .body("response", containsString("Test")); // Should mention TestAgent
    }

    /**
     * Test chat endpoint with whitespace-only message.
     */
    @Test
    void testChatEndpointWhitespaceMessage() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"   \"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(400)
                .contentType(MediaType.APPLICATION_JSON)
                .body("error", is("Message cannot be empty"));
    }

    /**
     * Test chat endpoint with long message.
     */
    @Test
    void testChatEndpointLongMessage() {
        String longMessage = "a".repeat(1000);
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"" + longMessage + "\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("response", notNullValue());
    }

    /**
     * Test chat endpoint with special characters in message.
     */
    @Test
    void testChatEndpointSpecialCharacters() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Hello! @#$%^&*() 测试\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("response", notNullValue());
    }

    /**
     * Test chat endpoint with newlines in message.
     */
    @Test
    void testChatEndpointMultilineMessage() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Line 1\\nLine 2\\nLine 3\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .contentType(MediaType.APPLICATION_JSON)
                .body("response", notNullValue());
    }

    /**
     * Test health endpoint is accessible without authentication.
     */
    @Test
    void testHealthEndpointNoAuth() {
        given().when().get("/agent/health").then().statusCode(200);
    }

    /**
     * Test chat endpoint returns proper content type.
     */
    @Test
    void testChatEndpointContentType() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Test\"}")
                .when()
                .post("/agent/chat")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString(MediaType.APPLICATION_JSON));
    }

    /**
     * Test profile with minimal agent configuration for testing.
     */
    public static class TestAgentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agentscope.model.provider",
                    "dashscope",
                    "agentscope.dashscope.api-key",
                    "test-api-key",
                    "agentscope.dashscope.model-name",
                    "qwen-plus",
                    "agentscope.agent.name",
                    "TestAgent",
                    "agentscope.agent.sys-prompt",
                    "You are a test assistant. Keep responses brief.");
        }
    }
}
