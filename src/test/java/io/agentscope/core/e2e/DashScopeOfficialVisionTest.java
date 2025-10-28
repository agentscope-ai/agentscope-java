/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Test using DashScope official MultiModalConversation API example.
 *
 * <p>This test verifies that the official API works before testing our formatter.
 */
@Tag("e2e")
@Tag("integration")
@Tag("vision")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Vision E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("DashScope Official MultiModal API Test")
class DashScopeOfficialVisionTest {

    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    @Test
    @DisplayName("Should call DashScope MultiModal API successfully using official example")
    void testOfficialMultiModalAPI() throws ApiException, NoApiKeyException, UploadFileException {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        System.out.println("\n=== Testing DashScope Official MultiModal API ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessage userMessage =
                MultiModalMessage.builder()
                        .role(Role.USER.getValue())
                        .content(
                                Arrays.asList(
                                        Collections.singletonMap("image", TEST_IMAGE_URL),
                                        Collections.singletonMap(
                                                "text", "Please describe this image in detail.")))
                        .build();

        MultiModalConversationParam param =
                MultiModalConversationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-vl-max") // Using qwen-vl-max
                        .messages(Arrays.asList(userMessage))
                        .build();

        System.out.println("Sending request to DashScope MultiModal API...");
        MultiModalConversationResult result = conv.call(param);

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getOutput(), "Output should not be null");
        assertNotNull(result.getOutput().getChoices(), "Choices should not be null");
        assertTrue(result.getOutput().getChoices().size() > 0, "Should have at least one choice");

        Object responseObj =
                result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text");
        String response = responseObj != null ? responseObj.toString() : null;

        System.out.println("\n=== Response Received ===");
        System.out.println("Response: " + response);

        assertNotNull(response, "Response text should not be null");
        assertTrue(response.length() > 10, "Response should have meaningful content");

        System.out.println("\n✓ Official MultiModal API works!");
    }
}
