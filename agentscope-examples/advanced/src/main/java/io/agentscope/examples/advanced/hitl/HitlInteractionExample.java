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
package io.agentscope.examples.advanced.hitl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * HITL (Human-in-the-Loop) Interactive UI Example.
 *
 * <p>This example demonstrates how an AI agent can proactively ask users for structured input
 * through dynamic UI components. When the agent encounters ambiguous or incomplete requests,
 * it uses the {@code ask_user} tool to request clarification, and the frontend dynamically
 * renders the appropriate UI component (text input, select buttons, forms, etc.).
 *
 * <h2>Architecture</h2>
 * <pre>
 * User Input → Agent Reasoning → LLM decides info is missing
 *     → Calls ask_user tool → ToolSuspendException → Agent pauses
 *     → SSE sends USER_INTERACTION event → Frontend renders UI
 *     → User responds → Agent resumes with response → Completes task
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 * export DASHSCOPE_API_KEY=your_api_key
 * java -cp ... io.agentscope.examples.advanced.hitl.HitlInteractionExample
 * </pre>
 * Then open http://localhost:8080/hitl-interaction/index.html
 */
@SpringBootApplication
@RestController
@RequestMapping("/api")
public class HitlInteractionExample {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> TOOLS_REQUIRING_CONFIRMATION =
            Set.of(AddCalendarEventTool.TOOL_NAME);

    private static final String SYS_PROMPT =
            """
            You are a professional fitness coach assistant that creates personalized
            workout plans.

            RULES:
            - NEVER ask questions in plain text. ALWAYS use the ask_user tool instead.
            - Call ask_user ONLY ONCE per response.
            - Respond in the same language as the user's input.

            Information to collect (one at a time, skip what the user already provided):
            - Fitness goal: select — Fat Loss, Muscle Gain, General Fitness, Flexibility
            - Body info (age, height, weight): form with number fields
            - Available equipment: multi_select with allow_other=true — e.g. dumbbells,
              barbells, treadmills, pull-up bars, resistance bands (tailor to user's goal)
            - Workout days per week: number
            - Injury / health concerns: confirm first, then text if yes
            - Plan start date: date

            Workflow:
            1. Collect missing information one at a time via ask_user.
            2. Generate a detailed weekly plan with exercises, sets, reps, and rest times.
            3. Call add_calendar_event once per workout day to add it to the calendar.
            """;

    private final Session session = new InMemorySession();

    private final ConcurrentHashMap<String, ReActAgent> runningAgents = new ConcurrentHashMap<>();

    private final Toolkit toolkit;

    private final DashScopeChatModel model;

    {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        toolkit = new Toolkit();
        toolkit.registerTool(new UserInteractionTool());
        toolkit.registerTool(new AddCalendarEventTool());

        model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                        .enableThinking(false)
                        .formatter(new DashScopeChatFormatter())
                        .build();
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Please set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  HITL Interactive UI Example");
        System.out.println("  Agent with dynamic UI-based user interaction");
        System.out.println("=".repeat(70));
        System.out.println("  Open: http://localhost:8080/hitl-interaction/index.html");
        System.out.println("=".repeat(70) + "\n");

        SpringApplication.run(HitlInteractionExample.class, args);
    }

    // ==================== Chat Endpoint ====================

    /**
     * Send a chat message and receive streaming response via SSE.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chat(
            @RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", "default");
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<Map<String, Object>>builder()
                            .data(errorEvent("Missing required parameter: message"))
                            .build());
        }

        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        Msg userMsg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        Flux<Map<String, Object>> events = agent.stream(userMsg).flatMap(this::convertEvent);
        return wrapAsSSE(sessionId, agent, events);
    }

    // ==================== User Interaction Response Endpoint ====================

    /**
     * Submit user's response to an ask_user interaction.
     *
     * <p>When the agent calls the ask_user tool and suspends, the frontend renders
     * a UI component. After the user responds, this endpoint is called to resume
     * the agent with the user's response as a ToolResultBlock.
     */
    @PostMapping(value = "/chat/respond", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> respond(
            @RequestBody Map<String, Object> request) {
        String sessionId = (String) request.getOrDefault("sessionId", "default");
        String toolId = (String) request.get("toolId");
        if (toolId == null || toolId.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<Map<String, Object>>builder()
                            .data(errorEvent("Missing required parameter: toolId"))
                            .build());
        }
        Object response = request.get("response");

        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        String responseText;
        if (response instanceof String s) {
            responseText = s;
        } else {
            try {
                responseText = OBJECT_MAPPER.writeValueAsString(response);
            } catch (Exception e) {
                responseText = String.valueOf(response);
            }
        }

        // Create ToolResultBlock with user's response
        ToolResultBlock result =
                ToolResultBlock.of(
                        toolId,
                        UserInteractionTool.TOOL_NAME,
                        TextBlock.builder().text("User responded: " + responseText).build());

        Msg responseMsg = Msg.builder().role(MsgRole.TOOL).content(result).build();

        Flux<Map<String, Object>> events = agent.stream(responseMsg).flatMap(this::convertEvent);
        return wrapAsSSE(sessionId, agent, events);
    }

