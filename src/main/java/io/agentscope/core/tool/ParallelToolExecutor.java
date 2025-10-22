/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executor for parallel tool calls following the Python AgentScope pattern.
 *
 * <p>This class provides the infrastructure for executing multiple tools either
 * in parallel or sequentially, with proper error handling and result aggregation.
 * It follows similar patterns to the Python implementation, implemented with Reactor.
 *
 * <p>Execution modes:
 * - Default: Uses Reactor's Schedulers.boundedElastic() for asynchronous I/O-bound operations
 * - Custom: Uses user-provided ExecutorService
 */
public class ParallelToolExecutor {

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
     * Execute tool calls either in parallel or sequentially using Reactor.
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute tools in parallel or sequentially
     * @return Mono containing list of tool responses
     */
    public Mono<List<ToolResultBlock>> executeTools(
            List<ToolUseBlock> toolCalls, boolean parallel) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }
        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);
        List<Mono<ToolResultBlock>> monos =
                toolCalls.stream().map(this::executeToolCallReactive).toList();
        if (parallel) {
            return Flux.merge(monos).collectList();
        }
        return Flux.concat(monos).collectList();
    }

    private Mono<ToolResultBlock> executeToolCallReactive(ToolUseBlock toolCall) {
        // Use the async API from toolkit
        Mono<ToolResultBlock> execution = toolkit.callToolAsync(toolCall);

        // Choose scheduler: Reactor's boundedElastic or custom executor
        // Only apply scheduler for synchronous tools (async tools manage their own threads)
        if (executorService == null) {
            execution = execution.subscribeOn(Schedulers.boundedElastic());
        } else {
            execution = execution.subscribeOn(Schedulers.fromExecutor(executorService));
        }

        return execution
                .map(toolResult -> toolResult.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            if (e instanceof RuntimeException
                                    && e.getCause() instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                logger.info("Tool call interrupted: {}", toolCall.getName());
                                return Mono.just(ToolResultBlock.interrupted());
                            }
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            // Extract the most informative error message
                            String errorMsg = getErrorMessage(e);
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    /**
     * Extract the most informative error message from an exception.
     * If the exception message is null, try to get the cause's message,
     * or fall back to the exception class name.
     *
     * @param throwable The exception
     * @return Error message string
     */
    private String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }

        // Try to get the message from the exception
        String message = throwable.getMessage();
        if (message != null && !message.isEmpty()) {
            return message;
        }

        // If no message, try to get the cause's message
        Throwable cause = throwable.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            return cause.getMessage();
        }

        // Fall back to the exception class name
        return throwable.getClass().getSimpleName();
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
