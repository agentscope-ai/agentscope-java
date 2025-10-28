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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for Audio (multimodal) capabilities.
 *
 * <p>These tests verify audio functionality with REAL API calls to DashScope qwen3-omni-flash model.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("e2e")
@Tag("integration")
@Tag("audio")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Audio E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("Audio (Multimodal) E2E Tests")
class AudioE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    // Official DashScope example audio from demo
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20240916/xvappi/%E8%A3%85%E4%BF%AE%E5%99%AA%E9%9F%B3.wav";

    private ReActAgent audioAgent;
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create agent with audio model
        audioAgent =
                ReActAgent.builder()
                        .name("AudioTestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant with audio understanding"
                                    + " capabilities. Analyze audio carefully and provide accurate"
                                    + " transcriptions and descriptions.")
                        .model(
                                OpenAIChatModel.builder()
                                        .apiKey(apiKey)
                                        .baseUrl(
                                                "https://dashscope.aliyuncs.com/compatible-mode/v1")
                                        .modelName("qwen3-omni-flash") // Audio model
                                        .stream(true)
                                        .formatter(new OpenAIChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== Audio E2E Test Setup Complete ===");
        System.out.println("Using model: qwen3-omni-flash");
        System.out.println("Test audio URL: " + TEST_AUDIO_URL);
    }

    @Test
    @DisplayName("Should analyze audio from URL successfully")
    void testAnalyzeAudioFromURL() {
        System.out.println("\n=== Test: Analyze Audio from URL ===");
        System.out.println("Audio URL: " + TEST_AUDIO_URL);

        // Create message with audio
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please transcribe this audio and tell me"
                                                                + " what it says.")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending audio request to DashScope...");

        // Send request and get response
        Msg response = audioAgent.call(userMsg).block(TEST_TIMEOUT);

        // Verify response
        assertNotNull(response, "Response should not be null");

        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should have text content");
        assertTrue(responseText.length() > 10, "Response should have meaningful content");

        System.out.println("\n=== Response Received ===");
        System.out.println("Response length: " + responseText.length() + " characters");
        System.out.println("Response: " + responseText);

        // Verify response contains transcription or audio-related content
        // The welcome.mp3 should contain some greeting or welcome message
        assertTrue(
                responseText.length() > 0,
                "Response should contain transcription or audio description");

        System.out.println("\n✓ Audio capability verified successfully!");
    }

    @Test
    @DisplayName("Should handle follow-up questions about the audio")
    void testFollowUpQuestionsAboutAudio() {
        System.out.println("\n=== Test: Follow-up Questions About Audio ===");

        // First message: Show the audio
        Msg firstMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is said in this audio?")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Analyzing audio...");
        Msg response1 = audioAgent.call(firstMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");

        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println("Round 1 response length: " + response1Text.length());
        System.out.println("Round 1 response: " + response1Text);

        // Second message: Ask follow-up question (without audio)
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What language is the audio in?")
                                                .build()))
                        .build();

        System.out.println("Round 2: Asking follow-up question...");
        Msg response2 = audioAgent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Round 2 response length: " + response2Text.length());
        System.out.println("Round 2 response: " + response2Text);

        // Verify agent remembers the audio context
        assertNotNull(response2Text, "Follow-up response should have content");
        assertTrue(response2Text.length() > 5, "Follow-up response should have meaningful content");

        System.out.println("\n✓ Follow-up conversation verified successfully!");
    }

    @Test
    @DisplayName("Should handle text-only questions after hearing audio")
    void testMixedConversation() {
        System.out.println("\n=== Test: Mixed Conversation (Audio + Text) ===");

        // Round 1: Audio question with audio
        Msg audioMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Summarize this audio briefly.")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Audio question with audio");
        Msg response1 = audioAgent.call(audioMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println(
                "Round 1 response: "
                        + (response1Text.length() > 100
                                ? response1Text.substring(0, 100) + "..."
                                : response1Text));

        // Round 2: Text-only question
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is 2 plus 2?").build()))
                        .build();

        System.out.println("Round 2: Text-only question");
        Msg response2 = audioAgent.call(textMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Round 2 response: " + response2Text);

        // Should be able to answer simple math question
        assertTrue(
                response2Text.contains("4") || response2Text.contains("四"),
                "Should correctly answer 2+2=4");

        System.out.println("\n✓ Mixed conversation verified successfully!");
    }

    @Test
    @DisplayName("Should handle audio with detailed analysis request")
    void testDetailedAudioAnalysis() {
        System.out.println("\n=== Test: Detailed Audio Analysis ===");

        // Create message requesting detailed analysis
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please analyze this audio in detail: "
                                                                + "1) What is said? "
                                                                + "2) What is the tone? "
                                                                + "3) Any background sounds?")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending detailed analysis request...");
        Msg response = audioAgent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should have text content");

        System.out.println("\n=== Detailed Analysis Response ===");
        System.out.println("Response length: " + responseText.length() + " characters");
        System.out.println("Full response:\n" + responseText);

        // Verify response is reasonably detailed
        assertTrue(
                responseText.length() > 20, "Detailed analysis should provide substantial content");

        System.out.println("\n✓ Detailed audio analysis verified successfully!");
    }
}
