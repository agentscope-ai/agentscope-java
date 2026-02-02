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

package io.agentscope.core.training.runner;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.backend.TrinityModelAdapter;
import io.agentscope.core.training.backend.dto.FeedbackRequest;
import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SelectionDecision;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Training Router Hook
 *
 * <p>Core training router Hook implementing shadow traffic training architecture with <b>fully automated Task/Run ID management</b>
 *
 * <p><b>Automation Flow:</b>
 * <ol>
 *   <li>PreCallEvent: No modifications, let request execute normally</li>
 *   <li>PostCallEvent: Filter and judge, run shadow Agent asynchronously</li>
 *   <li><b>Auto-generate Task ID</b>: Independent task identifier for each request</li>
 *   <li><b>Auto-allocate Run ID</b>: Execution count for the same Task</li>
 *   <li>Shadow Agent: Replace model with TrinityModelAdapter</li>
 *   <li><b>Auto-collect msg_ids</b>: Extract from Trinity API responses</li>
 *   <li>Calculate Reward: Based on shadow Agent execution results</li>
 *   <li>Submit Feedback: Batch submit all training data</li>
 * </ol>
 *
 * <p><b>Key Design:</b>
 * <ul>
 *   <li>100% requests use production model, ensuring service quality</li>
 *   <li>Sample portion of requests run shadow Agent asynchronously, no user impact</li>
 *   <li>Shadow Agent uses training model, collects training data</li>
 *   <li><b>Completely transparent to users</b>: All IDs managed automatically</li>
 * </ul>
 *
 * @see TaskIdGenerator
 * @see RunRegistry
 * @see RunExecutionContext
 */
public class TrainingRouter implements Hook {
    private static final Logger logger = LoggerFactory.getLogger(TrainingRouter.class);

    private final TrainingConfig config;
    private final TrinityClient trinityClient;
    private final RewardCalculator rewardCalculator;
    private final TrainingSelectionStrategy selectionStrategy;
    private final Scheduler asyncScheduler;

    // Save PreCallEvent inputs for retrieval during PostCallEvent
    private final Map<String, List<Msg>> callInputs = new ConcurrentHashMap<>();

