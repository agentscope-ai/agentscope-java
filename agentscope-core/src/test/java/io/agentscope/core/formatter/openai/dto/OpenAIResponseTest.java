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
package io.agentscope.core.formatter.openai.dto;

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
    @DisplayName("Should not detect error for successful response")
    void testSuccessfulResponse() {
        OpenAIResponse response = new OpenAIResponse();
        response.setCode("200");
        response.setStatus("success");

        assertFalse(response.isError());

        OpenAIResponse response2 = new OpenAIResponse();
        response2.setCode("0");

        assertFalse(response2.isError());
    }
}
