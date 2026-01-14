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
package io.agentscope.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook for handling structured output generation using the HITL mechanism.
 *
 * <p>This hook intercepts agent events to ensure the model calls the {@code generate_response}
 * tool for structured output generation:
 *
 * <ul>
 *   <li><b>PreReasoning:</b> In TOOL_CHOICE mode, forces tool_choice to generate_response
 *   <li><b>PostReasoning:</b> Checks if generate_response was called; if not, triggers retry
 *       with a reminder message
 *   <li><b>PostActing:</b> Stops the agent when generate_response completes successfully
 *   <li><b>PostCall:</b> Compresses memory by removing intermediate structured output messages
 * </ul>
 *
 * @hidden
 */
public class StructuredOutputHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHook.class);

    /** The tool name for structured output generation. */
    public static final String TOOL_NAME = "generate_response";

    private static final int MAX_RETRIES = 3;

    private final StructuredOutputReminder reminderMode;
    private final GenerateOptions baseOptions;
    private final Memory memory;

    private boolean completed = false;
    private Msg resultMsg = null;
    private int retryCount = 0;
    private ChatUsage aggregatedUsage = null;

    /**
     * Creates a new StructuredOutputHook.
     *
     * @param reminderMode The reminder mode (TOOL_CHOICE or PROMPT)
     * @param baseOptions The base generation options
     * @param memory The memory for compression in PostCall
     */
    public StructuredOutputHook(
            StructuredOutputReminder reminderMode, GenerateOptions baseOptions, Memory memory) {
        this.reminderMode = reminderMode;
        this.baseOptions = baseOptions;
        this.memory = memory;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            handlePreReasoning(e);
        } else if (event instanceof PostReasoningEvent e) {
            handlePostReasoning(e);
        } else if (event instanceof PostActingEvent e) {
            handlePostActing(e);
        } else if (event instanceof PostCallEvent e) {
            handlePostCall(e);
        }
        return Mono.just(event);
    }

    private void handlePreReasoning(PreReasoningEvent event) {
        // In TOOL_CHOICE mode, only force tool_choice when processing a TOOL_CHOICE reminder
        // message
        if (reminderMode == StructuredOutputReminder.TOOL_CHOICE) {
            List<Msg> inputMessages = event.getInputMessages();
            if (inputMessages == null || inputMessages.isEmpty()) {
                return;
            }
            Msg lastMsg = inputMessages.get(inputMessages.size() - 1);
            if (lastMsg != null && isToolChoiceReminderMessage(lastMsg)) {
                GenerateOptions options =
                        GenerateOptions.mergeOptions(
                                GenerateOptions.builder()
                                        .toolChoice(new ToolChoice.Specific(TOOL_NAME))
                                        .build(),
                                baseOptions);
                event.setGenerateOptions(options);
                log.debug("Set tool_choice to force generate_response on retry");
            }
        }
    }

    private boolean isToolChoiceReminderMessage(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata == null) {
            return false;
        }
        return StructuredOutputReminder.TOOL_CHOICE
                .toString()
                .equals(metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE));
    }

    private void handlePostReasoning(PostReasoningEvent event) {
        Msg msg = event.getReasoningMessage();
        if (msg == null) {
            return;
        }

        boolean hasCall = !msg.getContentBlocks(ToolUseBlock.class).isEmpty();

        if (!hasCall && retryCount < MAX_RETRIES) {
            retryCount++;
            log.debug(
                    "Model didn't call any tool, requesting retry ({}/{})",
                    retryCount,
                    MAX_RETRIES);

            // Add reminder message and goto reasoning
            event.gotoReasoning(createReminderMessage(reminderMode));
        }
        // If max retries exceeded, let it continue to summarizing which will report error
    }

    private void handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse != null && TOOL_NAME.equals(toolUse.getName())) {
            ToolResultBlock result = event.getToolResult();
            if (result != null
                    && result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))) {
                completed = true;
                resultMsg = event.getToolResultMsg();

                // Collect usage now, before memory compression (which happens in PostCall)
                List<Msg> messages = new ArrayList<>(memory.getMessages());
                aggregatedUsage = collectChatUsage(messages);

                log.debug("generate_response completed successfully, stopping agent");
                event.stopAgent();
            }
        }
    }

    private void handlePostCall(PostCallEvent event) {
        if (!completed) {
            return;
        }
        // Compress memory: remove generate_response related intermediate messages
        compressMemory();
    }

    /**
     * Remove structured output related messages from memory and add final response.
     */
    private void compressMemory() {
        List<Msg> original = new ArrayList<>(memory.getMessages());
        int originalSize = original.size();

        memory.clear();
        for (Msg msg : original) {
            if (!isStructuredOutputRelated(msg)) {
                memory.addMessage(msg);
            }
        }

        // Add the final response message (extracted from resultMsg)
        if (resultMsg != null) {
            Msg finalMsg = extractFinalResponseMsg(resultMsg);
            if (finalMsg != null) {
                // Merge collected usage into final message (usage was collected in
                // handlePostActing)
                if (aggregatedUsage != null) {
                    finalMsg = mergeChatUsage(finalMsg, aggregatedUsage);
                }
                memory.addMessage(finalMsg);
            }
        }

        log.debug(
                "Memory compressed: {} -> {} messages", originalSize, memory.getMessages().size());
    }

    /**
     * Collect and aggregate ChatUsage from assistant messages that are being removed.
     */
    private ChatUsage collectChatUsage(List<Msg> messages) {
        int totalInput = 0;
        int totalOutput = 0;
        double totalTime = 0;
        boolean hasUsage = false;

        for (Msg msg : messages) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ChatUsage usage = msg.getChatUsage();
                if (usage != null) {
                    hasUsage = true;
                    totalInput += usage.getInputTokens();
                    totalOutput += usage.getOutputTokens();
                    totalTime += usage.getTime();
                }
            }
        }

        return hasUsage
                ? ChatUsage.builder()
                        .inputTokens(totalInput)
                        .outputTokens(totalOutput)
                        .time(totalTime)
                        .build()
                : null;
    }

    /**
     * Create a new message with the given ChatUsage merged into its metadata.
     */
    private Msg mergeChatUsage(Msg msg, ChatUsage chatUsage) {
        Map<String, Object> metadata =
                new HashMap<>(msg.getMetadata() != null ? msg.getMetadata() : Map.of());
        metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);

        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(msg.getContent())
                .metadata(metadata)
                .timestamp(msg.getTimestamp())
                .build();
    }

    /**
     * Extract the final response message from the tool result message.
     *
     * @param toolResultMsg The tool result message containing the response
     * @return The final response message, or null if not found
     */
    private Msg extractFinalResponseMsg(Msg toolResultMsg) {
        List<ToolResultBlock> toolResults = toolResultMsg.getContentBlocks(ToolResultBlock.class);
        for (ToolResultBlock result : toolResults) {
            if (result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))
                    && result.getMetadata().containsKey("response_msg")) {
                Object responseMsgObj = result.getMetadata().get("response_msg");
                if (responseMsgObj instanceof Msg responseMsg) {
                    return responseMsg;
                }
            }
        }
        return null;
    }

    /**
     * Check if a message is related to structured output and should be removed.
     */
    private boolean isStructuredOutputRelated(Msg msg) {
        // Reminder messages are marked with metadata
        if (hasReminderMetadata(msg)) {
            return true;
        }

        // ToolUse/ToolResult match by tool name
        return hasGenerateResponseTool(msg);
    }

    private boolean hasReminderMetadata(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        return metadata != null
                && Boolean.TRUE.equals(
                        metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER));
    }

    private boolean hasGenerateResponseTool(Msg msg) {
        // Check ToolUse
        if (msg.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(tu -> TOOL_NAME.equals(tu.getName()))) {
            return true;
        }

        // Check ToolResult (all must match to avoid removing mixed results)
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        return !results.isEmpty()
                && results.stream().allMatch(tr -> TOOL_NAME.equals(tr.getName()));
    }

    /**
     * Creates a reminder message to prompt the model to call generate_response.
     *
     * <p>The message includes metadata to identify it as a reminder and store the
     * reminder mode, which is used by {@link #handlePreReasoning} to determine
     * whether to force tool_choice on retry.
     *
     * @param mode The structured output reminder mode
     * @return A reminder message with appropriate metadata
     */
    private Msg createReminderMessage(StructuredOutputReminder mode) {
        Map<String, Object> metadata =
                Map.of(
                        MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER,
                        true,
                        MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE,
                        mode.toString());

        return Msg.builder()
                .name("system")
                .role(MsgRole.USER)
                .content(
                        TextBlock.builder()
                                .text(
                                        "Please call the 'generate_response' function to provide"
                                                + " your response.")
                                .build())
                .metadata(metadata)
                .build();
    }

    /**
     * Check if structured output generation is completed.
     *
     * @return true if completed successfully
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Get the result message from generate_response.
     *
     * @return The result message, or null if not completed
     */
    public Msg getResultMsg() {
        return resultMsg;
    }

    /**
     * Get the aggregated ChatUsage from all reasoning rounds.
     *
     * @return The aggregated ChatUsage, or null if no usage was collected
     */
    public ChatUsage getAggregatedUsage() {
        return aggregatedUsage;
    }

    @Override
    public int priority() {
        // High priority to execute before other hooks
        return 50;
    }
}
