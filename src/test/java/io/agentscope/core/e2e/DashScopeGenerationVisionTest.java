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

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Test if DashScope Generation API supports vision using MessageContentImageURL.
 *
 * <p>This test verifies whether the Generation API (not MultiModalConversation API)
 * can handle vision content using MessageContentImageURL.
 */
@Tag("e2e")
@Tag("integration")
@Tag("vision")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Vision E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("DashScope Generation API Vision Test")
class DashScopeGenerationVisionTest {

    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    @Test
    @Disabled(
            "Generation API does not support vision with MessageContentImageURL. "
                    + "Use MultiModalConversation API for vision models instead. "
                    + "This test is kept for documentation purposes.")
    @DisplayName("Test if Generation API supports MessageContentImageURL")
    void testGenerationAPIWithMessageContentImageURL() throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        System.out.println("\n=== Testing Generation API with MessageContentImageURL ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        Generation generation = new Generation();

        // Build message with contents (multimodal format)
        Message userMessage = new Message();
        userMessage.setRole("user");
        List<com.alibaba.dashscope.common.MessageContentBase> contents = new ArrayList<>();
        contents.add(MessageContentText.builder().text("Please describe this image").build());
        contents.add(
                MessageContentImageURL.builder()
                        .imageURL(ImageURL.builder().url(TEST_IMAGE_URL).build())
                        .build());
        userMessage.setContents(contents);

        GenerationParam param =
                GenerationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-vl-max")
                        .messages(List.of(userMessage))
                        .resultFormat("message")
                        .build();

        System.out.println("Calling Generation API with MessageContentImageURL...");

        try {
            GenerationResult result = generation.call(param);
            System.out.println("Success! Response: " + result.getOutput().getText());
            System.out.println("\n✓ Generation API supports MessageContentImageURL!");
        } catch (Exception e) {
            System.out.println("Failed! Error: " + e.getMessage());
            System.out.println("\n✗ Generation API does NOT support MessageContentImageURL");
            System.out.println(
                    "This confirms we need to use MultiModalConversation API for vision");
            throw e;
        }
    }
}
