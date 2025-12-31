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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Debug test to see what GLM API returns for structured output request.
 *
 * <p>Tests confirmed:
 * 1. Simple chat with GLM-4 Plus works correctly
 * 2. Tool calling setup works (when tools are properly defined via toolkit)
 *
 * <p>Note: When tool_choice is set but no tools are defined in the request,
 * GLM returns a text description instead of an actual tool call.
 */
@Tag("debug")
@Disabled("Manual debug test - GLM API verified working")
class GLMApiDebugTest {

    private static final String API_KEY = System.getenv("GLM_API_KEY");
    private static final String BASE_URL =
            System.getenv().getOrDefault("GLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4/");
    private static final String MODEL =
            System.getenv().getOrDefault("GLM_MODEL_NAME", "glm-4-plus");

    @Test
    void testSimpleGLMChat() throws Exception {
        if (API_KEY == null || API_KEY.isEmpty()) {
            System.out.println("Skipping test: GLM_API_KEY not set");
            return;
        }
        OpenAIChatModel model =
                OpenAIChatModel.builder().baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL).stream(
                                false)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Say 'Hello, World!'").build()))
                        .build();

        ChatResponse result =
                model.stream(List.of(input), null, null).blockLast(Duration.ofSeconds(30));
        assertNotNull(result);
        System.out.println("Response: " + ((TextBlock) result.getContent().get(0)).getText());
    }

    @Test
    void testGLMWithTool() throws Exception {
        if (API_KEY == null || API_KEY.isEmpty()) {
            System.out.println("Skipping test: GLM_API_KEY not set");
            return;
        }
        OpenAIChatModel model =
                OpenAIChatModel.builder().baseUrl(BASE_URL).apiKey(API_KEY).modelName(MODEL).stream(
                                false)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please call the generate_response tool"
                                                                + " with a simple greeting")
                                                .build()))
                        .build();

        // Try with tool - this is what structured output does
        Map<String, Object> toolChoice = new HashMap<>();
        toolChoice.put("type", "function");
        Map<String, Object> function = new HashMap<>();
        function.put("name", "generate_response");
        toolChoice.put("function", function);

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(
                                new io.agentscope.core.model.ToolChoice.Specific(
                                        "generate_response"))
                        .build();

        try {
            ChatResponse result =
                    model.stream(List.of(input), null, options).blockLast(Duration.ofSeconds(30));
            System.out.println("=== GLM Tool Response ===");
            System.out.println("Result: " + result);
            System.out.println("Content size: " + result.getContent().size());
            result.getContent()
                    .forEach(
                            block -> {
                                System.out.println(
                                        "  Block type: " + block.getClass().getSimpleName());
                                if (block instanceof io.agentscope.core.message.TextBlock t) {
                                    System.out.println("  Text: " + t.getText());
                                } else if (block
                                        instanceof io.agentscope.core.message.ToolUseBlock t) {
                                    System.out.println("  Tool name: " + t.getName());
                                    System.out.println("  Tool input: " + t.getInput());
                                }
                            });
            if (result.getUsage() != null) {
                System.out.println(
                        "Usage: input="
                                + result.getUsage().getInputTokens()
                                + ", output="
                                + result.getUsage().getOutputTokens());
            }
            System.out.println("========================");
        } catch (Exception e) {
            System.out.println("Error: " + e.getClass().getName());
            System.out.println("Message: " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println(
                        "Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }
}
