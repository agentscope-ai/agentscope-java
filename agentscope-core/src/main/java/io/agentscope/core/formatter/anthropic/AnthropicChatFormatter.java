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
package io.agentscope.core.formatter.anthropic;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicMessage;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Anthropic Messages API. Converts between AgentScope Msg objects
 * and Anthropic DTO types.
 *
 * <p>Important: Anthropic API has special requirements:
 * <ul>
 *   <li>Only the first message can be a system message (handled via system parameter)
 *   <li>Tool results must be in separate user messages
 *   <li>Supports thinking blocks natively (extended thinking feature)
 *   <li>Automatic multi-agent conversation handling for MsgHub scenarios
 * </ul>
 *
 * <p><b>Multi-Agent Detection:</b> This formatter automatically detects multi-agent scenarios
 * (e.g., MsgHub conversations) by checking for multiple messages with different names but
 * the same role. When detected, it uses {@link AnthropicConversationMerger} to consolidate the
 * conversation into a format compatible with Anthropic's API.
 */
public class AnthropicChatFormatter extends AnthropicBaseFormatter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatFormatter.class);

    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";

    private final AnthropicMediaConverter mediaConverter;

    public AnthropicChatFormatter() {
        super();
        this.mediaConverter = new AnthropicMediaConverter();
    }

    public AnthropicChatFormatter(AnthropicMessageConverter messageConverter) {
        super(messageConverter);
        this.mediaConverter = new AnthropicMediaConverter();
    }

    @Override
    public List<AnthropicMessage> doFormat(List<Msg> msgs) {
        // Detect multi-agent scenario (multiple messages with different names but same role)
        boolean isMultiAgent = isMultiAgentConversation(msgs);
        log.debug(
                "doFormat: message count={}, isMultiAgent={}",
                msgs != null ? msgs.size() : 0,
                isMultiAgent);

        if (isMultiAgent) {
            log.info("Detected multi-agent conversation, using conversation merger");
            return formatMultiAgentConversation(msgs);
        }

        // Single-agent or simple conversation - use standard formatting
        log.debug("Using standard formatting for single-agent conversation");
        return messageConverter.convert(msgs);
    }

    /**
     * Detects if the message list represents a multi-agent conversation.
     *
     * <p>A multi-agent conversation is detected when:
     * <ul>
     *   <li>There are at least 2 ASSISTANT role messages with different names</li>
     *   <li>OR there are multiple ASSISTANT messages that would create consecutive
     *       messages with the same role</li>
     * </ul>
     *
     * @param msgs List of messages to check
     * @return true if this appears to be a multi-agent conversation
     */
    private boolean isMultiAgentConversation(List<Msg> msgs) {
        if (msgs == null || msgs.size() < 2) {
            log.debug(
                    "isMultiAgentConversation: too few messages (count={})",
                    msgs != null ? msgs.size() : 0);
            return false;
        }

        Set<String> assistantNames = new HashSet<>();
        MsgRole lastRole = null;
        boolean hasConsecutiveAssistant = false;
        boolean hasUserAndAssistant = false;
        boolean hasUserWithAgentName = false;

        for (int i = 0; i < msgs.size(); i++) {
            Msg msg = msgs.get(i);
            MsgRole currentRole = msg.getRole();
            String msgName = msg.getName();

            log.trace("Message {}: role={}, name={}", i, currentRole, msgName);

            // Check for consecutive ASSISTANT messages (without tool calls in between)
            if (lastRole == MsgRole.ASSISTANT && currentRole == MsgRole.ASSISTANT) {
                hasConsecutiveAssistant = true;
                log.debug("Found consecutive ASSISTANT messages at index {}", i);
            }

            // Check if we have both USER and ASSISTANT messages (typical MsgHub pattern)
            if ((lastRole == MsgRole.USER && currentRole == MsgRole.ASSISTANT)
                    || (lastRole == MsgRole.ASSISTANT && currentRole == MsgRole.USER)) {
                hasUserAndAssistant = true;
            }

            // Check if USER message has an agent name (indicates multi-agent context)
            if (currentRole == MsgRole.USER && msgName != null && !msgName.equals("user")) {
                hasUserWithAgentName = true;
                log.debug("Found USER message with agent name: {}", msgName);
            }

            // Collect ASSISTANT message names
            if (currentRole == MsgRole.ASSISTANT && msgName != null) {
                assistantNames.add(msgName);
            }

            lastRole = currentRole;
        }

        // Multi-agent if:
        // 1. Multiple assistant names, OR
        // 2. Consecutive assistant messages, OR
        // 3. User + Assistant pattern with named agent
        boolean result =
                assistantNames.size() > 1
                        || hasConsecutiveAssistant
                        || (hasUserAndAssistant && hasUserWithAgentName);
        log.debug(
                "isMultiAgentConversation: assistantNames={}, hasConsecutive={}, "
                        + "hasUserAndAssistant={}, hasUserWithAgentName={}, result={}",
                assistantNames,
                hasConsecutiveAssistant,
                hasUserAndAssistant,
                hasUserWithAgentName,
                result);
        return result;
    }

    /**
     * Formats a multi-agent conversation using the conversation merger.
     * This consolidates multiple agent messages into a format compatible with Anthropic's API.
     *
     * @param msgs List of messages in the multi-agent conversation
     * @return List of Anthropic-formatted messages
     */
    private List<AnthropicMessage> formatMultiAgentConversation(List<Msg> msgs) {
        log.debug("formatMultiAgentConversation: processing {} messages", msgs.size());

        // Simplified approach: separate system from non-system, merge everything else
        List<AnthropicMessage> result = new ArrayList<>();
        List<Msg> systemMsgs = new ArrayList<>();
        List<Msg> conversationMsgs = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.SYSTEM) {
                systemMsgs.add(msg);
            } else {
                conversationMsgs.add(msg);
            }
        }

        // Add system messages using standard converter
        for (Msg sysMsg : systemMsgs) {
            List<AnthropicMessage> converted = messageConverter.convert(List.of(sysMsg));
            result.addAll(converted);
        }

        // Merge all conversation messages into a single user message
        if (!conversationMsgs.isEmpty()) {
            log.debug("Merging {} conversation messages", conversationMsgs.size());

            List<Object> mergedContent =
                    AnthropicConversationMerger.mergeConversation(
                            conversationMsgs, DEFAULT_CONVERSATION_HISTORY_PROMPT);

            List<AnthropicContent> contentBlocks = new ArrayList<>();

            for (Object item : mergedContent) {
                if (item instanceof String text) {
                    contentBlocks.add(AnthropicContent.text(text));
                    log.trace("Added text content block (length: {})", text.length());
                } else if (item instanceof ImageBlock ib) {
                    try {
                        AnthropicContent.ImageSource imageSource =
                                mediaConverter.convertImageBlock(ib);
                        contentBlocks.add(
                                AnthropicContent.image(
                                        imageSource.getMediaType(), imageSource.getData()));
                        log.trace("Added image content block");
                    } catch (Exception e) {
                        log.warn("Failed to convert image block: {}", e.getMessage());
                        contentBlocks.add(AnthropicContent.text("[Image - conversion failed]"));
                    }
                }
            }

            if (!contentBlocks.isEmpty()) {
                AnthropicMessage mergedMessage = new AnthropicMessage("user", contentBlocks);
                result.add(mergedMessage);
                log.debug(
                        "Created merged user message with {} content blocks", contentBlocks.size());
            } else {
                log.warn("No content blocks created from merged conversation");
            }
        }

        log.debug("formatMultiAgentConversation: returning {} messages", result.size());
        return result;
    }

    @Override
    public ChatResponse parseResponse(AnthropicResponse response, Instant startTime) {
        return AnthropicResponseParser.parseMessage(response, startTime);
    }
}