    // ==================== Session Management ====================

    /**
     * Clear a chat session.
     */
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        session.delete(SimpleSessionKey.of(sessionId));
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Interrupt a running agent.
     */
    @PostMapping("/chat/interrupt/{sessionId}")
    public ResponseEntity<Map<String, Object>> interrupt(@PathVariable String sessionId) {
        ReActAgent agent = runningAgents.get(sessionId);
        if (agent != null) {
            agent.interrupt();
            return ResponseEntity.ok(Map.of("success", true, "interrupted", true));
        }
        return ResponseEntity.ok(Map.of("success", true, "interrupted", false));
    }

    // ==================== Tool Confirmation Endpoint ====================

    /**
     * Approve or reject pending tool execution.
     *
     * <p>When the agent calls a tool that requires confirmation (e.g. add_calendar_event),
     * the {@linkplain ToolConfirmationHook} stops the agent before execution. The frontend
     * displays approve/reject buttons. This endpoint handles the user's decision:
     * <ul>
     *   <li>Approved: resumes the agent to execute the pending tools</li>
     *   <li>Rejected: feeds synthetic "cancelled" tool results so the agent can continue</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/chat/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> confirmTool(
            @RequestBody Map<String, Object> request) {
        String sessionId = (String) request.getOrDefault("sessionId", "default");
        boolean confirmed = Boolean.TRUE.equals(request.get("confirmed"));
        List<Map<String, String>> toolCalls = (List<Map<String, String>>) request.get("toolCalls");

        ReActAgent agent = createAgent(sessionId);
        runningAgents.put(sessionId, agent);

        Flux<Map<String, Object>> eventFlux;
        if (confirmed) {
            eventFlux = agent.stream(StreamOptions.defaults()).flatMap(this::convertEvent);
        } else {
            String reason = (String) request.getOrDefault("reason", "Cancelled by user");
            List<ToolResultBlock> results = new ArrayList<>();
            if (toolCalls != null) {
                for (Map<String, String> tc : toolCalls) {
                    results.add(
                            ToolResultBlock.of(
                                    tc.get("id"),
                                    tc.get("name"),
                                    TextBlock.builder().text(reason).build()));
                }
            }
            Msg cancelMsg =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(results.toArray(new ToolResultBlock[0]))
                            .build();
            eventFlux = agent.stream(cancelMsg).flatMap(this::convertEvent);
        }

        return wrapAsSSE(sessionId, agent, eventFlux);
    }

    // ==================== Agent Factory ====================

    private ReActAgent createAgent(String sessionId) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("FitnessCoach")
                        .sysPrompt(SYS_PROMPT)
                        .model(model)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hook(new ToolConfirmationHook(TOOLS_REQUIRING_CONFIRMATION))
                        .hook(new ObservationHook())
                        .build();

        agent.loadIfExists(session, sessionId);
        return agent;
    }

    // ==================== SSE Wrapping ====================

    private Flux<ServerSentEvent<Map<String, Object>>> wrapAsSSE(
            String sessionId, ReActAgent agent, Flux<Map<String, Object>> events) {
        return events.concatWith(Flux.just(completeEvent()))
                .onErrorResume(error -> Flux.just(errorEvent(error.getMessage()), completeEvent()))
                .doFinally(
                        signal -> {
                            runningAgents.remove(sessionId);
                            agent.saveTo(session, sessionId);
                        })
                .map(data -> ServerSentEvent.<Map<String, Object>>builder().data(data).build());
    }

    // ==================== Event Conversion ====================

    /**
     * Convert agent events to SSE-friendly maps.
     *
     * <p>Key logic: when the agent returns with {@code TOOL_SUSPENDED} and the suspended
     * tool is {@code ask_user}, we emit a {@code USER_INTERACTION} event containing the
     * UI specification from the tool's input parameters.
     */
    private Flux<Map<String, Object>> convertEvent(Event event) {
        List<Map<String, Object>> events = new ArrayList<>();
        Msg msg = event.getMessage();

        switch (event.getType()) {
            case REASONING -> {
                if (event.isLast() && msg.hasContentBlocks(ToolUseBlock.class)) {
                    List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                    boolean needsConfirm =
                            toolCalls.stream()
                                    .anyMatch(
                                            t ->
                                                    TOOLS_REQUIRING_CONFIRMATION.contains(
                                                            t.getName()));

                    if (needsConfirm) {
                        // Tools require user approval — emit TOOL_CONFIRM
                        events.add(toolConfirmEvent(toolCalls));
                    } else {
                        // Normal tool calls — show non-ask_user tools
                        for (ToolUseBlock tool : toolCalls) {
                            if (!UserInteractionTool.TOOL_NAME.equals(tool.getName())) {
                                events.add(toolUseEvent(tool));
                            }
                        }
                    }
                } else {
                    // Streaming text chunks
                    String text = extractText(msg);
                    if (text != null && !text.isEmpty()) {
                        events.add(textEvent(text, !event.isLast()));
                    }
                }
            }
            case TOOL_RESULT -> {
                for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                    if (!UserInteractionTool.TOOL_NAME.equals(result.getName())) {
                        events.add(toolResultEvent(result));
                    }
                }
            }
            case AGENT_RESULT -> {
                GenerateReason reason = msg.getGenerateReason();
                if (reason == GenerateReason.TOOL_SUSPENDED) {
                    List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                    for (ToolUseBlock tool : toolCalls) {
                        if (UserInteractionTool.TOOL_NAME.equals(tool.getName())) {
                            events.add(userInteractionEvent(tool));
                        }
                    }
                }
            }
            default -> {
                // HINT, SUMMARY, etc. - ignore for simplicity
            }
        }

