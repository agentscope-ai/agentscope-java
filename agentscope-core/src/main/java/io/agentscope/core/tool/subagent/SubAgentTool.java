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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
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
 * <p>The tool exposes two parameters:
 *
 * <ul>
 *   <li>{@code session_id} - Optional. Omit to start a new session, provide to continue an
 *       existing one.
 *   <li>{@code message} - Required. The message to send to the agent.
 * </ul>
 */
public class SubAgentTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Parameter name for session ID. */
    private static final String PARAM_SESSION_ID = "session_id";

    /** Parameter name for message. */
    private static final String PARAM_MESSAGE = "message";

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
     *   <li>Message execution (streaming or non-streaming based on config)
     *   <li>Agent state persistence after execution
     * </ul>
     *
     * @param param The tool call parameters containing input and emitter
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeConversation(ToolCallParam param) {
        return Mono.defer(
                () -> {
                    try {
                        Map<String, Object> input = param.getInput();

                        // Get or create session ID
                        String sessionId = (String) input.get(PARAM_SESSION_ID);
                        boolean isNewSession = sessionId == null;
                        if (isNewSession) {
                            sessionId = UUID.randomUUID().toString();
                        }

                        // Get message
                        String message = (String) input.get(PARAM_MESSAGE);
                        if (message == null || message.isEmpty()) {
                            return Mono.just(ToolResultBlock.error("Message is required"));
                        }

                        // Create agent for this session
                        final String finalSessionId = sessionId;
                        Agent agent = agentProvider.provide();

                        // Load existing state if continuing session
                        if (!isNewSession && agent instanceof StateModule) {
                            loadAgentState(finalSessionId, (StateModule) agent);
                        }

                        // Build user message
                        Msg userMsg =
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text(message).build())
                                        .build();

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

                        // Save state after execution
                        return result.doOnSuccess(
                                r -> {
                                    if (agent instanceof StateModule) {
                                        saveAgentState(finalSessionId, (StateModule) agent);
                                    }
                                });
                    } catch (Exception e) {
                        logger.error("Error in session setup: {}", e.getMessage(), e);
                        return Mono.just(
                                ToolResultBlock.error("Session setup failed: " + e.getMessage()));
                    }
                });
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

        return agent.stream(List.of(userMsg), streamOptions)
                .doOnNext(event -> forwardEvent(event, emitter, streamOptions))
                .filter(Event::isLast)
                .last()
                .map(
                        lastEvent -> {
                            Msg response = lastEvent.getMessage();
                            return buildResult(response, sessionId);
                        })
                .onErrorResume(
                        e -> {
                            logger.error("Error in streaming execution: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error("Execution error: " + e.getMessage()));
                        });
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

        return agent.call(List.of(userMsg))
                .map(response -> buildResult(response, sessionId))
                .onErrorResume(
                        e -> {
                            logger.error("Error in execution: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error("Execution error: " + e.getMessage()));
                        });
    }

    /**
     * Forwards an event to the emitter as serialized JSON.
     *
     * <p>Serializes the event using Jackson ObjectMapper and emits it as a text block. Serialization
     * failures are logged but do not interrupt execution.
     *
     * @param event The event to forward
     * @param emitter The emitter to send the event to
     * @param streamOptions The stream options (currently unused, reserved for future filtering)
     */
    private void forwardEvent(Event event, ToolEmitter emitter, StreamOptions streamOptions) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.emit(ToolResultBlock.text(json));
        } catch (JsonProcessingException e) {
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
     * Builds the JSON schema for tool parameters.
     *
     * <p>Creates a schema with two properties:
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

        // Session ID (optional)
        Map<String, Object> sessionIdProp = new HashMap<>();
        sessionIdProp.put("type", "string");
        sessionIdProp.put(
                "description",
                "Session ID for multi-turn dialogue. Omit to start a NEW session."
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
