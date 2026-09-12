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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.openai.models.responses.Response;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for {@link ResponsesHelper}.
 *
 * <p>Covers full metadata extraction, absence paths, usage mapping, and usage-absent
 * return. Most fields are also exercised indirectly by {@link ResponsesResponseParserTest}.
 */
class ResponsesHelperTest {

    @Test
    void fullMetadataResponseExtractsAllFields() {
        Response response = TestSdkFixtures.fullMetadataResponse();
        Map<String, Object> md = ResponsesHelper.extractResponseMetadata(response);

        assertEquals("resp_test_123", md.get(OpenAIOfficialConstants.MD_RESPONSE_ID));
        assertEquals(1697000000.5, (Double) md.get(OpenAIOfficialConstants.MD_RESPONSE_CREATED_AT));
        assertEquals("incomplete", md.get(OpenAIOfficialConstants.MD_RESPONSE_STATUS));
        assertEquals(
                1697000001.5, (Double) md.get(OpenAIOfficialConstants.MD_RESPONSE_COMPLETED_AT));
        assertEquals("priority", md.get(OpenAIOfficialConstants.MD_RESPONSE_SERVICE_TIER));
        assertEquals(
                "max_output_tokens", md.get(OpenAIOfficialConstants.MD_RESPONSE_INCOMPLETE_REASON));
        @SuppressWarnings("unchecked")
        Map<String, Object> errorMap =
                (Map<String, Object>) md.get(OpenAIOfficialConstants.MD_RESPONSE_ERROR);
        assertEquals("Something went wrong", errorMap.get("message"));
        assertEquals("server_error", errorMap.get("code"));
    }

    @Test
    void minimalResponseHasOnlyIdAndCreatedAt() {
        Response response =
                TestSdkFixtures.response(List.of(TestSdkFixtures.messageItem("hello")), null, null);
        Map<String, Object> md = ResponsesHelper.extractResponseMetadata(response);

        assertEquals("resp_test_123", md.get(OpenAIOfficialConstants.MD_RESPONSE_ID));
        assertEquals(1697000000.5, (Double) md.get(OpenAIOfficialConstants.MD_RESPONSE_CREATED_AT));
        assertFalse(md.containsKey(OpenAIOfficialConstants.MD_RESPONSE_STATUS));
        assertFalse(md.containsKey(OpenAIOfficialConstants.MD_RESPONSE_COMPLETED_AT));
        assertFalse(md.containsKey(OpenAIOfficialConstants.MD_RESPONSE_SERVICE_TIER));
        assertFalse(md.containsKey(OpenAIOfficialConstants.MD_RESPONSE_INCOMPLETE_REASON));
        assertFalse(md.containsKey(OpenAIOfficialConstants.MD_RESPONSE_ERROR));
    }

    @Test
    void usageMappedToChatUsageAndReasoningTokensWrittenToMetadata() {
        Response response = TestSdkFixtures.usageResponse(100L, 50L, 20L, 15L);
        Map<String, Object> metadata = new HashMap<>();
        ChatUsage usage = ResponsesHelper.extractUsage(response, Instant.now(), metadata);

        assertNotNull(usage);
        assertEquals(100, usage.getInputTokens());
        assertEquals(50, usage.getOutputTokens());
        assertEquals(20, usage.getCachedTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals(15, metadata.get(OpenAIOfficialConstants.MD_USAGE_REASONING_TOKENS));
    }

    @Test
    void usageAbsentReturnsNullAndDoesNotWriteReasoningTokens() {
        Response response = TestSdkFixtures.textResponse("hello");
        Map<String, Object> metadata = new HashMap<>();
        ChatUsage usage = ResponsesHelper.extractUsage(response, Instant.now(), metadata);

        assertNull(usage);
        assertFalse(metadata.containsKey(OpenAIOfficialConstants.MD_USAGE_REASONING_TOKENS));
    }
}
