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
package io.agentscope.examples.plannotebook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service for managing Agent.
 */
@Service
public class AgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final ObjectMapper SSE_JSON = new ObjectMapper();

    /** Max length of the flattened transcript in each {@code ctx} SSE payload (large tool outputs). */
    private static final int CTX_FLAT_MAX_CHARS = 48_000;

    /** Max length per text-like field inside structured {@code messages} in {@code ctx} payloads. */
    private static final int CTX_FIELD_MAX_CHARS = 8_000;

    private static final Set<String> PLAN_TOOL_NAMES =
            Set.of(
                    "create_plan",
                    "update_plan_info",
                    "revise_current_plan",
                    "update_subtask_state",
                    "finish_subtask",
                    "get_subtask_count",
                    "finish_plan",
                    "view_subtasks",
                    "view_historical_plans",
                    "recover_historical_plan");

    /**
     * Allowed first token for {@link ShellCommandTool} (Unix/macOS). On Windows, extend with e.g.
     * {@code dir}, {@code type}. Whitelist empty is not used here — an empty whitelist would allow
     * any command per {@link io.agentscope.core.tool.coding.UnixCommandValidator}.
     */
    private static final Set<String> SHELL_COMMAND_WHITELIST =
            Set.of(
                    "ls", "pwd", "cat", "echo", "mkdir", "rmdir", "cp", "mv", "rm", "wc", "head",
                    "bash", "sh");

    private final PlanService planService;

    private String apiKey;
    private ReActAgent agent;
    private InMemoryMemory memory;
    private Toolkit toolkit;

    // Track if agent is paused waiting for user to continue
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    // Track if user has requested to stop (will pause on next plan tool execution)
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /**
     * Tool call inputs captured in {@link PostActingEvent} (runs before streaming TOOL_RESULT).
     * Consumed in {@link #mapEventToString} in order — no core changes required.
     */
    private final ConcurrentLinkedQueue<Map<String, Object>> pendingToolInputs =
            new ConcurrentLinkedQueue<>();

    /**
     * Serialized {@code ctx} lines captured after all hooks adjust {@link PreReasoningEvent} /
     * {@link PreSummaryEvent} input. Drained before each streamed {@link Event} is mapped.
     */
    private final ConcurrentLinkedQueue<String> pendingContextSseLines =
            new ConcurrentLinkedQueue<>();

    private final AtomicInteger contextSeq = new AtomicInteger(0);

    public AgentService(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("DASHSCOPE_API_KEY environment variable not set");
            throw new IllegalStateException("DASHSCOPE_API_KEY environment variable is required");
        }

        initializeAgent();
        log.info("AgentService initialized successfully");
    }

    private void initializeAgent() {
        pendingToolInputs.clear();
        pendingContextSseLines.clear();
        contextSeq.set(0);
        memory = new InMemoryMemory();
        toolkit = new Toolkit();
        toolkit.registerTool(new FileToolMock());
        String workspaceRoot = resolveWorkspaceRoot();
        toolkit.registerTool(new ReadFileTool(workspaceRoot));
        toolkit.registerTool(new WriteFileTool(workspaceRoot));
        toolkit.registerTool(new ShellCommandTool(workspaceRoot, SHELL_COMMAND_WHITELIST, null));

        PlanNotebook planNotebook = PlanNotebook.builder().build();
        planService.setPlanNotebook(planNotebook);

        // Register change hook to broadcast plan changes via SSE
        planNotebook.addChangeHook(
                "planServiceBroadcast", (notebook, plan) -> planService.broadcastPlanChange());

        // Create hook to pause agent for user review when stop is requested
        Hook planChangeHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent postActing) {
                            ToolUseBlock toolUse = postActing.getToolUse();
                            Map<String, Object> captured = new LinkedHashMap<>();
                            if (toolUse != null && toolUse.getInput() != null) {
                                captured.putAll(toolUse.getInput());
                            }
                            pendingToolInputs.offer(captured);

                            if (toolUse != null) {
                                String toolName = toolUse.getName();
                                if (PLAN_TOOL_NAMES.contains(toolName)) {
                                    if (stopRequested.compareAndSet(true, false)) {
                                        log.info(
                                                "Plan tool '{}' executed, pausing for user review",
                                                toolName);
                                        isPaused.set(true);
                                        postActing.stopAgent();
                                    }
                                }
                            }
                        }
                        return Mono.just(event);
                    }
                };

        /*
         * Low priority: run after other hooks so the payload matches what the model will receive.
         */
        Hook promptCaptureHook =
                new Hook() {
                    @Override
                    public int priority() {
                        return 1000;
                    }

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent pre) {
                            offerContextLine(
                                    "reasoning", pre.getModelName(), pre.getInputMessages());
                        } else if (event instanceof PreSummaryEvent sum) {
                            offerContextLine("summary", sum.getModelName(), sum.getInputMessages());
                        }
                        return Mono.just(event);
                    }
                };

        agent =
                ReActAgent.builder()
                        .name("PlanAgent")
                        .sysPrompt(
                                "You are a systematic assistant that helps users complete complex"
                                        + " tasks through structured planning.\n")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen3.5-27b")
                                        .stream(true)
                                        .enableThinking(true)
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(8192)
                                                        .build())
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(memory)
                        .toolkit(toolkit)
                        .maxIters(50)
                        .hook(planChangeHook)
                        .hook(promptCaptureHook)
                        .planNotebook(planNotebook)
                        .build();
    }

    /**
     * Send a message to the agent and get streaming response.
     */
    public Flux<String> chat(String sessionId, String message) {
        // Clear paused state when user sends a new message
        isPaused.set(false);
        pendingToolInputs.clear();
        pendingContextSseLines.clear();
        contextSeq.set(0);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return attachPendingContext(
                agent.stream(userMsg, createStreamOptions())
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Resume agent execution after user review.
     * This is called when user clicks "Continue" button after reviewing/modifying the plan.
     */
    public Flux<String> resume(String sessionId) {
        if (isPaused.compareAndSet(true, false)) {
            log.info("Resuming agent execution after user review");
            pendingToolInputs.clear();
            pendingContextSseLines.clear();
            contextSeq.set(0);

            // Resume by calling agent.stream() with no input message
            return attachPendingContext(
                    agent.stream(createStreamOptions()).subscribeOn(Schedulers.boundedElastic()));
        } else {
            log.warn("Tried to resume but agent is not paused or already resuming");
            return Flux.just("Agent is not paused or is already resuming.");
        }
    }

    private StreamOptions createStreamOptions() {
        return StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .includeActingChunk(false)
                .build();
    }

    private Flux<String> attachPendingContext(Flux<Event> events) {
        return events.concatMap(
                event -> {
                    List<String> prefix = drainPendingContextLines();
                    String mapped = mapEventToString(event);
                    boolean hasPrefix = !prefix.isEmpty();
                    boolean hasBody = mapped != null && !mapped.isEmpty();
                    if (!hasPrefix && !hasBody) {
                        return Flux.empty();
                    }
                    Flux<String> head = Flux.fromIterable(prefix);
                    return hasBody ? head.concatWith(Flux.just(mapped)) : head;
                });
    }

    private List<String> drainPendingContextLines() {
        List<String> out = new ArrayList<>();
        String line;
        while ((line = pendingContextSseLines.poll()) != null) {
            out.add(line);
        }
        return out;
    }

    private void offerContextLine(String phase, String modelName, List<Msg> messages) {
        try {
            int seq = contextSeq.incrementAndGet();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("t", "ctx");
            root.put("phase", phase);
            root.put("seq", seq);
            root.put("model", modelName != null ? modelName : "");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Msg m : messages) {
                rows.add(msgToDebugRow(m));
            }
            root.put("messages", rows);
            String flat = truncateIfNeeded(buildFlatTranscript(messages), CTX_FLAT_MAX_CHARS);
            root.put("flat", flat);
            pendingContextSseLines.offer(SSE_JSON.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("Failed to serialize model context for SSE: {}", e.getMessage());
        }
    }

    private static Map<String, Object> msgToDebugRow(Msg m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", m.getRole().name());
        if (m.getName() != null) {
            row.put("name", m.getName());
        }
        List<Object> parts = new ArrayList<>();
        for (ContentBlock b : m.getContent()) {
            parts.add(contentBlockToDebugMap(b));
        }
        row.put("content", parts);
        return row;
    }

    private static Object contentBlockToDebugMap(ContentBlock b) {
        if (b instanceof TextBlock tb) {
            return Map.of(
                    "type",
                    "text",
                    "text",
                    truncateIfNeeded(
                            tb.getText() != null ? tb.getText() : "", CTX_FIELD_MAX_CHARS));
        }
        if (b instanceof ThinkingBlock th) {
            return Map.of(
                    "type",
                    "thinking",
                    "thinking",
                    truncateIfNeeded(
                            th.getThinking() != null ? th.getThinking() : "", CTX_FIELD_MAX_CHARS));
        }
        if (b instanceof ToolUseBlock tu) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "tool_use");
            m.put("id", tu.getId());
            m.put("name", tu.getName());
            m.put("input", tu.getInput() != null ? tu.getInput() : Map.of());
            String raw = tu.getContent();
            if (raw != null && !raw.isEmpty()) {
                m.put("raw", truncateIfNeeded(raw, CTX_FIELD_MAX_CHARS));
            }
            return m;
        }
        if (b instanceof ToolResultBlock tr) {
            return Map.of(
                    "type",
                    "tool_result",
                    "name",
                    tr.getName() != null ? tr.getName() : "",
                    "output",
                    truncateIfNeeded(flattenToolOutput(tr), CTX_FIELD_MAX_CHARS));
        }
        return Map.of(
                "type", "other", "repr", truncateIfNeeded(String.valueOf(b), CTX_FIELD_MAX_CHARS));
    }

    private static String buildFlatTranscript(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            sb.append("--- ").append(m.getRole().name());
            if (m.getName() != null) {
                sb.append(" (").append(m.getName()).append(')');
            }
            sb.append(" ---\n");
            for (ContentBlock b : m.getContent()) {
                appendContentBlockForFlat(sb, b);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendContentBlockForFlat(StringBuilder sb, ContentBlock o) {
        if (o instanceof TextBlock tb) {
            sb.append(tb.getText() != null ? tb.getText() : "");
        } else if (o instanceof ThinkingBlock th) {
            sb.append(th.getThinking() != null ? th.getThinking() : "");
        } else if (o instanceof ToolUseBlock tu) {
            sb.append("[tool_use ")
                    .append(tu.getName())
                    .append(" id=")
                    .append(tu.getId())
                    .append("] ");
            sb.append(tu.getInput() != null ? tu.getInput().toString() : "{}");
            String raw = tu.getContent();
            if (raw != null && !raw.isEmpty()) {
                sb.append(" raw=").append(raw);
            }
            sb.append('\n');
        } else if (o instanceof ToolResultBlock tr) {
            sb.append("[tool_result ").append(tr.getName()).append("]\n");
            sb.append(flattenToolOutput(tr));
            sb.append('\n');
        } else {
            sb.append(o);
        }
    }

    private static String truncateIfNeeded(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n...(truncated)";
    }

    /**
     * Maps a stream event to one SSE line: a JSON object {@code {t: kind, ...}} so the UI can
     * interleave thinking, answer text, and tool results, then render Markdown.
     */
    private String mapEventToString(Event event) {
        try {
            if (event.getType() == EventType.AGENT_RESULT) {
                Msg msg = event.getMessage();
                if (msg != null
                        && msg.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED) {
                    isPaused.set(true);
                    return SSE_JSON.writeValueAsString(Map.of("t", "paused"));
                }
                return "";
            }

            if (event.getType() == EventType.TOOL_RESULT) {
                Msg msg = event.getMessage();
                if (msg == null) {
                    return "";
                }
                List<ToolResultBlock> blocks = msg.getContentBlocks(ToolResultBlock.class);
                if (blocks.isEmpty()) {
                    return "";
                }
                ToolResultBlock tr = blocks.get(0);
                String name = tr.getName() != null ? tr.getName() : "";
                String body = flattenToolOutput(tr);
                Map<String, Object> payload = new LinkedHashMap<>(4);
                payload.put("t", "tool");
                payload.put("n", name);
                payload.put("d", body);
                Map<String, Object> inputSnapshot = pendingToolInputs.poll();
                if (inputSnapshot != null && !inputSnapshot.isEmpty()) {
                    payload.put("i", inputSnapshot);
                }
                return SSE_JSON.writeValueAsString(payload);
            }

            if (event.isLast()) {
                return "";
            }

            Msg msg = event.getMessage();
            if (msg == null) {
                return "";
            }

            List<ThinkingBlock> thinkingBlocks = msg.getContentBlocks(ThinkingBlock.class);
            if (!thinkingBlocks.isEmpty()) {
                String delta = thinkingBlocks.get(0).getThinking();
                if (delta == null || delta.isEmpty()) {
                    return "";
                }
                return SSE_JSON.writeValueAsString(Map.of("t", "think", "d", delta));
            }

            List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
            if (!textBlocks.isEmpty()) {
                String delta = textBlocks.get(0).getText();
                if (delta == null || delta.isEmpty()) {
                    return "";
                }
                return SSE_JSON.writeValueAsString(Map.of("t", "text", "d", delta));
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to encode SSE chunk: {}", e.getMessage());
            return "";
        }
    }

    private static String flattenToolOutput(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock o : block.getOutput()) {
            appendContentBlock(sb, o);
        }
        return sb.toString();
    }

    private static void appendContentBlock(StringBuilder sb, ContentBlock o) {
        if (o instanceof TextBlock tb) {
            sb.append(tb.getText());
        } else if (o instanceof ThinkingBlock th) {
            sb.append(th.getThinking());
        } else if (o instanceof ToolResultBlock tr) {
            sb.append(flattenToolOutput(tr));
        } else {
            sb.append(o);
        }
    }

    /**
     * Check if the agent is currently paused.
     */
    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * Request the agent to stop after the next plan tool execution.
     * This sets a flag that will cause the agent to pause after executing any plan-related tool.
     */
    public void requestStop() {
        log.info("User requested stop - will pause after next plan tool execution");
        stopRequested.set(true);
    }

    /**
     * Check if a stop has been requested.
     */
    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /**
     * Reset the agent, clearing all conversations and plans.
     */
    public void reset() {
        log.info("Resetting agent and clearing all data");
        isPaused.set(false);
        stopRequested.set(false);
        FileToolMock.clearStorage();
        initializeAgent();
        planService.broadcastPlanChange();
        log.info("Agent reset completed");
    }

    /**
     * Directory bound for {@link ReadFileTool} / {@link WriteFileTool}. Override with env
     * {@code PLAN_NOTEBOOK_WORKSPACE}; otherwise {@code ~/.agentscope/plan-notebook/workspace}.
     */
    private static String resolveWorkspaceRoot() {
        String override = System.getenv("PLAN_NOTEBOOK_WORKSPACE");
        Path root =
                override != null && !override.isEmpty()
                        ? Paths.get(override).toAbsolutePath().normalize()
                        : Paths.get(
                                        System.getProperty("user.home"),
                                        ".agentscope",
                                        "plan-notebook",
                                        "workspace")
                                .toAbsolutePath()
                                .normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create workspace directory: " + root, e);
        }
        log.info("Agent file tools restricted to workspace: {}", root);
        return root.toString();
    }
}
