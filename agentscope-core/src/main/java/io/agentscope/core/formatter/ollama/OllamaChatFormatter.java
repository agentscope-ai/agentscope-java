/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatter for Ollama Chat API.
 * Converts between AgentScope Msg objects and Ollama DTO types.
 *
 */
public class OllamaChatFormatter
        extends AbstractBaseFormatter<OllamaMessage, OllamaResponse, OllamaRequest> {

    private final OllamaMessageConverter messageConverter;
    private final OllamaResponseParser responseParser;
    private final OllamaToolsHelper toolsHelper;
    private final boolean promoteToolResultImages;

    public OllamaChatFormatter() {
        this(false);
    }

    public OllamaChatFormatter(boolean promoteToolResultImages) {
        this.messageConverter = new OllamaMessageConverter();
        this.responseParser = new OllamaResponseParser();
        this.toolsHelper = new OllamaToolsHelper();
        this.promoteToolResultImages = promoteToolResultImages;
    }

    @Override
    protected List<OllamaMessage> doFormat(List<Msg> msgs) {
        List<OllamaMessage> result = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            Msg msg = msgs.get(i);
            OllamaMessage convertedMsg = messageConverter.convertMessage(msg);
            result.add(convertedMsg);

            // If promoteToolResultImages is enabled and this is a tool result with images,
            // add a separate user message with the images after the tool result
            if (promoteToolResultImages && isToolResultWithImages(convertedMsg)) {
                OllamaMessage imageMsg = createImagePromotionMessage(msg, convertedMsg);
                if (imageMsg != null) {
                    result.add(imageMsg);
                }
            }
        }
        return result;
    }

    private boolean isToolResultWithImages(OllamaMessage msg) {
        return "tool".equals(msg.getRole())
                && msg.getContent() != null
                && msg.getContent().contains("image")
                && msg.getContent().contains("can be found at:");
    }

    private OllamaMessage createImagePromotionMessage(Msg originalMsg, OllamaMessage convertedMsg) {
        // Extract image paths from the tool result content
        // Look for image paths in the format "image can be found at: ./path"
        String content = convertedMsg.getContent();

        // Find image paths in the content
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("can be found at: ([^\s\n]+)");
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String imagePath = matcher.group(1);

            // Try to convert the image to base64
            try {
                io.agentscope.core.message.ImageBlock imageBlock =
                        extractImageBlockFromMsg(originalMsg);
                if (imageBlock != null) {
                    String base64Image =
                            new OllamaMediaConverter().convertImageBlockToBase64(imageBlock);

                    OllamaMessage imageMsg = new OllamaMessage();
                    imageMsg.setRole("user");
                    imageMsg.setContent(
                            "<system-info>The following are "
                                    + "the image contents from the tool "
                                    + "result of '"
                                    + convertedMsg.getName()
                                    + "':\n\n"
                                    + "- The image from '"
                                    + imagePath
                                    + "': \n</system-info>");
                    imageMsg.setImages(java.util.Collections.singletonList(base64Image));
                    return imageMsg;
                }
            } catch (Exception e) {
                // Log error but don't fail the whole request
                org.slf4j.LoggerFactory.getLogger(OllamaChatFormatter.class)
                        .warn("Failed to promote image from tool result", e);
            }
        }

        return null;
    }

    private io.agentscope.core.message.ImageBlock extractImageBlockFromMsg(Msg msg) {
        for (io.agentscope.core.message.ContentBlock block : msg.getContent()) {
            if (block instanceof io.agentscope.core.message.ImageBlock) {
                return (io.agentscope.core.message.ImageBlock) block;
            } else if (block instanceof io.agentscope.core.message.ToolResultBlock) {
                io.agentscope.core.message.ToolResultBlock toolResult =
                        (io.agentscope.core.message.ToolResultBlock) block;
                for (io.agentscope.core.message.ContentBlock outputBlock : toolResult.getOutput()) {
                    if (outputBlock instanceof io.agentscope.core.message.ImageBlock) {
                        return (io.agentscope.core.message.ImageBlock) outputBlock;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public ChatResponse parseResponse(OllamaResponse result, Instant startTime) {
        return responseParser.parseResponse(result);
    }

    @Override
    public void applyOptions(
            OllamaRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    /**
     * Apply OllamaOptions to the request.
     *
     * @param request The request to apply options to
     * @param options The runtime options
     * @param defaultOptions The default options
     */
    public void applyOptions(
            OllamaRequest request, OllamaOptions options, OllamaOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    @Override
    public void applyTools(OllamaRequest request, List<ToolSchema> tools) {
        toolsHelper.applyTools(request, tools);
    }

    @Override
    public void applyToolChoice(OllamaRequest request, ToolChoice toolChoice) {
        toolsHelper.applyToolChoice(request, toolChoice);
    }

    /**
     * Build a complete OllamaRequest for the API call.
     *
     * @param model Model name
     * @param messages Formatted Ollama messages
     * @param stream Whether to enable streaming
     * @param options Generation options
     * @param defaultOptions Default generation options
     * @param tools Tool schemas
     * @param toolChoice Tool choice configuration
     * @return Complete OllamaRequest ready for API call
     */
    public OllamaRequest buildRequest(
            String model,
            List<OllamaMessage> messages,
            boolean stream,
            OllamaOptions options,
            OllamaOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        OllamaRequest.Builder builder =
                OllamaRequest.builder().model(model).messages(messages).stream(stream);

        OllamaRequest request = builder.build();

        applyOptions(request, options, defaultOptions);
        if (tools != null && !tools.isEmpty()) {
            applyTools(request, tools);
        }
        applyToolChoice(request, toolChoice);

        return request;
    }
}
