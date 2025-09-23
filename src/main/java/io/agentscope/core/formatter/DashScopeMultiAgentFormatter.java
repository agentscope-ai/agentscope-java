/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.formatter;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ContentBlockUtils;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MediaInfo;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope formatter for multi-agent conversations, aligned with the Python
 * DashScopeMultiAgentFormatter.
 *
 * Behavior:
 * - Collapses multi-agent conversation (no tool calls) into a single user message
 *   with <history> ... </history> wrapped text, including agent names.
 * - Tool use/results remain as separate messages.
 * - Supports multimodal (image/audio) by passing through content blocks.
 */
public class DashScopeMultiAgentFormatter extends TruncatedFormatterBase {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiAgentFormatter.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    public DashScopeMultiAgentFormatter() {
        super();
    }

    public DashScopeMultiAgentFormatter(TokenCounter tokenCounter, Integer maxTokens) {
        super(tokenCounter, maxTokens);
    }

    @Override
    protected List<Map<String, Object>> formatInternal(List<Msg> msgs, FormatterOptions options) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Separate tool sequences to keep them as-is; collapse others into a single history message
        List<Msg> conversation = new ArrayList<>();
        List<Msg> toolSeq = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.ASSISTANT
                            && msg.getContent() instanceof ToolUseBlock)) {
                toolSeq.add(msg);
            } else {
                conversation.add(msg);
            }
        }

        if (!conversation.isEmpty()) {
            result.addAll(formatAgentConversation(conversation));
        }
        if (!toolSeq.isEmpty()) {
            result.addAll(formatToolSequence(toolSeq));
        }
        return result;
    }

    protected List<Map<String, Object>> formatAgentConversation(List<Msg> msgs) {
        // Build conversation blocks: include agent name and text lines; multimodal are appended as
        // separate blocks
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        StringBuilder textAccumulator = new StringBuilder();
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        for (Msg msg : msgs) {
            String name = msg.getName() != null ? msg.getName() : "Unknown";
            String role =
                    switch (msg.getRole()) {
                        case USER -> "User";
                        case ASSISTANT -> "Assistant";
                        case SYSTEM -> "System";
                        case TOOL -> "Tool";
                        default -> "Unknown";
                    };

            ContentBlock block = msg.getContent();
            if (block instanceof TextBlock tb) {
                textAccumulator
                        .append(role)
                        .append(" ")
                        .append(name)
                        .append(": ")
                        .append(tb.getText())
                        .append("\n");
            } else if (ContentBlockUtils.hasMediaContent(block)) {
                // Flush accumulated text before adding media
                if (textAccumulator.length() > 0) {
                    contentBlocks.add(Map.of("text", textAccumulator.toString()));
                    textAccumulator.setLength(0);
                }
                MediaInfo mediaInfo = ContentBlockUtils.getMediaInfo(block);
                if (mediaInfo != null) {
                    String key =
                            switch (block.getType()) {
                                case IMAGE -> "image";
                                case AUDIO -> "audio";
                                case VIDEO -> "video";
                                default -> "unknown";
                            };
                    if (!"unknown".equals(key)) {
                        contentBlocks.add(Map.of(key, normalizeMediaUrl(mediaInfo.getData())));
                    }
                }
            } else if (block instanceof ThinkingBlock tb) {
                textAccumulator
                        .append(role)
                        .append(" ")
                        .append(name)
                        .append(": ")
                        .append(tb.getThinking())
                        .append("\n");
            } else if (block instanceof ToolResultBlock toolResult) {
                // Handle ToolResultBlock in conversation history
                ContentBlock output = toolResult.getOutput();
                String resultText = "";
                if (output instanceof TextBlock textBlock) {
                    resultText = textBlock.getText();
                } else {
                    resultText = ContentBlockUtils.extractTextContent(output);
                }
                textAccumulator
                        .append(role)
                        .append(" ")
                        .append(name)
                        .append(" (")
                        .append(toolResult.getName())
                        .append("): ")
                        .append(resultText)
                        .append("\n");
            }
        }

        textAccumulator.append(HISTORY_END_TAG);
        if (textAccumulator.length() > 0) {
            contentBlocks.add(Map.of("text", textAccumulator.toString()));
        }

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put(
                "content", contentBlocks.isEmpty() ? List.of(Map.of("text", "")) : contentBlocks);
        return List.of(userMsg);
    }

    @Override
    protected Map<String, Object> formatSystemMessage(Msg msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "system");
        m.put("content", msg.getTextContent());
        return m;
    }

    @Override
    protected List<Map<String, Object>> formatToolSequence(List<Msg> msgs) {
        // Delegate to single-chat DashScope formatter behavior
        List<Map<String, Object>> out = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                out.add(formatAssistantToolCall(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                out.add(formatToolResult(msg));
            }
        }
        return out;
    }

    @Override
    protected List<Map<String, Object>> formatAgentMessage(List<Msg> msgs, boolean isFirst) {
        // Not used in multi-agent collapse path; kept for completeness
        return Collections.emptyList();
    }

    private Map<String, Object> formatAssistantToolCall(Msg msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "assistant");
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        contentBlocks.add(Map.of("text", ""));
        m.put("content", contentBlocks);

        ContentBlock content = msg.getContent();
        if (content instanceof ToolUseBlock toolUse) {
            Map<String, Object> call = new HashMap<>();
            call.put("id", toolUse.getId());
            call.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", toolUse.getName());
            function.put("arguments", toJson(toolUse.getInput()));
            call.put("function", function);
            m.put("tool_calls", List.of(call));
            log.debug(
                    "Formatted multi-agent tool call: id={}, name={}",
                    toolUse.getId(),
                    toolUse.getName());
        }
        return m;
    }

    private Map<String, Object> formatToolResult(Msg msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "tool");

        ContentBlock content = msg.getContent();
        if (content instanceof ToolResultBlock toolResult) {
            // Extract content from ToolResultBlock
            ContentBlock output = toolResult.getOutput();
            if (output instanceof TextBlock textBlock) {
                m.put("content", textBlock.getText());
            } else {
                m.put("content", ContentBlockUtils.extractTextContent(output));
            }
            // Use the actual tool call ID from ToolResultBlock
            m.put("tool_call_id", toolResult.getId());
        } else {
            // Fallback for non-ToolResultBlock content
            m.put("content", msg.getTextContent());
            // We do not track the originating tool_call_id in current message model.
            // Provide a best-effort placeholder to maintain structure.
            m.put("tool_call_id", "tool_call_" + System.currentTimeMillis());
        }
        return m;
    }

    private String normalizeMediaUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (!url.startsWith("http://")
                && !url.startsWith("https://")
                && !url.startsWith("file://")
                && !url.startsWith("data:")) {
            File f = new File(url);
            if (f.exists()) {
                return "file://" + f.getAbsolutePath();
            }
        }
        return url;
    }

    private String toJson(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : input.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
                }
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(true)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ImageBlock.class,
                                AudioBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class))
                .build();
    }
}
