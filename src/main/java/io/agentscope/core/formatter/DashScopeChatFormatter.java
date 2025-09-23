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
import io.agentscope.core.message.VideoBlock;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formatter for DashScope Conversation/Generation APIs.
 *
 * This formatter converts AgentScope Msg objects to the format required by
 * DashScope APIs, following the Python DashScopeChatFormatter behavior:
 * - Multimodal content is represented as a list of blocks under "content"
 * - Tool calls are included via "tool_calls" on assistant messages
 * - Tool results are separate messages with role "tool"
 */
public class DashScopeChatFormatter extends TruncatedFormatterBase {

    public DashScopeChatFormatter() {
        super();
    }

    public DashScopeChatFormatter(TokenCounter tokenCounter, Integer maxTokens) {
        super(tokenCounter, maxTokens);
    }

    @Override
    protected List<Map<String, Object>> formatInternal(List<Msg> msgs, FormatterOptions options) {
        List<Map<String, Object>> formatted = new ArrayList<>();

        for (Msg msg : msgs) {
            switch (msg.getRole()) {
                case SYSTEM -> formatted.add(formatSystemMessage(msg));
                case USER, ASSISTANT -> formatted.add(formatAgentMsg(msg));
                case TOOL -> formatted.add(formatToolResultMsg(msg));
            }
        }

        // Post-process: If a message's content is a list and all items are text,
        // collapse into a single string (helps token counters and some providers)
        for (Map<String, Object> m : formatted) {
            Object contentObj = m.get("content");
            if (contentObj instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;
                boolean allText = true;
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> item : contentList) {
                    Object text = item.get("text");
                    Object type = item.get("type");
                    if (!(text instanceof String) || (type != null && !"text".equals(type))) {
                        allText = false;
                        break;
                    }
                    if (sb.length() > 0) sb.append("\n");
                    sb.append((String) text);
                }
                if (allText && sb.length() > 0) {
                    m.put("content", sb.toString());
                }
            }
        }

        return formatted;
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
        // For DashScope, tool calls/results are separate messages in sequence
        List<Map<String, Object>> out = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                out.add(formatAgentMsg(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                out.add(formatToolResultMsg(msg));
            }
        }
        return out;
    }

    @Override
    protected List<Map<String, Object>> formatAgentMessage(List<Msg> msgs, boolean isFirst) {
        // Combine adjacent agent messages into multiple DashScope messages
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAgentMsg(msg));
            }
        }
        return result;
    }

    private Map<String, Object> formatAgentMsg(Msg msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", msg.getRole().name().toLowerCase());

        ContentBlock content = msg.getContent();
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        if (content instanceof TextBlock textBlock) {
            contentBlocks.add(Map.of("text", textBlock.getText()));
        } else if (content instanceof ThinkingBlock thinkingBlock) {
            // Thinking content is placed as reasoning content for models that support it
            contentBlocks.add(Map.of("text", thinkingBlock.getThinking()));
        } else if (content instanceof ImageBlock
                || content instanceof AudioBlock
                || content instanceof VideoBlock) {
            MediaInfo mediaInfo = ContentBlockUtils.getMediaInfo(content);
            if (mediaInfo != null) {
                String key =
                        switch (content.getType()) {
                            case IMAGE -> "image";
                            case AUDIO -> "audio";
                            case VIDEO -> "video";
                            default -> "unknown";
                        };
                if (!"unknown".equals(key)) {
                    contentBlocks.add(Map.of(key, normalizeMediaUrl(mediaInfo.getData())));
                }
            }
        } else if (content instanceof ToolUseBlock toolUse) {
            Map<String, Object> call = new HashMap<>();
            call.put("id", toolUse.getId());
            call.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", toolUse.getName());
            function.put("arguments", toJson(toolUse.getInput()));
            call.put("function", function);
            toolCalls.add(call);
            // DashScope requires content to be present; use placeholder
            contentBlocks.add(Map.of("text", ""));
        } else if (content instanceof ToolResultBlock toolResult) {
            // ToolResultBlock should be handled as tool role message, not agent message
            // This should not happen in formatAgentMsg, but handle gracefully
            ContentBlock output = toolResult.getOutput();
            if (output instanceof TextBlock textBlock) {
                contentBlocks.add(Map.of("text", textBlock.getText()));
            } else {
                contentBlocks.add(Map.of("text", ContentBlockUtils.extractTextContent(output)));
            }
        } else if (content != null) {
            // Fallback to text representation
            contentBlocks.add(Map.of("text", ContentBlockUtils.toTextRepresentation(content)));
        } else {
            contentBlocks.add(Map.of("text", ""));
        }

        m.put("content", contentBlocks);
        if (!toolCalls.isEmpty()) {
            m.put("tool_calls", toolCalls);
        }
        return m;
    }

    private Map<String, Object> formatToolResultMsg(Msg msg) {
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
        // If it's a local path, convert to file:// URL
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
                .supportMultiAgent(false)
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
