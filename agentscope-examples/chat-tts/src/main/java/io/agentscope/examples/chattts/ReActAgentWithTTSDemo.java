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
package io.agentscope.examples.chattts;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import java.util.Scanner;

/**
 * Interactive CLI demo of ReActAgent with real-time TTS.
 *
 * <p>The agent speaks while generating response, and text is printed
 * incrementally as it's generated.
 *
 * <p>Usage:
 * <pre>
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn exec:java -pl agentscope-examples/chat-tts \
 *   -Dexec.mainClass="io.agentscope.examples.chattts.ReActAgentWithTTSDemo"
 * </pre>
 */
public class ReActAgentWithTTSDemo {

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

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║     ReActAgent + Real-time TTS Interactive Demo      ║");
        System.out.println("║     Agent speaks while generating response           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // 1. Create TTS Model
        System.out.println("Initializing TTS Model...");
        DashScopeRealtimeTTSModel ttsModel =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash")
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("wav")
                        .build();

        // 2. Create Audio Player
        System.out.println("Initializing Audio Player...");
        AudioPlayer player =
                AudioPlayer.builder()
                        .sampleRate(24000)
                        .sampleSizeInBits(16)
                        .channels(1)
                        .signed(true)
                        .bigEndian(false)
                        .build();

        // 3. Create TTSHook (Real-time Mode)
        System.out.println("Initializing TTSHook (realtime mode)...");
        TTSHook ttsHook =
                TTSHook.builder().ttsModel(ttsModel).audioPlayer(player).realtimeMode(true).build();

        // 4. Create Chat Model
        System.out.println("Initializing Chat Model...");
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").build();

        // 5. Create ReActAgent
        System.out.println("Creating ReActAgent...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("你是一个友好的中文助手。请用简洁的语言回答问题，控制在100字以内。")
                        .model(chatModel)
                        .hook(ttsHook)
                        .maxIters(3)
                        .build();

        // 6. StreamOptions for incremental text output
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build();

        System.out.println();
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println();

        // 7. Demo: Ask an initial question
        String initialQuestion = "介绍一下杭州";
        askQuestion(agent, streamOptions, initialQuestion);

        System.out.println("Type your message and press Enter. Type 'exit' to quit.");
        System.out.println();

        // 8. Interactive loop
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                break;
            }

            askQuestion(agent, streamOptions, input);
        }

        // Cleanup
        ttsHook.stop();
        scanner.close();

        System.out.println();
        System.out.println("Goodbye!");
    }

    /**
     * Ask a question and stream the response with TTS.
     *
     * @param agent the ReActAgent
     * @param streamOptions streaming options
     * @param question the question to ask
     */
    private static void askQuestion(
            ReActAgent agent, StreamOptions streamOptions, String question) {
        System.out.println("You: " + question);

        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(question).build())
                        .build();

        System.out.print("Assistant: ");

        agent.stream(userMsg, streamOptions)
                .doOnNext(
                        event -> {
                            String text = event.getMessage().getTextContent();
                            if (text != null && !text.isEmpty()) {
                                System.out.print(text);
                                System.out.flush();
                            }
                        })
                .doOnComplete(() -> System.out.println())
                .blockLast();

        // Brief pause for audio to finish
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println();
    }
}
