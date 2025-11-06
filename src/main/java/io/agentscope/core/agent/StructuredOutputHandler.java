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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Handles structured output generation logic for ReActAgent.
 *
 * <p>This class encapsulates all structured output related functionality including:
 * <ul>
 *   <li>Temporary tool registration and cleanup
 *   <li>Memory checkpoint and rollback
 *   <li>Reminder message injection
 *   <li>Response validation and extraction
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 * <pre>
 * 1. create() - Create handler instance
 * 2. prepare() - Register tool, mark memory checkpoint
 * 3. [Loop execution with needsRetry/isCompleted checks]
 * 4. extractFinalResult() - Extract and cleanup
 * 5. cleanup() - Unregister tool
 * </pre>
 */
public class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    private final Class<?> targetClass;
    private final Toolkit toolkit;
    private final Memory memory;
    private final String agentName;

    // State management
    private int memoryStartIndex = -1;

    /**
     * Create a structured output handler.
     *
     * @param targetClass The target class for structured output
     * @param toolkit The toolkit for tool registration
     * @param memory The memory for checkpoint management
     * @param agentName The agent name for message creation
     */
    public StructuredOutputHandler(
            Class<?> targetClass, Toolkit toolkit, Memory memory, String agentName) {
        this.targetClass = targetClass;
        this.toolkit = toolkit;
        this.memory = memory;
        this.agentName = agentName;
    }

    // ==================== Lifecycle Methods ====================

    /**
     * Prepare for structured output execution.
     * Registers temporary tool and marks memory checkpoint.
     */
    public void prepare() {
        memoryStartIndex = memory.getMessages().size();
        log.debug("Structured output prepare: memory start index = {}", memoryStartIndex);

        Map<String, Object> jsonSchema = JsonSchemaUtils.generateSchemaFromClass(targetClass);
        AgentTool temporaryTool = createStructuredOutputTool(jsonSchema);
        toolkit.registerAgentTool(temporaryTool);
    }

    /**
     * Cleanup after structured output execution.
     * Unregisters temporary tool and resets state.
     */
    public void cleanup() {
        toolkit.removeTool("generate_response");
        memoryStartIndex = -1;
        log.debug("Structured output cleanup completed");
    }

    // ==================== Loop Interaction Methods ====================

    /**
     * Create GenerateOptions with forced tool choice for structured output.
     *
     * @param baseOptions Base generation options to merge with (may be null)
     * @return New GenerateOptions with toolChoice set to force generate_response
     */
    public GenerateOptions createOptionsWithForcedTool(GenerateOptions baseOptions) {
        return GenerateOptions.mergeOptions(
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        .build(),
                baseOptions);
    }

    /**
     * Check if structured output generation is completed.
     *
     * @return true if completed successfully
     */
    public boolean isCompleted() {
        return checkStructuredOutputResponse() != null;
    }

    /**
     * Extract and cleanup final result.
     *
     * @return Final message with structured data in metadata
     */
    public Msg extractFinalResult() {
        Msg rawResult = checkStructuredOutputResponse();
        if (rawResult == null) {
            throw new IllegalStateException(
                    "Structured output not found when extractFinalResult called");
        }

        Msg cleanedMsg = extractResponseData(rawResult);
        cleanupStructuredOutputHistory(cleanedMsg);

        return cleanedMsg;
    }

    // ==================== Private Helper Methods ====================

    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return "generate_response";
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");
                params.put("properties", Map.of("response", schema));
                params.put("required", List.of("response"));
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                return Mono.fromCallable(
                        () -> {
                            Object responseData = input.get("response");

                            if (targetClass != null && responseData != null) {
                                try {
                                    ObjectMapper mapper = new ObjectMapper();
                                    mapper.convertValue(responseData, targetClass);
                                } catch (Exception e) {
                                    String simplifiedError = simplifyValidationError(e);
                                    String errorMsg =
                                            String.format(
                                                    "Schema validation failed: %s\n\n"
                                                        + "Please review the expected structure and"
                                                        + " call 'generate_response' again with a"
                                                        + " correctly formatted response object.",
                                                    simplifiedError);

                                    Map<String, Object> errorMetadata = new HashMap<>();
                                    errorMetadata.put("success", false);
                                    errorMetadata.put("validation_error", simplifiedError);

                                    return ToolResultBlock.of(
                                            List.of(TextBlock.builder().text(errorMsg).build()),
                                            errorMetadata);
                                }
                            }

                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    ObjectMapper mapper = new ObjectMapper();
                                    contentText = mapper.writeValueAsString(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }

                            Msg responseMsg =
                                    Msg.builder()
                                            .name(agentName)
                                            .role(MsgRole.ASSISTANT)
                                            .content(TextBlock.builder().text(contentText).build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            Map<String, Object> toolMetadata = new HashMap<>();
                            toolMetadata.put("success", true);
                            toolMetadata.put("response_msg", responseMsg);

                            return ToolResultBlock.of(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Successfully generated response.")
                                                    .build()),
                                    toolMetadata);
                        });
            }
        };
    }

    private String simplifyValidationError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "Unable to parse response structure";
        }

        int newlineIndex = message.indexOf('\n');
        if (newlineIndex > 0) {
            message = message.substring(0, newlineIndex);
        }

        if (message.length() > 200) {
            message = message.substring(0, 197) + "...";
        }

        return message;
    }

    private Msg checkStructuredOutputResponse() {
        List<Msg> msgs = memory.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg.getRole() == MsgRole.TOOL) {
                List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
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
                break;
            }
        }
        return null;
    }

    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            return Msg.builder()
                    .name(responseMsg.getName())
                    .role(responseMsg.getRole())
                    .content(responseMsg.getContent())
                    .metadata(
                            responseData instanceof Map
                                    ? (Map<String, Object>) responseData
                                    : Map.of("data", responseData))
                    .build();
        }
        return responseMsg;
    }

    private void cleanupStructuredOutputHistory(Msg finalResponseMsg) {
        if (memoryStartIndex < 0) {
            log.warn("Memory start index not set, skipping cleanup");
            return;
        }

        List<Msg> currentMessages = memory.getMessages();
        int currentSize = currentMessages.size();

        if (memoryStartIndex >= currentSize) {
            log.warn(
                    "Invalid start index {} >= current size {}, skipping cleanup",
                    memoryStartIndex,
                    currentSize);
            return;
        }

        int messagesToRemove = currentSize - memoryStartIndex;
        log.debug(
                "Cleaning up structured output history: removing {} messages from index {}",
                messagesToRemove,
                memoryStartIndex);

        for (int i = 0; i < messagesToRemove; i++) {
            memory.deleteMessage(memoryStartIndex);
        }

        memory.addMessage(finalResponseMsg);

        log.debug(
                "Cleanup complete. Memory now has {} messages (was {})",
                memory.getMessages().size(),
                currentSize);
    }
}
