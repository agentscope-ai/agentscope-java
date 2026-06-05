/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.examples.documentation2.multimodal;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import io.agentscope.examples.documentation2.common.ExampleUtils;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * MultiModalToolExample - Demonstrates multimodal tools with a tool-call logging middleware.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced {@code ToolCallLoggingHook implements Hook} with
 *       {@code ToolCallLoggingMiddleware implements MiddlewareBase}.</li>
 *   <li>{@code PreActingEvent} → {@code onActing()} invoked before {@code next.apply()};
 *       tool name and input read from {@code ActingInput.toolCalls()}.</li>
 *   <li>{@code PostActingEvent} → tap {@code ToolResultEndEvent} and {@code ToolResultTextDeltaEvent}
 *       inside the {@code onActing()} stream; full content inspection via
 *       {@code agent.getState().getContext()} is available after the call returns.</li>
 *   <li>Removed {@code .memory(new InMemoryMemory())}.</li>
 *   <li>{@code .hook(...)} → {@code .middleware(...)}.</li>
 * </ul>
 */
public class MultiModalToolExample {

    /**
     * Runs the multimodal tool example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "MultiModal Tool Calling Example",
                "This example demonstrates how to equip an Agent with multimodal tools.\n"
                        + "The agent has image, audio and video multimodal tools.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DashScopeMultiModalTool(apiKey));
        printRegisteredTools();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultiModalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to multimodal tools."
                                        + " Use tools when needed to answer questions accurately."
                                        + " Always explain what you're doing when using tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new ToolCallLoggingMiddleware())
                        .build();

        printExamplePrompts();
        ExampleUtils.startChat(agent);
    }

    private static void printRegisteredTools() {
        String registeredTools =
                """
                Registered tools:
                - dashscope_text_to_image: Generate image(s) based on the given text.
                - dashscope_image_to_text: Generate text based on the given images.
                - dashscope_text_to_audio: Convert the given text to audio.
                - dashscope_audio_to_text: Convert the given audio to text.
                - dashscope_text_to_video: Generate video based on the given text prompt.
                - dashscope_image_to_video: Generate a video from a single input image and an optional text prompt.
                - dashscope_first_and_last_frame_image_to_video: Generate video transitioning between two frames.
                - dashscope_video_to_text: Analyze video and generate a text description.
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
                [dashscope_video_to_text]:
                Describe the video url of 'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4'.
                """;
        System.out.println(examplePrompts);
        System.out.println("\n");
    }

    /**
     * Middleware that logs tool call names / inputs before execution,
     * and logs tool result text deltas and final state after execution.
     */
    static class ToolCallLoggingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            // Log all tool calls before execution (replaces PreActingEvent)
            for (ToolUseBlock toolCall : input.toolCalls()) {
                System.out.println(
                        "\n[MIDDLEWARE] Tool call START — name: "
                                + toolCall.getName()
                                + ", input: "
                                + toolCall.getInput());
            }

            String toolNames =
                    input.toolCalls().stream()
                            .map(ToolUseBlock::getName)
                            .collect(Collectors.joining(", "));

            return next.apply(input)
                    .doOnNext(
                            event -> {
                                // Log streaming text deltas from the tool result
                                if (event instanceof ToolResultTextDeltaEvent delta) {
                                    System.out.println(
                                            "[MIDDLEWARE] Tool result delta (id="
                                                    + delta.getToolCallId()
                                                    + "): "
                                                    + delta.getDelta());
                                } else if (event instanceof ToolResultEndEvent end) {
                                    // Final completion status (replaces PostActingEvent)
                                    System.out.println(
                                            "[MIDDLEWARE] Tool result END — tool: "
                                                    + end.getToolCallId()
                                                    + ", state: "
                                                    + end.getState());
                                }
                            })
                    .doOnComplete(
                            () ->
                                    System.out.println(
                                            "[MIDDLEWARE] Tool call COMPLETE — "
                                                    + toolNames
                                                    + "\n"));
        }
    }
}