        return Flux.fromIterable(events);
    }

    // ==================== Event Builders ====================

    private Map<String, Object> textEvent(String content, boolean incremental) {
        return Map.of("type", "TEXT", "content", content, "incremental", incremental);
    }

    private Map<String, Object> toolUseEvent(ToolUseBlock tool) {
        return Map.of(
                "type", "TOOL_USE",
                "toolId", tool.getId(),
                "toolName", tool.getName(),
                "toolInput", convertInput(tool.getInput()));
    }

    private Map<String, Object> toolResultEvent(ToolResultBlock result) {
        return Map.of(
                "type", "TOOL_RESULT",
                "toolId", result.getId(),
                "toolName", result.getName(),
                "toolResult", ObservationHook.extractToolOutputText(result, ""));
    }

    /**
     * Build a TOOL_CONFIRM event containing all pending tool calls that need user approval.
     */
    private Map<String, Object> toolConfirmEvent(List<ToolUseBlock> toolCalls) {
        List<Map<String, Object>> pending =
                toolCalls.stream()
                        .map(
                                tool ->
                                        Map.<String, Object>of(
                                                "id", tool.getId(),
                                                "name", tool.getName(),
                                                "input", convertInput(tool.getInput()),
                                                "needsConfirm",
                                                        TOOLS_REQUIRING_CONFIRMATION.contains(
                                                                tool.getName())))
                        .toList();
        return Map.of("type", "TOOL_CONFIRM", "pendingToolCalls", pending);
    }

    /**
     * Build a USER_INTERACTION event from the ask_user tool's ToolUseBlock.
     *
     * <p>The tool's input parameters contain the UI specification:
     * question, ui_type, options, fields, default_value.
     */
    private Map<String, Object> userInteractionEvent(ToolUseBlock tool) {
        Map<String, Object> input = tool.getInput();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "USER_INTERACTION");
        event.put("toolId", tool.getId());
        event.put("question", input.getOrDefault("question", "Please provide more information"));
        event.put("uiType", input.getOrDefault("ui_type", "text"));

        if (input.containsKey("options")) {
            event.put("options", input.get("options"));
        }
        if (input.containsKey("fields")) {
            event.put("fields", input.get("fields"));
        }
        if (input.containsKey("default_value")) {
            event.put("defaultValue", input.get("default_value"));
        }
        if (Boolean.TRUE.equals(input.get("allow_other"))) {
            event.put("allowOther", true);
        }

        return event;
    }

    private static Map<String, Object> completeEvent() {
        return Map.of("type", "COMPLETE");
    }

    private static Map<String, Object> errorEvent(String error) {
        return Map.of("type", "ERROR", "error", error != null ? error : "Unknown error");
    }

    // ==================== Helpers ====================

    private String extractText(Msg msg) {
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (textBlocks.isEmpty()) {
            return null;
        }
        return textBlocks.stream().map(TextBlock::getText).collect(Collectors.joining());
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
