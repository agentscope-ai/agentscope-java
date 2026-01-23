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
package io.agentscope.core.tool.multimodal;

import io.agentscope.core.message.ToolResultBlock;

/**
 * E2E test for DashScope TTS.
 *
 * <p>Usage: export DASHSCOPE_API_KEY=sk-xxx && mvn exec:java -pl agentscope-core \
 * -Dexec.mainClass="io.agentscope.core.tool.multimodal.DashScopeTTSE2ETest"
 */
public class DashScopeTTSE2ETest {

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set DASHSCOPE_API_KEY");
            return;
        }

        DashScopeMultiModalTool tool = new DashScopeMultiModalTool(apiKey);

        // Test Qwen TTS
        System.out.println("=== Testing Qwen TTS (qwen3-tts-flash) ===");
        String text = "你好，欢迎使用语音合成功能。";
        System.out.println("Text: " + text);

        ToolResultBlock result =
                tool.dashscopeTextToAudio(text, "qwen3-tts-flash", "Cherry", "Chinese", null)
                        .block();

        if (result.getOutput() != null && !result.getOutput().isEmpty()) {
            System.out.println("Result: SUCCESS");
            System.out.println("Audio generated successfully!");
        } else {
            System.out.println("Result: ERROR - No output");
        }

        System.out.println("\nDone!");
    }
}
