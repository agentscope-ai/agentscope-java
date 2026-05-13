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
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

/**
 * Interactive CLI demo of ReActAgent with real-time TTS.
 *
 * <p>Features:
 * <ul>
 *   <li>Real-time TTS: Agent speaks while generating response</li>
 *   <li>Auto-interrupt: When user sends a new message while Agent is speaking,
 *       the current audio is automatically interrupted and new audio starts</li>
 * </ul>
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

        // Create TTS model and hook
        // Use qwen3-tts-flash-realtime which supports system voices (Cherry, Serena, etc.)
        // VD model requires voice design, VC model requires voice cloning
        DashScopeRealtimeTTSModel ttsModel =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash-realtime")
                        .voice("Cherry")
                        .build();

        // TTSHook will automatically create a default AudioPlayer if not provided
        TTSHook ttsHook = TTSHook.builder().ttsModel(ttsModel).build();

        // Create agents
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("你是一个友好的中文助手。请用简洁的语言回答问题。")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .build())
                        .hook(ttsHook)
                        .build();

        UserAgent user = UserAgent.builder().name("User").build();

        // Main loop
        Msg msg = null;
        while (true) {
            msg = user.call(msg).block();
            if ("exit".equalsIgnoreCase(msg.getTextContent())) {
                break;
            }
            msg = agent.call(msg).block();
        }

        ttsHook.stop();
    }
}
