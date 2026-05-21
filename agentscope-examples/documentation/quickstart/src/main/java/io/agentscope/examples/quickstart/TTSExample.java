/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import io.agentscope.core.model.tts.DashScopeTTSModel;
import io.agentscope.core.model.tts.TTSOptions;
import io.agentscope.core.model.tts.TTSResponse;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;

/**
 * Example demonstrating all three TTS usage patterns in AgentScope Java.
 *
 * <p>This example covers:
 * <ul>
 *   <li>Example 1: ReActAgent with realtime TTS - Agent speaks while generating response</li>
 *   <li>Example 2: Standalone TTSModel - Use TTS independently without Agent</li>
 *   <li>Example 3: TTS as Agent Tool - Agent decides when to invoke TTS tool</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Set DASHSCOPE_API_KEY environment variable</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn exec:java -pl agentscope-examples/quickstart \
 *   -Dexec.mainClass="io.agentscope.examples.quickstart.TTSExample"
 * </pre>
 */
public class TTSExample {

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set DASHSCOPE_API_KEY environment variable");
            return;
        }

        System.out.println("========================================");
        System.out.println("  AgentScope Java TTS Examples");
        System.out.println("========================================");
        System.out.println();

        // Example 1: ReActAgent speaks while generating response
        realtimeAgentWithTTS(apiKey);

        // Example 2: Use TTSModel independently without Agent
        standaloneTTSModel(apiKey);

        // Example 3: Realtime TTS with push/finish pattern (WebSocket streaming)
        standaloneRealtimeTTSDemo(apiKey);

        // Example 4: Agent invokes TTS as a tool
        agentWithTTSTool(apiKey);

        System.out.println("All examples completed!");
    }

    // ========================================================================
    // Example 1: ReActAgent with Realtime TTS
    // ========================================================================

    /**
     * Demonstrates ReActAgent speaking while generating response.
     *
     * <p>Using TTSHook, the Agent synthesizes and plays audio in real-time as it
     * generates text. This creates a natural conversational experience where the
     * user hears the response as it's being produced.
     *
     * @param apiKey DashScope API key
     */
    private static void realtimeAgentWithTTS(String apiKey) {
        System.out.println("=== Example 1: ReActAgent with Realtime TTS ===");
        System.out.println("Agent will speak while generating response...");
        System.out.println();

        // 1. Create realtime TTS model (WebSocket-based, streaming input + output)
        DashScopeRealtimeTTSModel ttsModel =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash-realtime") // WebSocket realtime model
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("pcm")
                        .build();

        // 2. Create audio player for local playback
        AudioPlayer player =
                AudioPlayer.builder()
                        .sampleRate(24000)
                        .sampleSizeInBits(16)
                        .channels(1)
                        .signed(true)
                        .bigEndian(false)
                        .build();

        // 3. Create TTSHook with realtime TTS model (WebSocket streaming)
        TTSHook ttsHook = TTSHook.builder().ttsModel(ttsModel).audioPlayer(player).build();

        // 4. Create chat model
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").build();

        // 5. Create agent with TTS hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("你是一个友善的助手，请用不多于30字的简洁文字回复")
                        .model(chatModel)
                        .hook(ttsHook)
                        .maxIters(3)
                        .build();

        // 6. Call agent - it will speak while generating!
        System.out.println("User: 告诉我一个有趣的冷知识");
        System.out.println("Assistant (speaking in real-time): ");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("告诉我一个有趣的冷知识").build())
                        .build();
        Msg response = agent.call(userMsg).block();

        if (response != null) {
            System.out.println(response.getTextContent());
        }

        // 7. Clean up
        try {
            Thread.sleep(4000); // Wait for audio to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ttsHook.stop();

        System.out.println();
    }

    // ========================================================================
    // Example 2: Standalone TTSModel
    // ========================================================================

    /**
     * Demonstrates using TTSModel independently without Agent.
     *
     * <p>Use this when you need text-to-speech synthesis outside of Agent context,
     * such as generating audio files, voice notifications, or any scenario where
     * you have text and want to convert it to speech directly.
     *
     * @param apiKey DashScope API key
     */
    private static void standaloneTTSModel(String apiKey) {
        System.out.println("=== Example 2: Standalone TTSModel ===");
        System.out.println("Using TTSModel directly without Agent...");
        System.out.println();

        // 2.1 Non-streaming synthesis
        System.out.println("--- 2.1 Non-streaming synthesis ---");

        DashScopeTTSModel ttsModel =
                DashScopeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash")
                        .voice("Cherry")
                        .build();

        TTSOptions options =
                TTSOptions.builder().sampleRate(24000).format("wav").language("Auto").build();

        String text = "你好, 欢迎来到 AgentScope Java 的 TTS demo.";
        System.out.println("Text: " + text);

        TTSResponse response = ttsModel.synthesize(text, options).block();

        if (response != null) {
            System.out.println("Request ID: " + response.getRequestId());
            if (response.getAudioData() != null) {
                System.out.println(
                        "Audio Data: " + response.getAudioData().length + " bytes, playing...");

                // Play audio using AudioPlayer
                AudioPlayer nonStreamPlayer =
                        AudioPlayer.builder()
                                .sampleRate(24000)
                                .sampleSizeInBits(16)
                                .channels(1)
                                .signed(true)
                                .bigEndian(false)
                                .build();
                nonStreamPlayer.start();
                nonStreamPlayer.play(response.toAudioBlock());
                nonStreamPlayer.drain();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                nonStreamPlayer.stop();
                System.out.println("Playback completed.");
            } else if (response.getAudioUrl() != null) {
                System.out.println("Audio URL: " + response.getAudioUrl());
            }
        }

        // 2.2 Streaming synthesis
        System.out.println();
        System.out.println("--- 2.2 Streaming synthesis ---");

        DashScopeRealtimeTTSModel realtimeTts =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash-realtime") // WebSocket realtime model
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("pcm")
                        .build();

        AudioPlayer player =
                AudioPlayer.builder()
                        .sampleRate(24000)
                        .sampleSizeInBits(16)
                        .channels(1)
                        .signed(true)
                        .bigEndian(false)
                        .build();

        String longText = "这是一个语音合成流式返回的演示，音频片段会分片到达。";
        System.out.println("Text: " + longText);
        System.out.println("Playing streaming audio...");

        player.start();
        realtimeTts.synthesizeStream(longText).doOnNext(audio -> player.play(audio)).blockLast();
        player.drain();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        player.stop();

        System.out.println("Streaming playback completed.");
        System.out.println();
    }

    // ========================================================================
    // Example 3: Agent with TTS Tool
    // ========================================================================

    /**
     * Demonstrates using TTS as an Agent tool.
     *
     * <p>The Agent is given access to the TTS tool and decides when to invoke it
     * based on user requests. This is useful when the Agent should autonomously
     * decide whether to respond with audio, such as when the user explicitly asks
     * for spoken output.
     *
     * @param apiKey DashScope API key
     */
    private static void agentWithTTSTool(String apiKey) {
        System.out.println("=== Example 4: Agent with TTS Tool ===");
        System.out.println("Agent will invoke TTS tool when appropriate...");
        System.out.println();

        // 1. Create chat model
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").build();

        // 2. Create multimodal tool and register it
        DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(multiModalTool);

        // 3. Create agent with multimodal toolkit
        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultiModalAssistant")
                        .sysPrompt(
                                "You are a multimodal assistant. "
                                        + "When user asks you to speak or generate audio, "
                                        + "use the dashscope_text_to_audio tool.")
                        .model(chatModel)
                        .toolkit(toolkit)
                        .maxIters(3)
                        .build();

        // 4. Ask agent to generate audio
        System.out.println("User: Please say 'Welcome to AgentScope' in audio");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("Please say 'Welcome to AgentScope' in audio")
                                        .build())
                        .build();
        Msg response = agent.call(userMsg).block();

        if (response != null) {
            System.out.println("Agent response:");

            // Extract audio from response
            boolean foundAudio = false;
            for (ContentBlock block : response.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    System.out.println("  Text: " + textBlock.getText());
                } else if (block instanceof AudioBlock audio) {
                    foundAudio = true;
                    System.out.println("  Audio: [AudioBlock generated]");
                    System.out.println(
                            "  Source type: " + audio.getSource().getClass().getSimpleName());
                }
            }

            if (!foundAudio) {
                System.out.println("  (Agent may have responded with text instead of audio)");
                System.out.println("  Full response: " + response.getTextContent());
            }
        }

        System.out.println();
    }

    // ========================================================================
    // Example 4: Realtime TTS with Push/Finish Pattern
    // ========================================================================

    /**
     * Demonstrates DashScopeRealtimeTTSModel with push/finish pattern.
     *
     * <p>This example shows how to use the WebSocket-based realtime TTS model
     * with incremental text input. Key features:
     * <ul>
     *   <li>startSession() - Establish WebSocket connection</li>
     *   <li>push(text) - Send text incrementally (context is maintained)</li>
     *   <li>finish() - Signal end of input, get remaining audio</li>
     *   <li>getAudioStream() - Subscribe to receive audio as it's generated</li>
     * </ul>
     *
     * <p>This pattern is ideal for:
     * <ul>
     *   <li>Streaming LLM output to TTS in real-time</li>
     *   <li>Building voice assistants with low latency</li>
     *   <li>Scenarios where text arrives incrementally</li>
     * </ul>
     *
     * @param apiKey DashScope API key
     */
    private static void standaloneRealtimeTTSDemo(String apiKey) {
        System.out.println("=== Example 3: Realtime TTS with Push/Finish Pattern ===");
        System.out.println("Using WebSocket streaming with incremental text input...");
        System.out.println();

        // 1. Create realtime TTS model with server_commit mode
        // server_commit: Server automatically commits text for synthesis
        // commit: Client must manually call commitTextBuffer()
        DashScopeRealtimeTTSModel ttsModel =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash-realtime")
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("pcm")
                        .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)
                        .languageType("Auto")
                        .build();

        // 2. Create audio player
        AudioPlayer player =
                AudioPlayer.builder()
                        .sampleRate(24000)
                        .sampleSizeInBits(16)
                        .channels(1)
                        .signed(true)
                        .bigEndian(false)
                        .build();

        // 3. Start player
        player.start();

        // 4. Start TTS session (establishes WebSocket connection)
        System.out.println("Starting TTS session...");
        ttsModel.startSession();

        // 5. Subscribe to audio stream BEFORE pushing text
        // Audio arrives asynchronously via WebSocket callback
        System.out.println("Subscribing to audio stream...");
        ttsModel.getAudioStream()
                .doOnNext(
                        audio -> {
                            player.play(audio);
                        })
                .doOnComplete(() -> System.out.println("Audio stream completed."))
                .subscribe();

        // 6. Push text incrementally (simulating LLM streaming output)
        String[] textChunks = {"你好，", "我是", "你的", "语音", "助手，", "很高兴", "为你", "服务！"};

        System.out.println("Pushing text chunks: ");
        for (String chunk : textChunks) {
            System.out.print(chunk);
            ttsModel.push(chunk);

            // Simulate delay between chunks (like LLM streaming)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println();

        // 7. Finish session and wait for all audio to complete
        System.out.println("Finishing session, waiting for audio...");
        ttsModel.finish().blockLast();

        // 8. Drain player to ensure all audio is played
        player.drain();

        // 9. Wait a bit for audio playback to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 10. Clean up
        player.stop();
        ttsModel.close();

        System.out.println("Push/Finish pattern example completed.");
        System.out.println();
    }
}
