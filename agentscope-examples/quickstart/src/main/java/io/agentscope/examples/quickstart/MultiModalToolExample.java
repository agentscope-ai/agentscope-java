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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * MultiModalToolExample - Demonstrates how to equip an Agent with multimodal tools.
 */
public class MultiModalToolExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "MultiModal Tool Calling Example",
                "This example demonstrates how to equip an Agent with multimodal tools.\n"
                        + "The agent has image, audio and video multimodal tools.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create and register tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DashScopeMultiModalTool(apiKey));
        printRegisterTools();

        // Create Agent with tools
        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultiModalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to multimodal"
                                        + " tools. Use tools when needed to answer questions"
                                        + " accurately. Always explain what you're doing when using"
                                        + " tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .hook(new ToolCallLoggingHook())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        printExamplePrompts();

        ExampleUtils.startChat(agent);
    }

    private static void printRegisterTools() {
        String registeredTools =
                """
                Registered tools:
                - dashscope_text_to_image: Generate image(s) based on the given text.
                - dashscope_image_to_text: Generate text based on the given images.
                - dashscope_text_to_audio: Convert the given text to audio.
                - dashscope_audio_to_text: Convert the given audio to text.
                - dashscope_text_to_video: Generate video based on the given text prompt.
                - dashscope_image_to_video: Generate a video from a single input image and an optional text prompt.
                - dashscope_first_and_last_frame_image_to_video: Generate video transitioning from a first frame to a last frame and an optional text prompt.
                - dashscope_video_to_text: Analyze video and generate a text description or answer questions based on the video content.
                """;

        System.out.println(registeredTools);
        System.out.println("\n");
    }

    private static void printExamplePrompts() {
        String examplePrompts =
                """
                Example Prompts:
                [dashscope_text_to_image]:
                Generate a black dog image url.
                [dashscope_image_to_text]:
                Describe the image url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png'.
                [dashscope_text_to_audio]:
                Convert the texts of 'hello, qwen!' to audio url.
                [dashscope_audio_to_text]:
                Convert the audio url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male2.wav' to text.
                [dashscope_text_to_video]:
                Generate a smart cat is running in the moonlight video.
                [dashscope_image_to_video]:
                Generate a video that a tiger is running in moonlight based on the image url of 'https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png'.
                [dashscope_first_and_last_frame_image_to_video]:
                Generate a video that a black kitten curiously looking at the sky based on the first frame image url of 'https://wanx.alicdn.com/material/20250318/first_frame.png' and the last frame image url of 'https://wanx.alicdn.com/material/20250318/last_frame.png'.
                [dashscope_video_to_text]:
                Describe the video url of 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4'.
                """;
        System.out.println(examplePrompts);
        System.out.println("\n");
    }

    static class ToolCallLoggingHook implements Hook {

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreActingEvent preActing) {
                System.out.println(
                        "\n[HOOK] PreActingEvent - Tool: "
                                + preActing.getToolUse().getName()
                                + ", Input: "
                                + preActing.getToolUse().getInput());

            } else if (event instanceof PostActingEvent postActingEvent) {
                ToolResultBlock toolResult = postActingEvent.getToolResult();
                List<ContentBlock> contentBlocks = toolResult.getOutput();
                if (contentBlocks != null && !contentBlocks.isEmpty()) {
                    for (ContentBlock cb : contentBlocks) {
                        if (cb instanceof ImageBlock ib) {
                            Source source = ib.getSource();
                            if (source instanceof URLSource urlSource) {
                                System.out.println(
                                        "\n[HOOK] PostActingEvent - Tool Result: \nImage URL: "
                                                + urlSource.getUrl());
                            } else if (source instanceof Base64Source base64Source) {
                                System.out.println(
                                        "\n"
                                                + "[HOOK] PostActingEvent - Tool Result: \n"
                                                + "Image Base64 data: "
                                                + base64Source.getData());
                            }
                        } else if (cb instanceof AudioBlock ab) {
                            Source source = ab.getSource();
                            if (source instanceof URLSource urlSource) {
                                System.out.println(
                                        "\n[HOOK] PostActingEvent - Tool Result: \nAudio URL: "
                                                + urlSource.getUrl());
                            } else if (source instanceof Base64Source base64Source) {
                                System.out.println(
                                        "\n"
                                                + "[HOOK] PostActingEvent - Tool Result: \n"
                                                + "Audio Base64 data: "
                                                + base64Source.getData());
                            }
                        } else if (cb instanceof VideoBlock vb) {
                            Source source = vb.getSource();
                            if (source instanceof URLSource urlSource) {
                                System.out.println(
                                        "\n[HOOK] PostActingEvent - Tool Result: \nVideo URL: "
                                                + urlSource.getUrl());
                            } else if (source instanceof Base64Source base64Source) {
                                System.out.println(
                                        "\n"
                                                + "[HOOK] PostActingEvent - Tool Result: \n"
                                                + "Video Base64 data: "
                                                + base64Source.getData());
                            }
                        } else if (cb instanceof TextBlock tb) {
                            System.out.println(
                                    "\n[HOOK] PostActingEvent - Tool Result: \nText: "
                                            + tb.getText());
                        }
                    }
                    System.out.println("\n");
                }
            }
            return Mono.just(event);
        }
    }
}
