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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final PlanService planService;

    private String apiKey;
    private ReActAgent agent;
    private InMemoryMemory memory;
    private Toolkit toolkit;

    // Track if agent is paused waiting for user to continue
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    // Track if user has requested to stop (will pause on next plan tool execution)
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

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
        memory = new InMemoryMemory();
        toolkit = new Toolkit();
        toolkit.registerTool(new FileToolMock());

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
                            String toolName = postActing.getToolUse().getName();
                            if (PLAN_TOOL_NAMES.contains(toolName)) {
                                // Only stop if user has requested it
                                if (stopRequested.compareAndSet(true, false)) {
                                    log.info(
                                            "Plan tool '{}' executed, pausing for user review",
                                            toolName);
                                    isPaused.set(true);
                                    postActing.stopAgent();
                                }
                            }
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
                                        .modelName("qwen3-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(memory)
                        .toolkit(toolkit)
                        .maxIters(50)
                        .hook(planChangeHook)
                        .planNotebook(planNotebook)
                        .build();
    }

    /**
     * Send a message to the agent and get streaming response.
     */
    public Flux<String> chat(String sessionId, String message) {
        // Clear paused state when user sends a new message
        isPaused.set(false);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return agent.stream(userMsg, createStreamOptions())
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::mapEventToString)
                .filter(text -> text != null && !text.isEmpty());
    }

    /**
     * Resume agent execution after user review.
     * This is called when user clicks "Continue" button after reviewing/modifying the plan.
     */
    public Flux<String> resume(String sessionId) {
        if (isPaused.compareAndSet(true, false)) {
            log.info("Resuming agent execution after user review");

            // Resume by calling agent.stream() with no input message
            return agent.stream(createStreamOptions())
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(this::mapEventToString)
                    .filter(text -> text != null && !text.isEmpty());
        } else {
            log.warn("Tried to resume but agent is not paused or already resuming");
            return Flux.just("Agent is not paused or is already resuming.");
        }
    }

    private StreamOptions createStreamOptions() {
        return StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .build();
    }

    /**
     * Map a stream event to a string for SSE output.
     */
    private String mapEventToString(Event event) {
        // Handle AGENT_RESULT events (agent execution ended)
        if (event.getType() == EventType.AGENT_RESULT) {
            Msg msg = event.getMessage();
            if (msg != null && msg.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED) {
                isPaused.set(true);
                return "[PAUSED]";
            }
            // Normal completion - content already streamed via REASONING chunks
            return "";
        }

        // Skip final accumulated messages in incremental mode to avoid duplicate output
        if (event.isLast()) {
            return "";
        }

        List<TextBlock> textBlocks = event.getMessage().getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            return textBlocks.get(0).getText();
        }
        return "";
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
}
