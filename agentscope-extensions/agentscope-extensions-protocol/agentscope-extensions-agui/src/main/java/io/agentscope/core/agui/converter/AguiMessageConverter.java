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
package io.agentscope.core.agui.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.agui.model.AudioInputContent;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.InputContentSource;
import io.agentscope.core.agui.model.InputContentUrlSource;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.agui.model.VideoInputContent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Converter between AG-UI messages and AgentScope messages.
 *
 * <p>This class handles the bidirectional conversion between the AG-UI protocol's
 * message format and AgentScope's internal message format.
 */
public class AguiMessageConverter {

    /** Interrupt metadata key shared with AgentLifecycleEventConverter. */
    private static final String INTERRUPT_METADATA_SOURCE = "source";

    /** Interrupt metadata value for Permission HITL pauses. */
    private static final String INTERRUPT_SOURCE_PERMISSION = "permission";

    /**
     * Creates a new AguiMessageConverter
     */
    public AguiMessageConverter() {}

    /**
     * Convert an AG-UI message to an AgentScope message.
     *
     * @param aguiMessage The AG-UI message to convert
     * @return The converted AgentScope message
     */
    public Msg toMsg(AguiMessage aguiMessage) {
        MsgRole role = convertRole(aguiMessage.getRole());
        List<ContentBlock> blocks = new ArrayList<>();

        // Handle content: plain text or structured blocks
        MessageContent content = aguiMessage.getContent();
        if (content instanceof MessageContent.Text text) {
            addTextBlock(blocks, text.value(), aguiMessage);
        } else if (content instanceof MessageContent.Blocks blocksContent) {
            if (!aguiMessage.isUserMessage()) {
                throw new IllegalArgumentException(
                        "Structured content blocks are only supported for AG-UI user messages");
            }
            for (InputContent input : blocksContent.parts()) {
                blocks.add(toContentBlock(input));
            }
        }

        // Add tool calls if present (for assistant messages)
        if (aguiMessage.hasToolCalls()) {
            for (AguiToolCall tc : aguiMessage.getToolCalls()) {
                blocks.add(toToolUseBlock(tc));
            }
        }

        return Msg.builder().id(aguiMessage.getId()).role(role).content(blocks).build();
    }

