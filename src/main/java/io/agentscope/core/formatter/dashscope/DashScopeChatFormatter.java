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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.FormatterCapabilities;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Formatter for DashScope Conversation/Generation APIs.
 * Converts between AgentScope Msg objects and DashScope SDK types.
 *
 * <p>This formatter handles both text and multimodal messages, supporting the DashScope
 * Generation API and MultiModalConversation API.
 */
public class DashScopeChatFormatter
        extends AbstractBaseFormatter<Message, GenerationResult, GenerationParam> {

    private final DashScopeMessageConverter messageConverter;
    private final DashScopeResponseParser responseParser;
    private final DashScopeToolsHelper toolsHelper;

    public DashScopeChatFormatter() {
        this.messageConverter = new DashScopeMessageConverter(this::convertToolResultToString);
        this.responseParser = new DashScopeResponseParser();
        this.toolsHelper = new DashScopeToolsHelper();
    }

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            Message dsMsg = messageConverter.convertToMessage(msg, hasMedia);
            if (dsMsg != null) {
                result.add(dsMsg);
            }
        }
        return result;
    }

    @Override
    public ChatResponse parseResponse(GenerationResult result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            GenerationParam param, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(
                param,
                options,
                defaultOptions,
                opt -> getOptionOrDefault(options, defaultOptions, opt));
    }

    @Override
    public void applyTools(GenerationParam param, List<ToolSchema> tools) {
        toolsHelper.applyTools(param, tools);
    }

    /**
     * Format AgentScope Msg objects to DashScope MultiModalMessage format.
     * This method is used for vision models that require the MultiModalConversation API.
     *
     * @param messages The AgentScope messages to convert
     * @return List of MultiModalMessage objects ready for DashScope MultiModalConversation API
     */
    public List<MultiModalMessage> formatMultiModal(List<Msg> messages) {
        return messages.stream()
                .map(messageConverter::convertToMultiModalMessage)
                .collect(Collectors.toList());
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class,
                                ImageBlock.class,
                                AudioBlock.class,
                                VideoBlock.class))
                .build();
    }
}
