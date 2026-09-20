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

import static io.agentscope.core.agui.AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_CONTENT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_INPUT;
import static io.agentscope.core.agui.AguiInterruptConstants.METADATA_TOOL_NAME;
import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

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
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.agui.model.VideoInputContent;
import io.agentscope.core.event.ConfirmResult;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converter between AG-UI messages and AgentScope messages.
 *
 * <p>This class handles the bidirectional conversion between the AG-UI protocol's
 * message format and AgentScope's internal message format.
 */
public class AguiMessageConverter {

    /** AG-UI resume payload key: user approved the tool call. */
    private static final String RESUME_PAYLOAD_APPROVED = "approved";

    /** AG-UI resume payload key: full replacement tool arguments. */
    private static final String RESUME_PAYLOAD_EDITED_ARGS = "editedArgs";

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
        } else if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
            // Tool message with no content (e.g. frontend tool returning nothing): still
            // emit a ToolResultBlock so the pending tool call is resolved downstream.
            addTextBlock(blocks, "", aguiMessage);
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
        return toMsgList(input, Map.of());
    }

    /**
     * Convert an AG-UI run input to AgentScope messages, resolving resume entries through known
     * originating interrupts when available.
     *
     * <p>Some clients (notably CopilotKit {@code useInterrupt.resolve()}) send both a {@code
     * role:"tool"} message and a matching {@code resume[]} entry for the same {@code toolCallId}.
     * Ordinary tool resumes that would duplicate an already-present {@link ToolResultBlock} id are
     * skipped so {@code ReActAgent} does not throw {@code Duplicate tool result ID}. Permission
     * confirm resumes still produce a USER {@link ConfirmResult} message and are never skipped on
     * that basis.
     *
     * @param input The AG-UI run input
     * @param resumeInterrupts Mapping from interrupt ID to the originating interrupt
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(
            RunAgentInput input, Map<String, AguiEvent.Interrupt> resumeInterrupts) {
        Objects.requireNonNull(input, "input cannot be null");
        List<Msg> msgs = new ArrayList<>(toMsgList(input.getMessages()));
        Set<String> existingToolResultIds = collectToolResultIds(msgs);
        Map<String, AguiEvent.Interrupt> interrupts =
                resumeInterrupts != null ? resumeInterrupts : Map.of();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt = interrupts.get(resume.getInterruptId());
            String toolCallId = resolveToolCallId(resume.getInterruptId(), interrupt);
            if (toolCallId == null || toolCallId.isBlank()) {
                continue;
            }
            if (isPermissionConfirmInterrupt(interrupt)) {
                msgs.add(toConfirmResultMsg(resume, toolCallId, interrupt));
            } else if (!existingToolResultIds.contains(toolCallId)) {
                msgs.add(toToolResultMsg(resume, toolCallId));
                existingToolResultIds.add(toolCallId);
            }
        }
        return List.copyOf(msgs);
    }

    private static Set<String> collectToolResultIds(List<Msg> msgs) {
        Set<String> ids = new HashSet<>();
        for (Msg msg : msgs) {
            if (msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult
                        && toolResult.getId() != null
                        && !toolResult.getId().isBlank()) {
                    ids.add(toolResult.getId());
                }
            }
        }
        return ids;
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
        if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
            // Tool results must always carry a ToolResultBlock, even when the frontend
            // returned empty content.
            String resultText = text != null ? text : "";
            blocks.add(
                    ToolResultBlock.builder()
                            .id(aguiMessage.getToolCallId())
                            .output(TextBlock.builder().text(resultText).build())
                            .state(ToolResultState.SUCCESS)
                            .build());
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        blocks.add(TextBlock.builder().text(text).build());
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
                resume.isCancelled() ? ToolResultState.INTERRUPTED : ToolResultState.SUCCESS;
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

    /**
     * Build a {@link Msg} carrying a {@link ConfirmResult} for a permission-mode tool confirmation
     * resume.
     *
     * <p>The resulting Msg is a USER-role message whose metadata contains a single-entry {@code
     * List<ConfirmResult>} under {@link Msg#METADATA_CONFIRM_RESULTS}. The {@code ToolUseBlock}
     * inside the {@link ConfirmResult} is reconstructed from the originating interrupt metadata so
     * that its {@code content} field (the tool arguments as a JSON-object string) is guaranteed
     * non-null, avoiding the {@code "argument content is null"} bug in
     * {@code ReActAgent.applyConfirmResults}.
     *
     * @param resume the AG-UI resume entry
     * @param toolCallId the resolved tool call ID
     * @param interrupt the originating interrupt containing tool metadata
     * @return a USER-role Msg with the confirmation result
     */
    @SuppressWarnings("unchecked")
    private Msg toConfirmResultMsg(
            AguiResume resume, String toolCallId, AguiEvent.Interrupt interrupt) {
        boolean approved = isApproved(resume);
        Map<String, Object> metadata = interrupt.metadata();

        String toolName = metadata != null ? stringValue(metadata.get(METADATA_TOOL_NAME)) : null;
        Map<String, Object> toolInput = null;
        String toolContent = null;
        if (metadata != null) {
            Object inputObj = metadata.get(METADATA_TOOL_INPUT);
            if (inputObj instanceof Map) {
                toolInput = toStringObjectMap(inputObj, METADATA_TOOL_INPUT);
            }
            toolContent = stringValue(metadata.get(METADATA_TOOL_CONTENT));
        }
        Map<String, Object> editedArgs = editedArgs(resume);
        if (editedArgs != null) {
            toolInput = editedArgs;
            toolContent = serializeArguments(editedArgs);
        }

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id(toolCallId)
                        .name(toolName)
                        .input(toolInput)
                        .content(toolContent)
                        .build();

        ConfirmResult confirmResult = new ConfirmResult(approved, toolUseBlock);
        return Msg.builder()
                .id("agui-confirm-" + resume.getInterruptId())
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(confirmResult)))
                .build();
    }

    /**
     * Determine whether the user approved the tool.
     *
     * <p>If the status is {@code cancelled}, the tool is denied. Otherwise, only an explicit
     * {@code approved: true} boolean field approves the tool.
     */
    @SuppressWarnings("unchecked")
    private static boolean isApproved(AguiResume resume) {
        if (resume.isCancelled()) {
            return false;
        }
        Object payload = resume.getPayload();
        if (payload instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get(RESUME_PAYLOAD_APPROVED));
        }
        return false;
    }

    private static Map<String, Object> editedArgs(AguiResume resume) {
        Object payload = resume.getPayload();
        if (!(payload instanceof Map<?, ?> map) || !map.containsKey(RESUME_PAYLOAD_EDITED_ARGS)) {
            return null;
        }
        return toStringObjectMap(map.get(RESUME_PAYLOAD_EDITED_ARGS), RESUME_PAYLOAD_EDITED_ARGS);
    }

    private static Map<String, Object> toStringObjectMap(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object when present");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        fieldName + " must contain only string property names");
            }
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean isPermissionConfirmInterrupt(AguiEvent.Interrupt interrupt) {
        if (interrupt == null || !TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return false;
        }
        Map<String, Object> metadata = interrupt.metadata();
        return metadata != null
                && INTERRUPT_KIND_PERMISSION_CONFIRM.equals(
                        metadata.get(METADATA_AGENTSCOPE_INTERRUPT_KIND));
    }

    private String resumeContent(AguiResume resume) {
        if (resume.isCancelled()) {
            return "Interrupt cancelled by user";
        }
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

    private String resolveToolCallId(String interruptId, AguiEvent.Interrupt interrupt) {
        if (interrupt != null
                && interrupt.toolCallId() != null
                && !interrupt.toolCallId().isBlank()) {
            return interrupt.toolCallId();
        }
        if (interrupt != null && !TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return null;
        }
        if (interruptId == null || interruptId.isBlank()) {
            return null;
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
