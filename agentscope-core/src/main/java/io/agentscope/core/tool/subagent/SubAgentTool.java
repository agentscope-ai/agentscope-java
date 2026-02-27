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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AgentTool implementation that wraps a sub-agent for multi-turn conversation.
 *
 * <p>This tool allows an agent to be called as a tool by other agents, supporting multi-turn
 * conversation with session management. Each session maintains its own agent instance and state.
 *
 * <p>Thread safety is ensured by using {@link SubAgentProvider} to create a fresh agent instance
 * for each new session.
 *
 * <p>The tool exposes the following parameters:
 *
 * <ul>
 *   <li>{@code session_id} - Optional. Omit to start a new session, provide to continue an
 *       existing one.
 *   <li>{@code message} - Required. The message to send to the agent.
 * </ul>
 *
 * <p><b>Context Sharing Modes:</b>
 *
 * <ul>
 *   <li><b>SHARED (default):</b> Sub-agent receives a forked copy of parent's memory with pending
 *       tool calls removed. Provides context visibility while avoiding validation issues. Changes
 *       don't affect parent's memory. Sub-agent uses parent's system prompt context.
 *   <li><b>FORK:</b> Sub-agent gets a forked copy of parent's memory at invocation time, with
 *       pending tool calls removed. Changes don't affect parent's memory. Sub-agent uses parent's
 *       system prompt context.
 *   <li><b>NEW:</b> Sub-agent has completely independent memory with its own system prompt. No
 *       context from parent.
 * </ul>
 *
 * <p><b>Note:</b> Both SHARED and FORK modes fork the parent's memory because the parent's memory
 * contains the pending tool_use block that invoked the sub-agent, which would cause validation
 * errors if shared directly.
 */
