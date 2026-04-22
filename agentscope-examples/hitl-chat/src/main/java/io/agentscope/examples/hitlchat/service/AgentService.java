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
package io.agentscope.examples.hitlchat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.examples.hitlchat.dto.ChatEvent;
import io.agentscope.examples.hitlchat.dto.ChatEvent.PendingToolCall;
import io.agentscope.examples.hitlchat.dto.ToolConfirmRequest.ToolCallInfo;
import io.agentscope.examples.hitlchat.hook.ToolConfirmationHook;
import io.agentscope.examples.hitlchat.tools.BuiltinTools;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Service for managing agents and chat sessions.
 *
 * <p>Uses AgentScope's InMemorySession for agent state persistence. Agent instances are created
 * per-request and state is loaded from session. Interruption is handled via SSE connection
 * cancellation.
 */
@Service
public class AgentService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${dashscope.model-name:qwen-plus}")
    private String modelName;

    private final McpService mcpService;
    private Toolkit sharedToolkit;
    private ToolConfirmationHook confirmationHook;

    /** Session storage for agent state persistence. */
    private final Session session = new InMemorySession();

    /** Cache for running agents, keyed by sessionId. Cleared after request completes. */
    private final ConcurrentHashMap<String, ReActAgent> runningAgents = new ConcurrentHashMap<>();

    public AgentService(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @PostConstruct
    public void init() {
        sharedToolkit = new Toolkit();
        sharedToolkit.registerTool(new BuiltinTools());
        sharedToolkit.registerTool(new ReadFileTool());
        Set<String> defaultDangerousTools = new HashSet<>();
        defaultDangerousTools.add("view_text_file");
        defaultDangerousTools.add("list_directory");
        confirmationHook = new ToolConfirmationHook(defaultDangerousTools);
    }

    public Toolkit getToolkit() {
        return sharedToolkit;
    }

    public Set<String> getToolNames() {
        return sharedToolkit.getToolNames();
    }

    public ToolConfirmationHook getConfirmationHook() {
        return confirmationHook;
    }

    /**
     * Create a new agent and load state from session.
     */
    private ReActAgent createAgent(String sessionId) {
        Toolkit sessionToolkit = sharedToolkit.copy();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful assistant with access to various tools. "
                                        + "Use tools when appropriate to help the user.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(sessionToolkit)
                        .memory(new InMemoryMemory())
                        .hook(confirmationHook)
                        .build();
        // Load existing state from session
        agent.loadIfExists(session, sessionId);
        return agent;
    }

    /**
     * Process a chat message.
     */
    public Flux<ChatEvent> chat(String sessionId, String message) {
        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return agent.stream(userMsg)
                .flatMap(this::convertEventToChatEvents)
                .concatWith(Flux.just(ChatEvent.complete()))
                .doFinally(
                        signal -> {
                            runningAgents.remove(sessionId);
                            agent.saveTo(session, sessionId);
                        })
                .onErrorResume(
                        error ->
                                Flux.just(
                                        ChatEvent.error(error.getMessage()), ChatEvent.complete()));
    }

    /**
     * Confirm or reject pending tool execution.
     */
    public Flux<ChatEvent> confirmTool(
            String sessionId, boolean confirmed, String reason, List<ToolCallInfo> toolCalls) {
        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        if (confirmed) {
            return agent.stream(StreamOptions.defaults())
                    .flatMap(this::convertEventToChatEvents)
                    .concatWith(Flux.just(ChatEvent.complete()))
                    .doFinally(
                            signal -> {
                                runningAgents.remove(sessionId);
                                agent.saveTo(session, sessionId);
                            })
                    .onErrorResume(
                            error ->
                                    Flux.just(
                                            ChatEvent.error(error.getMessage()),
                                            ChatEvent.complete()));
        } else {
            List<ToolResultBlock> results = new ArrayList<>();
            String cancelMessage = reason != null ? reason : "Operation cancelled by user";
            if (toolCalls != null) {
                for (ToolCallInfo tool : toolCalls) {
                    results.add(
                            ToolResultBlock.of(
                                    tool.getId(),
                                    tool.getName(),
                                    TextBlock.builder().text(cancelMessage).build()));
                }
            }
            Msg cancelResult =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.TOOL)
                            .content(results.toArray(new ToolResultBlock[0]))
                            .build();

            return agent.stream(cancelResult)
                    .flatMap(this::convertEventToChatEvents)
                    .concatWith(Flux.just(ChatEvent.complete()))
                    .doFinally(
                            signal -> {
                                runningAgents.remove(sessionId);
                                agent.saveTo(session, sessionId);
                            })
                    .onErrorResume(
                            error ->
                                    Flux.just(
                                            ChatEvent.error(error.getMessage()),
                                            ChatEvent.complete()));
        }
    }

    /**
     * Clear a session.
     */
    public void clearSession(String sessionId) {
        session.delete(SimpleSessionKey.of(sessionId));
    }

    /**
     * Interrupt a running agent by sessionId.
     *
     * @param sessionId the session ID
     * @return true if an agent was found and interrupted, false otherwise
     */
    public boolean interrupt(String sessionId) {
        ReActAgent agent = runningAgents.get(sessionId);
        if (agent != null) {
            agent.interrupt();
            return true;
        }
        return false;
    }

    /**
     * Check if a session exists.
     */
    public boolean sessionExists(String sessionId) {
        return session.exists(SimpleSessionKey.of(sessionId));
    }

    /**
     * Get all session keys.
     */
    public Set<SessionKey> listSessionKeys() {
        return session.listSessionKeys();
    }

    private Flux<ChatEvent> convertEventToChatEvents(Event event) {
        List<ChatEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        switch (event.getType()) {
            case REASONING -> {
                if (event.isLast() && msg.hasContentBlocks(ToolUseBlock.class)) {
                    List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                    boolean hasDangerous =
                            toolCalls.stream()
                                    .anyMatch(t -> confirmationHook.isDangerous(t.getName()));
                    if (hasDangerous) {
                        List<PendingToolCall> pending = new ArrayList<>();
                        for (ToolUseBlock tool : toolCalls) {
                            pending.add(
                                    new PendingToolCall(
                                            tool.getId(),
                                            tool.getName(),
                                            convertInput(tool.getInput()),
                                            confirmationHook.isDangerous(tool.getName())));
                        }
                        events.add(ChatEvent.toolConfirm(pending));
                    } else {
                        for (ToolUseBlock tool : toolCalls) {
                            events.add(
                                    ChatEvent.toolUse(
                                            tool.getId(),
                                            tool.getName(),
                                            convertInput(tool.getInput())));
                        }
                    }
                } else {
                    String text = extractText(msg);
                    if (text != null && !text.isEmpty()) {
                        events.add(ChatEvent.text(text, !event.isLast()));
                    }
                }
            }
            case TOOL_RESULT -> {
                for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                    events.add(
                            ChatEvent.toolResult(
                                    result.getId(), result.getName(), extractToolOutput(result)));
                }
            }
            case AGENT_RESULT -> {
                String text = msg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    events.add(ChatEvent.text(text, false));
                }
            }
            default -> {}
        }
        return Flux.fromIterable(events);
    }

    private String extractText(Msg msg) {
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (textBlocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TextBlock block : textBlocks) {
            sb.append(block.getText());
        }
        return sb.toString();
    }

    private String extractToolOutput(ToolResultBlock result) {
        List<ContentBlock> outputs = result.getOutput();
        if (outputs == null || outputs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : outputs) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertInput(Object input) {
        if (input == null) {
            return Map.of();
        }
        if (input instanceof Map) {
            return (Map<String, Object>) input;
        }
        try {
            return OBJECT_MAPPER.convertValue(input, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("value", input.toString());
        }
    }
}