    public TrainingRouter(
            TrainingConfig config,
            TrinityClient trinityClient,
            RewardCalculator rewardCalculator,
            TrainingSelectionStrategy selectionStrategy) {
        this.config = config;
        this.trinityClient = trinityClient;
        this.rewardCalculator = rewardCalculator;
        this.selectionStrategy = selectionStrategy;
        this.asyncScheduler =
                Schedulers.newBoundedElastic(
                        config.getShadowPoolSize(),
                        config.getShadowPoolCapacity(),
                        "training-shadow");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent) {
            PreCallEvent e = (PreCallEvent) event;
            return handlePreCall(e).thenReturn((T) e);
        } else if (event instanceof PostCallEvent) {
            PostCallEvent e = (PostCallEvent) event;
            return handlePostCall(e).thenReturn((T) e);
        } else {
            return Mono.just(event);
        }
    }

    @Override
    public int priority() {
        return 500; // Low priority, runs after business logic
    }

    /**
     * Handle PreCallEvent
     * Save input messages for use in PostCallEvent
     */
    private Mono<Void> handlePreCall(PreCallEvent event) {
        return Mono.fromRunnable(
                () -> {
                    String agentId = event.getAgent().getAgentId();
                    callInputs.put(agentId, event.getInputMessages());
                    logger.debug("Saved input messages for agent: {}", agentId);
                });
    }

    /**
     * Handle PostCallEvent
     * <b>Auto-generate Task ID, allocate Run ID, run shadow Agent asynchronously</b>
     */
    private Mono<Void> handlePostCall(PostCallEvent event) {
        return Mono.deferContextual(
                ctx -> {
                    String agentId = event.getAgent().getAgentId();
                    String agentName = event.getAgent().getName();

                    // ✅ Prevent shadow agent from triggering training (avoid recursive loop)
                    if (agentName != null && agentName.contains("-shadow")) {
                        logger.trace(
                                "Skipping training for shadow agent: {} (prevents recursive"
                                        + " training)",
                                agentName);
                        callInputs.remove(agentId); // Clean up input
                        return Mono.empty();
                    }

                    // Get input messages
                    List<Msg> inputs = callInputs.remove(agentId);
                    if (inputs == null) {
                        logger.warn("No input messages found for agent: {}", agentId);
                        return Mono.empty();
                    }

                    // Check if training is needed (using unified selection strategy)
                    logger.info(
                            "Checking training selection: agent={}, strategy={}, threadId={}",
                            event.getAgent().getName(),
                            selectionStrategy.getClass().getSimpleName(),
                            Thread.currentThread().getId());

                    SelectionDecision decision =
                            selectionStrategy.shouldSelect(
                                    event.getAgent(), inputs, event.getFinalMessage(), ctx);

                    if (!decision.shouldTrain()) {
                        logger.info(
                                "Skip training: agent={}, reason={}",
                                event.getAgent().getName(),
                                decision.getReason());
                        return Mono.empty();
                    }

                    // ✅ Get or generate Task ID
                    // Prioritize user-specified taskId (supports multiple runs of same task)
                    // Otherwise auto-generate new taskId
                    String taskId =
                            decision.getMetadata() != null
                                            && decision.getMetadata().containsKey("taskId")
                                    ? (String) decision.getMetadata().get("taskId")
                                    : TaskIdGenerator.generate();

                    if (decision.getMetadata() != null
                            && decision.getMetadata().containsKey("taskId")) {
                        logger.debug(
                                "Using user-specified Task ID: {} for agent: {}",
                                taskId,
                                event.getAgent().getName());
                    } else {
                        logger.debug(
                                "Auto-generated Task ID: {} for agent: {}",
                                taskId,
                                event.getAgent().getName());
                    }

                    // ✅ Get repeatTime configuration (how many times each task runs)
                    int repeatTime = config.getRepeatTime();

                    logger.info(
                            "Training triggered for task {}: repeatTime={}, reason={}, labels={}",
                            taskId,
                            repeatTime,
                            decision.getReason(),
                            decision.getLabels());

                    // ✅ Loop multiple runs (using same taskId, runId auto-increments)
                    for (int i = 0; i < repeatTime; i++) {
                        // Allocate new runId for each iteration
                        String currentRunId = RunRegistry.allocateRunId(taskId);
                        RunExecutionContext currentContext =
                                RunExecutionContext.create(taskId, currentRunId);

                        logger.info(
                                "Starting run {}/{} for task {}: {}",
                                i + 1,
                                repeatTime,
                                taskId,
                                currentContext);

                        // Check if this is the last run
                        boolean isLastRun = (i == repeatTime - 1);

                        // Run shadow Agent asynchronously (non-blocking)
                        runShadowAgent(event.getAgent(), inputs, currentContext, decision)
                                .subscribeOn(asyncScheduler)
                                .doFinally(
                                        signal -> {
                                            // Clean up resources only after last run
                                            if (isLastRun) {
                                                RunRegistry.cleanup(taskId);
                                                logger.debug(
                                                        "Cleaned up registry for Task: {} after {}"
                                                                + " runs",
                                                        taskId,
                                                        repeatTime);
                                            }
                                        })
                                .subscribe(
                                        null,
                                        error ->
                                                logger.error(
                                                        "Shadow agent failed for {}",
                                                        currentContext,
                                                        error));
                    }

                    return Mono.empty();
                });
    }

    /**
     * Run shadow Agent
     *
     * <p><b>Fully automated:</b>
     * <ul>
     *   <li>Auto-create TrinityModelAdapter (associated with RunExecutionContext)</li>
     *   <li>Auto-collect msg_ids into RunExecutionContext</li>
     *   <li>Auto-calculate Reward (based on execution results)</li>
     *   <li>Auto-submit Feedback (including Task ID, Run ID, msg_ids)</li>
     * </ul>
     */
    private Mono<Void> runShadowAgent(
            Agent productionAgent,
            List<Msg> inputs,
            RunExecutionContext executionContext,
            SelectionDecision decision) {
        return Mono.defer(
                () -> {
                    logger.info(
                            "Starting shadow agent for {}: agent={}, labels={}",
                            executionContext,
                            productionAgent.getName(),
                            decision.getLabels());

                    try {
                        // ✅ 1. Create Trinity model (auto-associate with RunExecutionContext)
                        TrinityModelAdapter trinityModel =
                                TrinityModelAdapter.builder()
                                        .baseUrl(trinityClient.getEndpoint() + "/v1")
                                        .modelName(config.getModelName())
                                        .apiKey(config.getTrinityApiKey())
                                        .executionContext(
                                                executionContext) // ← Pass execution context
                                        .build();

                        // 2. Clone Agent and replace model
                        Agent shadowAgent =
                                AgentCloner.cloneWithModel(productionAgent, trinityModel);

                        logger.debug(
                                "Shadow agent created for {}: {}",
                                executionContext,
                                shadowAgent.getName());

                        // ✅ 3. Execute shadow Agent
                        shadowAgent.call(inputs).block();

                        logger.info(
                                "Shadow agent completed: {}, duration={}ms",
                                executionContext,
                                executionContext.getDuration());

                        // ✅ 4. Auto-get msg_ids from RunExecutionContext
                        List<String> msgIds = executionContext.getMsgIds();

                        logger.info("Collected {} msg_ids for {}", msgIds.size(), executionContext);

                        if (msgIds.isEmpty()) {
                            logger.warn("No msg_ids collected for {}", executionContext);
                            return Mono.empty();
                        }

                        // ✅ 5. Calculate reward using RewardCalculator
                        double reward = rewardCalculator.calculate(shadowAgent);

                        logger.info("Calculated reward: {} for {}", reward, executionContext);

                        // ✅ 6. Auto-submit feedback (Task ID, Run ID, msg_ids auto-filled)
                        return trinityClient
                                .feedback(
                                        FeedbackRequest.builder()
                                                .taskId(executionContext.getTaskId())
                                                .runId(executionContext.getRunId())
                                                .msgIds(msgIds)
                                                .reward(reward)
                                                .build())
                                .doOnSuccess(
                                        v -> {
                                            logger.info(
                                                    "Feedback submitted successfully for {}",
                                                    executionContext);

                                            // ✅ 8. Save execution context to registry (for later
                                            // queries)
                                            TaskExecutionRegistry.register(executionContext);
                                            logger.debug(
                                                    "Registered context: {}, total runs: {}",
                                                    executionContext,
                                                    TaskExecutionRegistry.getRunCount(
                                                            executionContext.getTaskId()));
                                        })
                                .then();

                    } catch (Exception e) {
                        logger.error("Failed to create shadow agent for {}", executionContext, e);
                        return Mono.empty();
                    }
                });
    }
}
