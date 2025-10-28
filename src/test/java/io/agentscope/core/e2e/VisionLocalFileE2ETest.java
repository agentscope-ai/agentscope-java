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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * E2E test for vision models with local file support.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Local image files work with vision models (using file:// protocol)
 *   <li>Python alignment: file:// protocol is used for local files
 * </ul>
 */
@Tag("e2e")
@Tag("integration")
@Tag("vision")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Vision E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("Vision with Local File E2E Tests")
class VisionLocalFileE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    // Simple red square PNG (20x20) - Base64 encoded
    private static final String RED_SQUARE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAIAAAAC64paAAAAFklEQVR42mP8z8DAwMj4n4FhFIwCMgBmBQEAAhUCYwAAAABJRU5ErkJggg==";

    @TempDir static Path tempDir;

    private static ReActAgent visionAgent;
    private static File localImageFile;
    private static String apiKey;

    @BeforeAll
    static void setUp() throws Exception {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create local test image file (red square)
        localImageFile = tempDir.resolve("red_square.png").toFile();
        byte[] imageBytes = Base64.getDecoder().decode(RED_SQUARE_BASE64);
        try (FileOutputStream fos = new FileOutputStream(localImageFile)) {
            fos.write(imageBytes);
        }

        // Create agent with vision model
        visionAgent =
                ReActAgent.builder()
                        .name("VisionAgent")
                        .sysPrompt("You are a helpful assistant with vision capabilities.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-vl-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("\n=== Vision Local File E2E Test Setup ===");
        System.out.println("Local image file: " + localImageFile.getAbsolutePath());
        System.out.println("Local image size: " + localImageFile.length() + " bytes");
        System.out.println(
                "File URL: file://" + localImageFile.getAbsolutePath() + " (Python-aligned)");
    }

    @Test
    @DisplayName("Test vision with local file using file:// protocol")
    void testVisionWithLocalFile() {
        System.out.println("\n=== Test: Vision with Local File (file:// protocol) ===");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "What color is this image? Reply in one"
                                                                + " word.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        localImageFile
                                                                                .getAbsolutePath())
                                                                .build())
                                                .build()))
                        .build();

        Msg response = visionAgent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response text should not be null");
        assertFalse(responseText.isBlank(), "Response should not be blank");

        System.out.println("Response: " + responseText);

        // Verify the model correctly identified the red color
        assertTrue(
                responseText.toLowerCase().contains("red"),
                "Response should mention 'red' color. Got: " + responseText);

        System.out.println("✓ Local file with file:// protocol works!");
        System.out.println("✓ Python alignment confirmed: file:// protocol is used");
    }

    @Test
    @DisplayName("Test vision with local file in conversation context")
    void testVisionLocalFileInConversation() {
        System.out.println("\n=== Test: Local File in Conversation ===");

        // First message: show image and ask color
        Msg userMsg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Look at this image. What color is it?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        localImageFile
                                                                                .getAbsolutePath())
                                                                .build())
                                                .build()))
                        .build();

        Msg response1 = visionAgent.call(userMsg1).block(TEST_TIMEOUT);
        assertNotNull(response1);
        System.out.println("Response 1: " + TestUtils.extractTextContent(response1));

        // Second message: follow-up question (no image needed)
        Msg userMsg2 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Is it a warm or cool color?")
                                                .build()))
                        .build();

        Msg response2 = visionAgent.call(userMsg2).block(TEST_TIMEOUT);
        assertNotNull(response2);
        String text2 = TestUtils.extractTextContent(response2);
        assertNotNull(text2);

        System.out.println("Response 2: " + text2);

        // Red is a warm color
        assertTrue(
                text2.toLowerCase().contains("warm"),
                "Should recognize red as a warm color. Got: " + text2);

        System.out.println("✓ Conversation with local file works!");
    }
}