public class SubAgentTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);

    /** Parameter name for session ID. */
    private static final String PARAM_SESSION_ID = "session_id";

    /** Parameter name for message. */
    private static final String PARAM_MESSAGE = "message";

    /**
     * Context key for parent session ID.
     *
     * <p>Applications can register the parent session ID in ToolExecutionContext to enable session
     * inheritance for SHARED/FORK modes. The sub-agent will derive its session ID from the parent
     * session.
     *
     * <p>Example: context.register(CONTEXT_KEY_PARENT_SESSION_ID, "parent_session_123", String.class)
     */
    public static final String CONTEXT_KEY_PARENT_SESSION_ID = "parentSessionId";

    private final String name;
    private final String description;
    private final SubAgentProvider<?> agentProvider;
    private final SubAgentConfig config;

    /**
     * Creates a new SubAgentTool.
     *
     * @param agentProvider Provider for creating agent instances
     * @param config Configuration for the tool
     */
    public SubAgentTool(SubAgentProvider<?> agentProvider, SubAgentConfig config) {
        // Create a sample agent to derive name and description
        Agent sampleAgent = agentProvider.provide();

        this.agentProvider = agentProvider;
        this.config = config != null ? config : SubAgentConfig.defaults();
        this.name = resolveToolName(sampleAgent, this.config);
        this.description = resolveDescription(sampleAgent, this.config);

        logger.debug("Created SubAgentTool: name={}, description={}", name, description);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return buildSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return executeConversation(param);
    }

    /**
     * Executes a conversation with the sub-agent, managing session lifecycle.
     *
     * <p>This method handles:
     *
     * <ul>
     *   <li>Session ID generation for new conversations
     *   <li>Agent state loading for continued sessions
     *   <li>Memory sharing based on context sharing mode
     *   <li>Message execution (streaming or non-streaming based on config)
     *   <li>Agent state persistence after execution
     * </ul>
     *
     * @param param The tool call parameters containing input and emitter
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeConversation(ToolCallParam param) {
        return Mono.deferContextual(
                (ctxView) -> {
                    try {
                        Map<String, Object> input = param.getInput();

                        // Get or create session ID
                        String sessionId = (String) input.get(PARAM_SESSION_ID);
                        boolean isNewSession = sessionId == null;

                        // Get context sharing mode early for session inheritance logic
                        ContextSharingMode contextMode = config.getContextSharingMode();

                        if (isNewSession) {
                            // Try to inherit session ID from parent in SHARED/FORK modes
                            String parentSessionId = getParentSessionId(param.getContext());
                            if (parentSessionId != null
                                    && (contextMode == ContextSharingMode.SHARED
                                            || contextMode == ContextSharingMode.FORK)) {
                                // Derive sub-agent session ID from parent session
                                sessionId = parentSessionId + "_" + name;
                                logger.debug(
                                        "Inherited session ID from parent: {} -> {}",
                                        parentSessionId,
                                        sessionId);
                            } else {
                                // Generate new session ID
                                sessionId = UUID.randomUUID().toString();
                            }
                        }

                        // Get message
                        String message = (String) input.get(PARAM_MESSAGE);
                        if (message == null || message.isEmpty()) {
                            return Mono.just(ToolResultBlock.error("Message is required"));
                        }

                        // Prepare context for agent creation
                        final String finalSessionId = sessionId;
                        Agent parentAgent = param.getAgent();

                        // Compute memory to use based on context sharing mode
                        Memory memoryToUse = computeMemoryToUse(parentAgent, contextMode);

                        // Create SubAgentContext with parent agent and memory
                        SubAgentContext context =
                                new SubAgentContext(parentAgent, contextMode, memoryToUse);

                        // Create agent with context (memory is set during construction)
                        Agent agent = agentProvider.provideWithContext(context);

                        // Log sub-agent execution for debugging - Including model info if available
                        String modelInfo = "unknown";
                        if (agent instanceof io.agentscope.core.ReActAgent) {
                            io.agentscope.core.model.Model agentModel =
                                    ((io.agentscope.core.ReActAgent) agent).getModel();
                            if (agentModel != null) {
                                modelInfo = agentModel.getModelName();
                            }
                        }
                        logger.info(
                                "SubAgentTool executing: toolName={}, agentName={}, agentId={},"
                                        + " model={}, contextMode={}",
                                name,
                                agent.getName(),
                                agent.getAgentId(),
                                modelInfo,
                                contextMode);

                        // Load existing state if continuing session (only for NEW mode)
                        if (!isNewSession
                                && contextMode == ContextSharingMode.NEW
                                && agent instanceof StateModule) {
                            loadAgentState(finalSessionId, (StateModule) agent);
                        }

                        // Build user message - in SHARED/FORK modes, try to preserve images from
                        // original user message
                        Msg userMsg = buildUserMessage(message, memoryToUse);

                        logger.debug(
                                "Session {} with agent '{}': {}",
                                isNewSession ? "started" : "continued",
                                agent.getName(),
                                message.substring(0, Math.min(50, message.length())));

                        // Get emitter for event forwarding
                        ToolEmitter emitter = param.getEmitter();

                        // Execute and save state after completion
                        Mono<ToolResultBlock> result;
                        if (config.isForwardEvents()) {
                            result = executeWithStreaming(agent, userMsg, finalSessionId, emitter);
                        } else {
                            result = executeWithoutStreaming(agent, userMsg, finalSessionId);
                        }

                        // Save state after execution (only for NEW mode with independent session)
                        if (contextMode == ContextSharingMode.NEW && agent instanceof StateModule) {
                            return result.doOnSuccess(
                                    r -> saveAgentState(finalSessionId, (StateModule) agent));
                        }

                        return result;
                    } catch (Exception e) {
                        logger.error("Error in session setup: {}", e.getMessage(), e);
                        return Mono.just(
                                ToolResultBlock.error("Session setup failed: " + e.getMessage()));
                    }
                });
    }

    /**
     * Computes the memory to use based on context sharing mode.
     *
     * <p>
     *
     * <ul>
     *   <li>SHARED: Returns parent's memory directly
     *   <li>FORK: Returns a fork of parent's memory
     *   <li>NEW: Returns null (sub-agent should use its own independent memory)
     * </ul>
     *
     * @param parentAgent The parent agent (source of memory for SHARED/FORK modes)
     * @param contextMode The context sharing mode
     * @return The memory to use, or null for independent memory
     */
    private Memory computeMemoryToUse(Agent parentAgent, ContextSharingMode contextMode) {
        // Only ReActAgent supports memory sharing
        if (!(parentAgent instanceof ReActAgent parentReactAgent)) {
            logger.debug("Parent is not a ReActAgent, sub-agent will use independent memory");
            return null;
        }

        Memory parentMemory = parentReactAgent.getMemory();
        if (parentMemory == null) {
            logger.debug("Parent has no memory, sub-agent will use independent memory");
            return null;
        }

        switch (contextMode) {
            case SHARED:
                // Fork parent's memory and remove pending tool calls
                // Note: We cannot directly share parent's memory because it contains
                // the pending tool call to this sub-agent, which would cause
                // validation errors when the sub-agent tries to add new messages.
                // We use a forked copy with pending tool calls removed.
                Memory sharedMemory = parentMemory.fork();
                removePendingToolCalls(sharedMemory);
                logger.debug(
                        "Sub-agent will use SHARED (forked with pending calls removed) memory from"
                                + " parent ({} messages)",
                        sharedMemory.getMessages().size());
                return sharedMemory;

            case FORK:
                // Fork parent's memory and remove pending tool calls
                Memory forkedMemory = parentMemory.fork();
                removePendingToolCalls(forkedMemory);
                logger.debug(
                        "Sub-agent will use FORKed memory from parent ({} messages)",
                        forkedMemory.getMessages().size());
                return forkedMemory;

            case NEW:
                // Use independent memory (return null)
                logger.debug("Sub-agent will use NEW independent memory");
                return null;

            default:
                logger.debug(
                        "Unknown context sharing mode: {}, using independent memory", contextMode);
                return null;
        }
    }

    /**
     * Removes pending tool calls from memory.
     *
     * <p>This is necessary because when a sub-agent is invoked as a tool, the parent's memory
     * contains the tool_use block that called the sub-agent. If we share this memory directly,
     * the sub-agent will fail validation when trying to add new messages because of the pending
     * tool call.
     *
     * <p>This method removes:
     * <ul>
     *   <li>ASSISTANT messages that contain only ToolUseBlocks (no text)</li>
     *   <li>Messages with pending tool calls that haven't been resolved</li>
     * </ul>
     *
     * @param memory The memory to clean up
     */
    private void removePendingToolCalls(Memory memory) {
        List<Msg> messages = memory.getMessages();
        // Iterate backwards and remove messages with pending tool calls
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            // Check if this is an ASSISTANT message with only tool calls (no text content)
            if (msg.getRole() == MsgRole.ASSISTANT && hasOnlyToolCalls(msg)) {
                memory.deleteMessage(i);
                logger.debug("Removed pending tool call message from shared memory");
            } else {
                // Stop at the first message that's not a pending tool call
                break;
            }
        }
    }

    /**
     * Checks if a message contains only tool calls (no text content).
     *
     * @param msg The message to check
     * @return true if the message has only ToolUseBlocks
     */
    private boolean hasOnlyToolCalls(Msg msg) {
        List<io.agentscope.core.message.ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (io.agentscope.core.message.ContentBlock block : content) {
            if (block instanceof io.agentscope.core.message.TextBlock) {
                return false; // Has text content, not just tool calls
            }
        }
        // Check if there are any ToolUseBlocks
        return content.stream().anyMatch(b -> b instanceof io.agentscope.core.message.ToolUseBlock);
    }

    /**
     * Gets the parent session ID from the tool execution context.
     *
     * <p>The parent session ID should be registered in the context with the key {@link
     * #CONTEXT_KEY_PARENT_SESSION_ID}.
     *
     * @param context The tool execution context, may be null
     * @return The parent session ID, or null if not available
     */
    private String getParentSessionId(ToolExecutionContext context) {
        if (context == null) {
            return null;
        }
        return context.get(CONTEXT_KEY_PARENT_SESSION_ID, String.class);
    }

    /**
     * Loads agent state from the session storage.
     *
     * <p>If the session exists, the agent's state is restored. Any errors during loading are logged
     * but do not interrupt execution.
     *
     * @param sessionId The session ID to load state from
     * @param agent The state module to restore state into
     */
    private void loadAgentState(String sessionId, StateModule agent) {
        Session session = config.getSession();
        try {
            agent.loadIfExists(session, sessionId);
            logger.debug("Loaded state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn("Failed to load state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Saves agent state to the session storage.
     *
     * <p>Persists the agent's current state. Any errors during saving are logged but do not
     * interrupt execution.
     *
     * @param sessionId The session ID to save state under
     * @param agent The state module to save state from
     */
    private void saveAgentState(String sessionId, StateModule agent) {
        Session session = config.getSession();
        try {
            agent.saveTo(session, sessionId);
            logger.debug("Saved state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn("Failed to save state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Executes agent call with streaming, forwarding events to the emitter.
     *
     * <p>Uses the agent's streaming API and forwards each event to the provided emitter as JSON.
     * The final response is extracted from the last event.
     *
     * @param agent The agent to execute
     * @param userMsg The user message to send
     * @param sessionId The session ID for result building
     * @param emitter The emitter to forward events to
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeWithStreaming(
            Agent agent, Msg userMsg, String sessionId, ToolEmitter emitter) {

        StreamOptions streamOptions =
                config.getStreamOptions() != null
                        ? config.getStreamOptions()
                        : StreamOptions.defaults();

        return Mono.deferContextual(
                ctxView ->
                        agent.stream(List.of(userMsg), streamOptions)
                                .doOnNext(event -> forwardEvent(event, emitter, agent, sessionId))
                                .filter(Event::isLast)
                                .last()
                                .map(
                                        lastEvent -> {
                                            Msg response = lastEvent.getMessage();
                                            return buildResult(response, sessionId);
                                        })
                                .contextWrite(context -> context.putAll(ctxView))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in streaming execution:" + " {}",
                                                    e.getMessage(),
                                                    e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        }));
    }

    /**
     * Executes agent call without streaming.
     *
     * <p>Uses the agent's standard call API. No events are forwarded to the emitter.
     *
     * @param agent The agent to execute
     * @param userMsg The user message to send
     * @param sessionId The session ID for result building
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeWithoutStreaming(
            Agent agent, Msg userMsg, String sessionId) {

        return Mono.deferContextual(
                ctxView ->
                        agent.call(List.of(userMsg))
                                .map(response -> buildResult(response, sessionId))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in execution: {}", e.getMessage(), e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        })
                                .contextWrite(context -> context.putAll(ctxView)));
    }

    /**
     * Forwards an event to the emitter as serialized JSON.
     *
     * <p>Serializes the event using JsonCodec and emits it as a text block. Serialization
     * failures are logged but do not interrupt execution.
     *
     * @param event The event to forward
     * @param emitter The emitter to send the event to
     * @param agent The agent
     * @param sessionId Current session ID
     */
    private void forwardEvent(Event event, ToolEmitter emitter, Agent agent, String sessionId) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(event);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subagent_event", event == null ? "" : event);
            metadata.put("subagent_name", agent.getName() == null ? "" : agent.getName());
            metadata.put("subagent_id", agent.getAgentId() == null ? "" : agent.getAgentId());
            metadata.put("subagent_session_id", sessionId == null ? "" : sessionId);
            emitter.emit(
                    new ToolResultBlock(
                            null, null, List.of(TextBlock.builder().text(json).build()), metadata));
        } catch (Exception e) {
            logger.warn("Failed to serialize event to JSON: {}", e.getMessage());
        }
    }

    /**
     * Builds the final tool result with session context.
     *
     * <p>Formats the response to include the session ID, allowing callers to continue the
     * conversation by passing the session ID in subsequent calls.
     *
     * @param response The agent's response message
     * @param sessionId The session ID to include in the result
     * @return A tool result block containing the formatted response
     */
    private ToolResultBlock buildResult(Msg response, String sessionId) {
        String textContent = response.getTextContent();

        // Return response with session context
        return ToolResultBlock.text(
                String.format(
                        "session_id: %s\n\n%s",
                        sessionId, textContent != null ? textContent : "(No response)"));
    }

    /**
     * Builds a user message with text content.
     *
     * @param message The text message
     * @return The constructed message
     */
    private Msg buildUserMessage(String message, Memory memory) {
        // Try to extract images from the most recent user message in memory
        List<io.agentscope.core.message.ContentBlock> contentBlocks = new ArrayList<>();
        contentBlocks.add(TextBlock.builder().text(message).build());

        if (memory != null) {
            List<Msg> messages = memory.getMessages();
            // Search from the most recent message backwards for images
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg msg = messages.get(i);
                if (msg.getRole() == MsgRole.USER) {
                    List<ImageBlock> images = msg.getContentBlocks(ImageBlock.class);
                    if (!images.isEmpty()) {
                        contentBlocks.addAll(images);
                        logger.debug(
                                "Found {} image(s) in user message, forwarding to sub-agent",
                                images.size());
                        break; // Only take images from the most recent user message
                    }
                }
            }
        }

        return Msg.builder().role(MsgRole.USER).content(contentBlocks).build();
    }

    /**
     * Builds the JSON schema for tool parameters.
     *
     * <p>Creates a schema with properties:
     *
     * <ul>
     *   <li>{@code session_id} - Optional string for continuing existing conversations
     *   <li>{@code message} - Required string containing the message to send
     * </ul>
     *
     * @return A map representing the JSON schema for tool parameters
     */
    private Map<String, Object> buildSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // Session ID (optional) - allow null since LLMs may explicitly pass null
        Map<String, Object> sessionIdProp = new HashMap<>();
        sessionIdProp.put("type", List.of("string", "null"));
        sessionIdProp.put(
                "description",
                "Session ID for multi-turn dialogue. Omit or pass null to start a NEW session."
                        + " To CONTINUE an existing session and retain memory, you MUST extract"
                        + " the session_id from the previous response and pass it here.");
        properties.put(PARAM_SESSION_ID, sessionIdProp);

        // Message (required)
        Map<String, Object> messageProp = new HashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description", "Message to send to the agent");
        properties.put(PARAM_MESSAGE, messageProp);

        schema.put("properties", properties);
        schema.put("required", List.of(PARAM_MESSAGE));

        return schema;
    }

    /**
     * Resolves the tool name from config or derives it from the agent.
     *
     * <p>Priority: config.toolName > derived from agent name. When deriving from agent name, the
     * name is converted to lowercase and prefixed with "call_" (e.g., "ResearchAgent" becomes
     * "call_researchagent").
     *
     * @param agent The agent to derive name from if not configured
     * @param config The configuration that may override the name
     * @return The resolved tool name
     */
    private String resolveToolName(Agent agent, SubAgentConfig config) {
        if (config.getToolName() != null && !config.getToolName().isEmpty()) {
            return config.getToolName();
        }
        // Generate from agent name: "ResearchAgent" -> "call_researchagent"
        String agentName = agent.getName();
        if (agentName == null || agentName.isEmpty()) {
            return "call_agent";
        }
        return "call_" + agentName.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    /**
     * Resolves the tool description from config or derives it from the agent.
     *
     * <p>Priority: config.description > agent.description > default. The default description is
     * generated as "Call {agentName} to complete tasks".
     *
     * @param agent The agent to derive description from if not configured
     * @param config The configuration that may override the description
     * @return The resolved description
     */
    private String resolveDescription(Agent agent, SubAgentConfig config) {
        if (config.getDescription() != null && !config.getDescription().isEmpty()) {
            return config.getDescription();
        }
        // Use agent description if available
        String agentDesc = agent.getDescription();
        if (agentDesc != null && !agentDesc.isEmpty()) {
            return agentDesc;
        }
        // Generate default description
        return "Call " + agent.getName() + " to complete tasks";
    }
}
