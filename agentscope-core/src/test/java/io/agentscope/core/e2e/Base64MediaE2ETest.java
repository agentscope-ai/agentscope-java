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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for Base64-encoded media input.
 *
 * <p>Coverage:
 * - Base64 image input (vs URL input)
 * - Base64Source handling in formatters
 * - Base64 vs URL behavior equivalence
 */
@Tag("e2e")
@Tag("base64")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Base64 Media E2E Tests")
class Base64MediaE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private static final String TEST_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should handle Base64-encoded image input")
    void testBase64ImageInput(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Base64 Image Input with " + provider.getProviderName() + " ===");

        try {
            String base64Image = downloadAndEncodeImage(TEST_IMAGE_URL);
            System.out.println("Downloaded image, base64 length: " + base64Image.length());

            Toolkit toolkit = new Toolkit();
            ReActAgent agent = provider.createAgent("Base64ImageAgent", toolkit);

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            "Describe what you see in this image"
                                                                    + " (provided as Base64).")
                                                    .build(),
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .data(base64Image)
                                                                    .mediaType("image/jpeg")
                                                                    .build())
                                                    .build()))
                            .build();

            System.out.println("Sending Base64 image request...");

            Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null");
            assertTrue(
                    ContentValidator.hasMeaningfulContent(response),
                    "Response should have meaningful content");

            String responseText = TestUtils.extractTextContent(response);
            System.out.println(
                    "Response: " + responseText.substring(0, Math.min(200, responseText.length())));

            // Image is a cat, verify model recognized it
            assertTrue(
                    ContentValidator.mentionsVisualElements(response, "cat", "animal", "feline"),
                    "Response should mention cat for " + provider.getModelName());

            System.out.println("✓ Base64 image input verified for " + provider.getProviderName());

        } catch (IOException e) {
            System.out.println(
                    "⚠ Skipping Base64 image test for "
                            + provider.getProviderName()
                            + ": "
                            + e.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should produce equivalent results for Base64 vs URL")
    void testBase64VsURLEquivalence(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Base64 vs URL Equivalence with "
                        + provider.getProviderName()
                        + " ===");

        try {
            String base64Image = downloadAndEncodeImage(TEST_IMAGE_URL);

            Toolkit toolkit = new Toolkit();
            ReActAgent agentURL = provider.createAgent("URLAgent", toolkit);
            ReActAgent agentBase64 = provider.createAgent("Base64Agent", toolkit);

            String question = "What type of animal is in this image? Answer in one word.";

            // Test with URL
            Msg urlMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text(question).build(),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(TEST_IMAGE_URL)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg responseURL = agentURL.call(urlMsg).block(TEST_TIMEOUT);
            String urlAnswer = TestUtils.extractTextContent(responseURL);
            System.out.println("URL answer: " + urlAnswer);

            // Test with Base64
            Msg base64Msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text(question).build(),
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .data(base64Image)
                                                                    .mediaType("image/jpeg")
                                                                    .build())
                                                    .build()))
                            .build();

            Msg responseBase64 = agentBase64.call(base64Msg).block(TEST_TIMEOUT);
            String base64Answer = TestUtils.extractTextContent(responseBase64);
            System.out.println("Base64 answer: " + base64Answer);

            // Both should recognize it's a cat
            assertTrue(
                    ContentValidator.containsKeywords(responseURL, "cat", "feline"),
                    "URL response should mention cat");
            assertTrue(
                    ContentValidator.containsKeywords(responseBase64, "cat", "feline"),
                    "Base64 response should mention cat");

            System.out.println(
                    "✓ Base64 vs URL equivalence verified for " + provider.getProviderName());

        } catch (IOException e) {
            System.out.println(
                    "⚠ Skipping equivalence test for "
                            + provider.getProviderName()
                            + ": "
                            + e.getMessage());
        }
    }

    @Test
    @DisplayName("Should verify Base64Source formatter handling")
    void testBase64SourceFormatting() {
        System.out.println("\n=== Test: Base64Source Formatting ===");

        String testData =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        Base64Source source = Base64Source.builder().data(testData).mediaType("image/png").build();

        assertNotNull(source, "Base64Source should be created");
        assertNotNull(source.getData(), "Base64Source should have data");
        assertNotNull(source.getMediaType(), "Base64Source should have media type");

        System.out.println("Base64Source structure validated");
        System.out.println("Media type: " + source.getMediaType());
        System.out.println("Data length: " + source.getData().length());

        System.out.println("✓ Base64Source formatting verified");
    }

    /**
     * Helper method to download image from URL and encode to Base64.
     *
     * @param imageUrl The image URL
     * @return Base64-encoded image string
     * @throws IOException If download fails
     */
    private String downloadAndEncodeImage(String imageUrl) throws IOException {
        try (InputStream is = new URL(imageUrl).openStream()) {
            byte[] imageBytes = is.readAllBytes();
            return Base64.getEncoder().encodeToString(imageBytes);
        }
    }
}
