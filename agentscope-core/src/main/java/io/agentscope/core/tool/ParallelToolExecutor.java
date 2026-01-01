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
package io.agentscope.core.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.util.ExceptionUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Executor for parallel tool execution using Project Reactor.
 *
 * <p>This class provides the infrastructure for executing multiple tools either
 * in parallel or sequentially, with proper error handling and result aggregation.
 * Implemented with Reactor for reactive programming.
 *
 * <p>Execution modes:
 * - Default: Uses Reactor's Schedulers.boundedElastic() for asynchronous I/O-bound operations
 * - Custom: Uses user-provided ExecutorService
 */
class ParallelToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolExecutor.class);

    private final Toolkit toolkit;
    private final ExecutorService executorService;

    /**
     * Create a parallel tool executor with the given toolkit and custom executor service.
     *
     * @param toolkit Toolkit containing the tools to execute
     * @param executorService Custom executor service for tool execution
     */
    public ParallelToolExecutor(Toolkit toolkit, ExecutorService executorService) {
        this.toolkit = toolkit;
        this.executorService = executorService;
    }

    /**
     * Create a parallel tool executor with the given toolkit using Reactor Schedulers.
     * This is the recommended approach for most use cases.
     *
     * @param toolkit Toolkit containing the tools to execute
     */
    public ParallelToolExecutor(Toolkit toolkit) {
        this.toolkit = toolkit;
        this.executorService = null;
    }

    /**
     * Execute tool calls with concurrency control, timeout, and retry.
     *
     * <p>This is the only place that handles parallel/sequential execution, timeout, and retry
     * logic.
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute tools in parallel or sequentially
     * @param executionConfig Execution configuration for timeout and retry
     * @param agent The agent making the calls (may be null)
     * @param agentContext The agent-level tool execution context (may be null)
     * @return Mono containing list of tool responses
     */
    public Mono<List<ToolResultBlock>> executeTools(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }

        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);

        // Map each tool call to an execution Mono
        List<Mono<ToolResultBlock>> monos =
                toolCalls.stream()
                        .map(
                                toolCall ->
                                        executeWithInfrastructure(
                                                toolCall, executionConfig, agent, agentContext))
                        .toList();

        // Parallel or sequential execution
        if (parallel) {
            return Flux.merge(monos).collectList();
        }
        return Flux.concat(monos).collectList();
    }

    /**
     * Execute a single tool call with infrastructure concerns (scheduling, timeout, retry).
     *
     * <p>This wraps the core business logic with cross-cutting concerns.
     *
     * @param toolCall The tool call to execute
     * @param executionConfig Execution configuration for timeout and retry
     * @param agent The agent making the call (may be null)
     * @param agentContext The agent-level tool execution context (may be null)
     * @return Mono containing execution result
     */
    private Mono<ToolResultBlock> executeWithInfrastructure(
            ToolUseBlock toolCall,
            ExecutionConfig executionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        // Build tool call parameter with agent context
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .agent(agent)
                        .context(agentContext)
                        .build();

        // Get core execution from Toolkit (business logic)
        Mono<ToolResultBlock> execution = toolkit.callTool(param);

        // Apply infrastructure layers
        execution = applyScheduling(execution);
        execution = applyTimeout(execution, executionConfig, toolCall);
        execution = applyRetry(execution, executionConfig, toolCall);

        // Add tool metadata and error handling
        return execution
                .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            String errorMsg = ExceptionUtils.getErrorMessage(e);
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    /**
     * Apply thread scheduling to execution.
     *
     * @param execution The execution Mono
     * @return Execution with scheduling applied
     */
    private Mono<ToolResultBlock> applyScheduling(Mono<ToolResultBlock> execution) {
        if (executorService == null) {
            return execution.subscribeOn(Schedulers.boundedElastic());
        } else {
            return execution.subscribeOn(Schedulers.fromExecutor(executorService));
        }
    }

    /**
     * Apply timeout to execution if configured.
     *
     * @param execution The execution Mono
     * @param config Execution configuration
     * @param toolCall The tool call being executed
     * @return Execution with timeout applied
     */
    private Mono<ToolResultBlock> applyTimeout(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getTimeout() == null) {
            return execution;
        }

        Duration timeout = config.getTimeout();
        logger.debug("Applied timeout: {} for tool: {}", timeout, toolCall.getName());

        return execution.timeout(
                timeout,
                Mono.error(new RuntimeException("Tool execution timeout after " + timeout)));
    }

    /**
     * Apply retry logic to execution if configured.
     *
     * @param execution The execution Mono
     * @param config Execution configuration
     * @param toolCall The tool call being executed
     * @return Execution with retry applied
     */
    private Mono<ToolResultBlock> applyRetry(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getMaxAttempts() == null || config.getMaxAttempts() <= 1) {
            return execution;
        }

        Integer maxAttempts = config.getMaxAttempts();
        Duration initialBackoff =
                config.getInitialBackoff() != null
                        ? config.getInitialBackoff()
                        : Duration.ofSeconds(1);
        Duration maxBackoff =
                config.getMaxBackoff() != null ? config.getMaxBackoff() : Duration.ofSeconds(10);
        Predicate<Throwable> retryOn =
                config.getRetryOn() != null ? config.getRetryOn() : error -> true;

        Retry retrySpec =
                Retry.backoff(maxAttempts - 1, initialBackoff)
                        .maxBackoff(maxBackoff)
                        .jitter(0.5)
                        .filter(retryOn)
                        .doBeforeRetry(
                                signal ->
                                        logger.warn(
                                                "Retrying tool call (attempt {}/{}) due to: {}",
                                                signal.totalRetriesInARow() + 1,
                                                maxAttempts - 1,
                                                signal.failure().getMessage()));

        logger.debug(
                "Applied retry config: maxAttempts={} for tool: {}",
                maxAttempts,
                toolCall.getName());

        return execution.retryWhen(retrySpec);
    }

    /**
     * Get statistics about the executor configuration for debugging.
     *
     * @return Map containing executor information
     */
    public java.util.Map<String, Object> getExecutorStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        if (executorService == null) {
            stats.put("executorType", "Reactor Schedulers");
            stats.put("scheduler", "boundedElastic");
        } else if (executorService instanceof ThreadPoolExecutor tpe) {
            stats.put("executorType", "ThreadPoolExecutor");
            stats.put("activeThreads", tpe.getActiveCount());
            stats.put("corePoolSize", tpe.getCorePoolSize());
            stats.put("maximumPoolSize", tpe.getMaximumPoolSize());
            stats.put("poolSize", tpe.getPoolSize());
            stats.put("taskCount", tpe.getTaskCount());
            stats.put("completedTaskCount", tpe.getCompletedTaskCount());
        } else {
            stats.put("executorType", executorService.getClass().getSimpleName());
            stats.put("isShutdown", executorService.isShutdown());
            stats.put("isTerminated", executorService.isTerminated());
        }

        return stats;
    }
}
