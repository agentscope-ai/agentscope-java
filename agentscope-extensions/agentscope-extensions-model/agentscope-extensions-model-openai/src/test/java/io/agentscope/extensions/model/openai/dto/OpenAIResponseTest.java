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
package io.agentscope.extensions.model.openai.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenAIResponse error detection logic covering both standard OpenAI errors
 * and non-standard gateway errors based on code, status, or message fields.
 */
class OpenAIResponseTest {

    @Test
    @DisplayName("Should detect standard OpenAI error")
    void testStandardOpenAIError() {
        OpenAIResponse response = new OpenAIResponse();
        OpenAIError error = new OpenAIError();
        error.setCode("invalid_api_key");
        response.setError(error);

        assertTrue(response.isError());
    }

    @Test
    @DisplayName("Should detect non-standard gateway error by code")
    void testNonStandardErrorByCode() {
        OpenAIResponse response = new OpenAIResponse();
        response.setCode("429");
        response.setMessage("Rate limit exceeded");

        assertTrue(response.isError());
    }

    @Test
    @DisplayName("Should detect non-standard gateway error by status")
    void testNonStandardErrorByStatus() {
        OpenAIResponse response = new OpenAIResponse();
        response.setStatus("error");

        assertTrue(response.isError());
    }

    @Test
    @DisplayName("Should not detect error for successful response including custom gateway codes")
    void testSuccessfulResponse() {
        OpenAIResponse response1 = new OpenAIResponse();
        response1.setCode("200");
        response1.setStatus("success");
        assertFalse(response1.isError(), "Code 200 should not be an error");

        OpenAIResponse response2 = new OpenAIResponse();
        response2.setCode("0");
        assertFalse(response2.isError(), "Code 0 should not be an error");

        OpenAIResponse response3 = new OpenAIResponse();
        response3.setCode("ok");
        assertFalse(response3.isError(), "String code 'ok' should not be an error");

        OpenAIResponse response4 = new OpenAIResponse();
        response4.setCode("success");
        assertFalse(response4.isError(), "String code 'success' should not be an error");
    }

    @Test
    @DisplayName("Should detect error based on valid HTTP error code range (400-599)")
    void testErrorDetectionByHttpCodeRange() {
        OpenAIResponse response400 = new OpenAIResponse();
        response400.setCode("400");
        assertTrue(response400.isError(), "Code 400 should be detected as error");

        OpenAIResponse response500 = new OpenAIResponse();
        response500.setCode("500");
        assertTrue(response500.isError(), "Code 500 should be detected as error");

        OpenAIResponse response399 = new OpenAIResponse();
        response399.setCode("399");
        assertFalse(response399.isError(), "Code 399 should not be an error by default");

        OpenAIResponse response600 = new OpenAIResponse();
        response600.setCode("600");
        assertFalse(response600.isError(), "Code 600 should not be an error by default");
    }

    @Test
    @DisplayName("Should detect non-standard gateway error by status 'failed' ignoring case")
    void testNonStandardErrorByStatusFailed() {
        OpenAIResponse response = new OpenAIResponse();
        response.setStatus("Failed");
        assertTrue(response.isError(), "Status 'Failed' should be detected as error");
    }
}