    /**
     * Convert an AgentScope message to an AG-UI message.
     *
     * @param msg The AgentScope message to convert
     * @return The converted AG-UI message
     */
    public AguiMessage toAguiMessage(Msg msg) {
        String role = convertRole(msg.getRole());
        StringBuilder content = new StringBuilder();
        List<AguiToolCall> toolCalls = new ArrayList<>();
        String toolCallId = null;

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                if (content.length() > 0) {
                    content.append("\n");
                }
                content.append(tb.getText());
            } else if (block instanceof ToolUseBlock tub) {
                toolCalls.add(toAguiToolCall(tub));
            } else if (block instanceof ToolResultBlock trb) {
                toolCallId = trb.getId();
                // Extract text content from tool result
                for (ContentBlock output : trb.getOutput()) {
                    if (output instanceof TextBlock tb) {
                        if (content.length() > 0) {
                            content.append("\n");
                        }
                        content.append(tb.getText());
                    }
                }
            }
        }

        return new AguiMessage(
                msg.getId(),
                role,
                content.length() > 0 ? new MessageContent.Text(content.toString()) : null,
                toolCalls.isEmpty() ? null : toolCalls,
                toolCallId);
    }

    /**
     * Convert a list of AG-UI messages to AgentScope messages.
     *
     * @param aguiMessages The AG-UI messages to convert
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(List<AguiMessage> aguiMessages) {
        return aguiMessages.stream().map(this::toMsg).collect(Collectors.toList());
    }

    /**
     * Convert an AG-UI run input to AgentScope messages, including official resume entries.
     *
     * @param input The AG-UI run input
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(RunAgentInput input) {
        return toMsgList(input, Map.of(), Map.of());
    }

    /**
     * Convert an AG-UI run input to AgentScope messages, resolving resume entries through known
     * interrupt-to-tool-call mappings when available.
     *
     * @param input The AG-UI run input
     * @param resumeToolCallIds Mapping from interrupt ID to tool call ID
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(RunAgentInput input, Map<String, String> resumeToolCallIds) {
        return toMsgList(input, resumeToolCallIds, Map.of());
    }

    /**
     * Convert an AG-UI run input to AgentScope messages, resolving resume entries through known
     * interrupt metadata.
     *
     * <p>Permission HITL resumes become {@link ConfirmResult} metadata. Backend SchemaOnly /
     * tool-suspended resumes become {@link ToolResultBlock} tool messages, unless the client
     * already supplied a matching tool-result message (frontend HITL).
     *
     * @param input The AG-UI run input
     * @param resumeToolCallIds Mapping from interrupt ID to tool call ID
     * @param resumeInterrupts Mapping from interrupt ID to the open AG-UI interrupt
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(
            RunAgentInput input,
            Map<String, String> resumeToolCallIds,
            Map<String, AguiEvent.Interrupt> resumeInterrupts) {
        Objects.requireNonNull(input, "input cannot be null");
        List<Msg> msgs = new ArrayList<>(toMsgList(input.getMessages()));
        List<ConfirmResult> confirmResults = new ArrayList<>();
        List<ContentBlock> confirmContent = new ArrayList<>();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt =
                    resumeInterrupts != null ? resumeInterrupts.get(resume.getInterruptId()) : null;
            String toolCallId =
                    interrupt != null && interrupt.toolCallId() != null
                            ? interrupt.toolCallId()
                            : resolveToolCallId(resume.getInterruptId(), resumeToolCallIds);
            if (toolCallId == null || toolCallId.isBlank()) {
                continue;
            }
            if (isPermissionInterrupt(interrupt)) {
                confirmResults.add(toConfirmResult(resume, toolCallId, interrupt));
                // resume[] may contain multiple HITL decisions; serialize each payload into
                // content so approved/reason (and any extra fields) are preserved for context.
                String payloadText = resumeContent(resume);
                if (payloadText != null && !payloadText.isBlank()) {
                    confirmContent.add(TextBlock.builder().text(payloadText).build());
                }
            } else if (!hasToolResult(msgs, toolCallId)) {
                // Frontend HITL / SchemaOnly tools already return results as tool messages;
                // do not synthesize a second ToolResultBlock from the resume entry.
                msgs.add(toToolResultMsg(resume, toolCallId));
            }
        }
        if (!confirmResults.isEmpty()) {
            msgs.add(
                    Msg.builder()
                            .id("agui-resume-confirm")
                            .role(MsgRole.USER)
                            .content(List.copyOf(confirmContent))
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.copyOf(confirmResults)))
                            .build());
        }
        return List.copyOf(msgs);
    }

    /**
     * Convert a list of AgentScope messages to AG-UI messages.
     *
     * @param msgs The AgentScope messages to convert
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessageList(List<Msg> msgs) {
        return msgs.stream().map(this::toAguiMessage).collect(Collectors.toList());
    }

    /**
     * Convert an AG-UI role string to an AgentScope MsgRole.
     *
     * @param role The AG-UI role string
     * @return The corresponding MsgRole
     */
    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }

    /**
     * Convert an AgentScope MsgRole to an AG-UI role string.
     *
     * @param role The AgentScope MsgRole
     * @return The corresponding role string
     */
    private String convertRole(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    /**
     * Add a text content block for the given text, wrapping in a ToolResultBlock for tool messages.
     *
     * @param blocks the target block list
     * @param text the text content
     * @param aguiMessage the source message (for role/tool-call-id context)
     */
    private void addTextBlock(List<ContentBlock> blocks, String text, AguiMessage aguiMessage) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
            blocks.add(
                    ToolResultBlock.of(
                            aguiMessage.getToolCallId(),
                            null,
                            TextBlock.builder().text(text).build()));
        } else {
            blocks.add(TextBlock.builder().text(text).build());
        }
    }

    /**
     * Convert an AG-UI {@link InputContent} to an AgentScope {@link ContentBlock}.
     *
     * <p>Uses instanceof pattern matching over the sealed {@link InputContent} hierarchy.
     * When a new InputContent subtype is added, the final {@code throw} provides a
     * runtime safety net. On Java 21+, this can be upgraded to exhaustive switch
     * pattern matching for compile-time enforcement.
     *
     * @param input the AG-UI input content part
     * @return the converted content block
     */
    private ContentBlock toContentBlock(InputContent input) {
        if (input instanceof TextInputContent text) {
            return TextBlock.builder().text(text.text()).build();
        }
        if (input instanceof ImageInputContent image) {
            return ImageBlock.builder().source(toSource(image.source())).build();
        }
        if (input instanceof AudioInputContent audio) {
            return AudioBlock.builder().source(toSource(audio.source())).build();
        }
        if (input instanceof VideoInputContent video) {
            return VideoBlock.builder().source(toSource(video.source())).build();
        }
        //        if (input instanceof DocumentInputContent doc) {
        //            // AgentScope currently lacks a native DocumentBlock; falling back to
        // DataBlock
        //            return DataBlock.builder().source(toSource(doc.source())).build();
        //        }
        throw new IllegalStateException("Unhandled InputContent type: " + input);
    }

    /**
     * Convert an AG-UI protocol {@link InputContentSource} to an AgentScope {@link Source}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@link InputContentUrlSource} (type:"url", value) &rarr; {@link URLSource} (url)</li>
     *   <li>{@link InputContentDataSource} (type:"data", value) &rarr; {@link Base64Source} (data)</li>
     * </ul>
     *
     * @param inputSource the AG-UI protocol source
     * @return the AgentScope internal source
     */
    private Source toSource(InputContentSource inputSource) {
        if (inputSource instanceof InputContentUrlSource url) {
            return new URLSource(url.value(), url.mimeType());
        }
        if (inputSource instanceof InputContentDataSource data) {
            return new Base64Source(data.mimeType(), data.value());
        }
        throw new IllegalStateException("Unhandled InputContentSource type: " + inputSource);
    }

    /**
     * Convert an AG-UI tool call to an AgentScope ToolUseBlock.
     *
     * @param tc The AG-UI tool call
     * @return The converted ToolUseBlock
     */
    private ToolUseBlock toToolUseBlock(AguiToolCall tc) {
        Map<String, Object> input = parseJsonArguments(tc.getFunction().getArguments());
        return ToolUseBlock.builder()
                .id(tc.getId())
                .name(tc.getFunction().getName())
                .input(input)
                .build();
    }

    /**
     * Convert an AgentScope ToolUseBlock to an AG-UI tool call.
     *
     * @param tub The AgentScope ToolUseBlock
     * @return The converted AG-UI tool call
     */
    private AguiToolCall toAguiToolCall(ToolUseBlock tub) {
        String arguments = serializeArguments(tub.getInput());
        AguiFunctionCall function = new AguiFunctionCall(tub.getName(), arguments);
        return new AguiToolCall(tub.getId(), function);
    }

    /**
     * Parse JSON arguments string to a Map.
     *
     * @param arguments The JSON arguments string
     * @return The parsed Map
     */
    private Map<String, Object> parseJsonArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        try {
            return JsonUtils.getJsonCodec()
                    .fromJson(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonException e) {
            return Map.of();
        }
    }

    private Msg toToolResultMsg(AguiResume resume, String toolCallId) {
        ToolResultState state =
                resume.isCancelled() || !isApproved(resume)
                        ? ToolResultState.INTERRUPTED
                        : ToolResultState.SUCCESS;
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id(toolCallId)
                        .output(TextBlock.builder().text(resumeContent(resume)).build())
                        .metadata(
                                Map.of(
                                        "agui.interruptId", resume.getInterruptId(),
                                        "agui.resumeStatus", resume.getStatus()))
                        .state(state)
                        .build();
        return Msg.builder()
                .id("agui-resume-" + resume.getInterruptId())
                .role(MsgRole.TOOL)
                .content(result)
                .build();
    }

    private String resumeContent(AguiResume resume) {
        if (resume.isCancelled()) {
            return "Interrupt cancelled by user";
        }
        // Keep the client payload as-is (including approved:false + reason). isApproved() only
        // affects ToolResultState / ConfirmResult, not the serialized tool output text.
        Object payload = resume.getPayload();
        if (payload == null) {
            return "";
        }
        if (payload instanceof String text) {
            return text;
        }
        try {
            return JsonUtils.getJsonCodec().toJson(payload);
        } catch (JsonException e) {
            return String.valueOf(payload);
        }
    }

    private static boolean hasToolResult(List<Msg> msgs, String toolCallId) {
        for (Msg msg : msgs) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock trb && toolCallId.equals(trb.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPermissionInterrupt(AguiEvent.Interrupt interrupt) {
        if (interrupt == null || interrupt.metadata() == null) {
            return false;
        }
        return INTERRUPT_SOURCE_PERMISSION.equals(
                interrupt.metadata().get(INTERRUPT_METADATA_SOURCE));
    }

    private ConfirmResult toConfirmResult(
            AguiResume resume, String toolCallId, AguiEvent.Interrupt interrupt) {
        Map<String, Object> metadata = interrupt != null ? interrupt.metadata() : null;
        String toolName = metadataString(metadata, "toolName");
        Map<String, Object> toolInput = metadataMap(metadata, "toolInput");

        boolean approved = isApproved(resume);

        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id(toolCallId)
                        .name(toolName != null ? toolName : "")
                        .input(toolInput)
                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                        .build();
        return new ConfirmResult(approved, toolCall);
    }

    private static boolean isApproved(AguiResume resume) {
        if (resume.isCancelled()) {
            return false;
        }
        Object payload = resume.getPayload();
        if (payload instanceof Map<?, ?> map && map.containsKey("approved")) {
            Object approved = map.get("approved");
            if (approved instanceof Boolean bool) {
                return bool;
            }
            if (approved != null) {
                return Boolean.parseBoolean(String.valueOf(approved));
            }
        }
        // resolved without an explicit denial counts as approved
        return resume.isResolved();
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return value != null ? String.valueOf(value) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataMap(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String mapKey) {
                result.put(mapKey, entry.getValue());
            }
        }
        return result;
    }

    private String resolveToolCallId(String interruptId, Map<String, String> resumeToolCallIds) {
        if (interruptId == null || interruptId.isBlank()) {
            return null;
        }
        if (resumeToolCallIds != null) {
            String mapped = resumeToolCallIds.get(interruptId);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        int separator = interruptId.lastIndexOf(':');
        if (separator >= 0 && separator < interruptId.length() - 1) {
            return interruptId.substring(separator + 1);
        }
        return interruptId;
    }

    /**
     * Serialize arguments Map to JSON string.
     *
     * @param arguments The arguments Map
     * @return The JSON string
     */
    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(arguments);
        } catch (JsonException e) {
            return "{}";
        }
    }
}
