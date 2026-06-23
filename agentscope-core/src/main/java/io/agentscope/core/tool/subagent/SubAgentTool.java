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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

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
     *   <li>AgentStateStore ID generation for new conversations
     *   <li>Agent state loading for continued sessions
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
                        if (!isNewSession) {
                            loadAgentState(finalSessionId, agent);
                        }

                        // Build user message
                        Msg userMsg =
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text(message).build())
                                        .build();
                        RuntimeContext runtimeContext = param.getRuntimeContext();

                        logger.debug(
                                "AgentStateStore {} with agent '{}': {}",
                                isNewSession ? "started" : "continued",
                                agent.getName(),
                                message.substring(0, Math.min(50, message.length())));

                        // Get emitter for event forwarding
                        ToolEmitter emitter = param.getEmitter();

                        // Execute and save state after completion
                        Mono<ToolResultBlock> result;
                        if (config.isForwardEvents()) {
                            result =
                                    executeWithStreaming(
                                            agent,
                                            userMsg,
                                            finalSessionId,
                                            emitter,
                                            runtimeContext);
                        } else {
                            result =
                                    executeWithoutStreaming(
                                            agent, userMsg, finalSessionId, runtimeContext);
                        }

                        // Interrupt the sub-agent when the subscription is cancelled
                        // (e.g. ToolExecutor timeout triggers a retry on a new subscription).
                        // Without this, the orphan agent keeps consuming LLM tokens, SSH
                        // connections, and thread pool resources until it finishes naturally.
                        //
                        // Uses doFinally(CANCEL) rather than doOnCancel because doOnCancel
                        // also fires on normal error propagation cleanup, which would
                        // attempt to interrupt an already-failed agent.
                        //
                        // Uses interrupt(RuntimeContext) instead of the deprecated
                        // interrupt(InterruptSource) because the latter only looks up
                        // `defaultSessionId` which may differ from the session the call
                        // actually runs in.
                        result =
                                result.doFinally(
                                        signal -> {
                                            if (signal == SignalType.CANCEL) {
                                                interruptAgent(agent, runtimeContext);
                                            }
                                        });

                        // Save state after execution
                        return result.doOnSuccess(r -> saveAgentState(finalSessionId, agent));
                    } catch (Exception e) {
                        logger.error("Error in session setup: {}", e.getMessage(), e);
                        return Mono.just(
                                ToolResultBlock.error(
                                        "AgentStateStore setup failed: " + e.getMessage()));
                    }
                });
    }

    /**
     * Interrupts the sub-agent when the tool call is cancelled (e.g. by timeout-triggered
     * retry), preventing it from becoming an orphan agent that silently consumes resources.
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        if (agent instanceof ReActAgent ra) {
            ra.interrupt(ctx);
            logger.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its tool call subscription "
                            + "was cancelled.",
                    ra.getName(),
                    ra.getAgentId());
        }
    }

    /**
     * Loads sub-agent state for the conversation identified by {@code sessionId} from
     * {@link SubAgentConfig#getStateStore()} and merges it into the live agent's
     * {@link AgentState}. Errors are logged but do not interrupt execution.
     */
    private void loadAgentState(String sessionId, Agent agent) {
        if (!(agent instanceof ReActAgent ra)) {
            return;
        }
        AgentStateStore subSession = config.getStateStore();
        if (subSession == null) {
            return;
        }
        try {
            subSession
                    .get(null, sessionId, "agent_state", AgentState.class)
                    .ifPresent(loaded -> applyLoadedState(ra, loaded));
            logger.debug("Loaded sub-agent state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn(
                    "Failed to load sub-agent state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Saves the live {@link AgentState} for the conversation identified by {@code sessionId} into
     * {@link SubAgentConfig#getStateStore()}. Errors are logged but do not interrupt execution.
     */
    private void saveAgentState(String sessionId, Agent agent) {
        if (!(agent instanceof ReActAgent ra)) {
            return;
        }
        AgentStateStore subSession = config.getStateStore();
        if (subSession == null) {
            return;
        }
        try {
            subSession.save(null, sessionId, "agent_state", ra.getAgentState());
            logger.debug("Saved sub-agent state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn(
                    "Failed to save sub-agent state for session {}: {}", sessionId, e.getMessage());
        }
    }

    private static void applyLoadedState(ReActAgent agent, AgentState loaded) {
        AgentState live = agent.getAgentState();
        if (live == null) {
            return;
        }
        live.contextMutable().clear();
        live.contextMutable().addAll(loaded.getContext());
        live.setSummary(loaded.getSummary());
        live.setReplyId(loaded.getReplyId());
        live.setCurIter(loaded.getCurIter());
        live.setShutdownInterrupted(loaded.isShutdownInterrupted());
        live.getToolContext().setActivatedGroups(loaded.getToolContext().getActivatedGroups());
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
            Agent agent,
            Msg userMsg,
            String sessionId,
            ToolEmitter emitter,
            RuntimeContext runtimeContext) {

        StreamOptions streamOptions =
                config.getStreamOptions() != null
                        ? config.getStreamOptions()
                        : StreamOptions.defaults();

        return Mono.deferContextual(
                ctxView ->
                        streamWithContext(agent, userMsg, streamOptions, runtimeContext)
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
            Agent agent, Msg userMsg, String sessionId, RuntimeContext runtimeContext) {

        return Mono.deferContextual(
                ctxView ->
                        callWithContext(agent, userMsg, runtimeContext)
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

    private Flux<Event> streamWithContext(
            Agent agent, Msg userMsg, StreamOptions options, RuntimeContext runtimeContext) {
        if (runtimeContext != null && agent instanceof ReActAgent reActAgent) {
            return reActAgent.stream(List.of(userMsg), options, runtimeContext);
        }
        return agent.stream(List.of(userMsg), options);
    }

    private Mono<Msg> callWithContext(Agent agent, Msg userMsg, RuntimeContext runtimeContext) {
        if (runtimeContext != null && agent instanceof ReActAgent reActAgent) {
            return reActAgent.call(List.of(userMsg), runtimeContext);
        }
        return agent.call(List.of(userMsg));
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
                            null,
                            null,
                            null,
                            List.of(TextBlock.builder().text(json).build()),
                            metadata));
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

        // AgentStateStore ID (optional)
        Map<String, Object> sessionIdProp = new HashMap<>();
        sessionIdProp.put("type", "string");
        sessionIdProp.put(
                "description",
                "AgentStateStore ID for multi-turn dialogue. Omit to start a NEW session."
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
     * <p>Priority: explicit config.toolName > derived from agent name.
     * If derived from the agent name, the name will be sanitized to comply with strict LLM API constraints
     * (e.g., ^[a-zA-Z0-9_-]{1,64}$). For non-English characters (like Chinese) or excessively long names,
     * a deterministic short hash of the original name is appended to prevent naming collisions.
     *
     * @param agent The agent to derive name from if not configured
     * @param config The configuration that may override the name
     * @return The resolved tool name
     */
    private String resolveToolName(Agent agent, SubAgentConfig config) {
        if (config.getToolName() != null && !config.getToolName().trim().isEmpty()) {
            return config.getToolName().trim();
        }

        if (agent.getName() == null || agent.getName().trim().isEmpty()) {
            return "call_agent";
        }

        return sanitizeName("call_", agent.getName().trim());
    }

    /**
     * Helper method for {@link #resolveToolName(Agent, SubAgentConfig)}.
     * Extracts valid characters, lazily computes a deterministic hash
     * if necessary, and strictly enforces length limits via safe truncation.
     *
     * @param prefix The prefix to prepend to the tool name (e.g., "call_").
     * @param originalName The original name of the agent.
     * @return A sanitized, safe-to-use tool name.
     */
    private String sanitizeName(String prefix, String originalName) {
        // Keep the underscore, replace other illegal characters with underscores uniformly,
        // merge consecutive underscores, and remove the first and last underscores
        String lowerOriginal = originalName.toLowerCase(Locale.ROOT);
        String safePart =
                lowerOriginal
                        .replaceAll("[^a-z0-9_-]+", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_+|_+$", "");

        if (safePart.isEmpty()) {
            safePart = "agent";
        }

        String resolvedName = prefix + safePart;
        boolean isInformationLost = lowerOriginal.matches("^[a-z0-9_\\-\\s]+$");

        boolean needsHash = !isInformationLost || resolvedName.length() > 64;

        if (needsHash) {
            // Generate deterministic hash
            UUID uuid = UUID.nameUUIDFromBytes(originalName.getBytes(StandardCharsets.UTF_8));
            String shortHash = uuid.toString().replace("-", "").substring(0, 8);
            String suffix = "_" + shortHash;

            logger.warn(
                    "Agent name '{}' contains unsupported characters or is too long. Appended hash"
                        + " '{}' to prevent collisions. Only alphanumeric characters, underscores,"
                        + " and hyphens are supported in generated names. Recommended to configure"
                        + " an explicit English 'toolName' via SubAgentConfig.",
                    originalName,
                    shortHash);

            resolvedName = prefix + safePart + suffix;

            if (resolvedName.length() > 64) {
                int allowedSafePartLen = 64 - prefix.length() - suffix.length();
                if (allowedSafePartLen > 0) {
                    // replaceAll("_+$", "") strips any trailing underscores created by the cut,
                    // preventing double underscores when the suffix is appended.
                    safePart = safePart.substring(0, allowedSafePartLen).replaceAll("_+$", "");
                    resolvedName = prefix + safePart + suffix;
                } else {
                    // If prefix + suffix alone exceeds or equals 64 characters,
                    // discard the safePart entirely and forcefully truncate the prefix + hash
                    // combination.
                    resolvedName =
                            (prefix + shortHash)
                                    .substring(
                                            0, Math.min(64, prefix.length() + shortHash.length()));
                }
            }
        }

        return resolvedName;
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
