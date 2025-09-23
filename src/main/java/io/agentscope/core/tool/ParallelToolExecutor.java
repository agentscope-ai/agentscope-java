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

import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executor for parallel tool calls following the Python AgentScope pattern.
 *
 * This class provides the infrastructure for executing multiple tools either
 * in parallel or sequentially, with proper error handling and result aggregation.
 * It follows similar patterns to the Python implementation, implemented with Reactor.
 */
public class ParallelToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolExecutor.class);

    private final Toolkit toolkit;
    private final ExecutorService executorService;
    private final boolean useCustomExecutor;

    /**
     * Create a parallel tool executor with the given toolkit and executor service.
     *
     * @param toolkit Toolkit containing the tools to execute
     * @param executorService Custom executor service for tool execution
     */
    public ParallelToolExecutor(Toolkit toolkit, ExecutorService executorService) {
        this.toolkit = toolkit;
        this.executorService = executorService;
        this.useCustomExecutor = true;
    }

    /**
     * Create a parallel tool executor with the given toolkit using default executor.
     *
     * @param toolkit Toolkit containing the tools to execute
     */
    public ParallelToolExecutor(Toolkit toolkit) {
        this.toolkit = toolkit;
        // Use cached thread pool for I/O-bound tool operations
        this.executorService =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread thread = new Thread(r, "tool-executor");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.useCustomExecutor = false;
    }

    /**
     * Execute tool calls either in parallel or sequentially using Reactor.
     *
     * @param toolCalls List of tool calls to execute
     *     * @param parallel Whether to execute tools in parallel or sequentially
     * @return Mono containing list of tool responses maintaining original order
     */
    public Mono<List<ToolResponse>> executeTools(List<ToolUseBlock> toolCalls, boolean parallel) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }
        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);
        List<Mono<ToolResponse>> monos =
                toolCalls.stream().map(this::executeToolCallReactive).toList();
        if (parallel) {
            return Flux.merge(monos).collectList();
        }
        return Flux.concat(monos).collectList();
    }

    private Mono<ToolResponse> executeToolCallReactive(ToolUseBlock toolCall) {
        return Mono.fromCallable(() -> toolkit.callTool(toolCall))
                .subscribeOn(Schedulers.fromExecutor(executorService))
                .map(
                        toolResponse ->
                                new ToolResponse(
                                        toolResponse.getContent(),
                                        toolResponse.getMetadata(),
                                        toolResponse.isStream(),
                                        toolResponse.isLast(),
                                        toolResponse.isInterrupted(),
                                        toolCall.getId()))
                .onErrorResume(
                        e -> {
                            if (e instanceof RuntimeException
                                    && e.getCause() instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                logger.info("Tool call interrupted: {}", toolCall.getName());
                                return Mono.just(ToolResponse.interrupted());
                            }
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            return Mono.just(
                                    ToolResponse.error("Tool execution failed: " + e.getMessage()));
                        });
    }

    /**
     * Execute multiple tool calls with timeout support using Reactor.
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute in parallel
     * @param timeoutMs Timeout in milliseconds
     * @return Mono with list of tool responses, with timeout errors for incomplete calls
     */
    public Mono<List<ToolResponse>> executeToolsWithTimeout(
            List<ToolUseBlock> toolCalls, boolean parallel, long timeoutMs) {
        return executeTools(toolCalls, parallel)
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .onErrorResume(
                        java.util.concurrent.TimeoutException.class,
                        e -> {
                            logger.warn("Tool execution timed out after {} ms", timeoutMs);
                            return Mono.just(
                                    toolCalls.stream()
                                            .map(
                                                    tc ->
                                                            ToolResponse.error(
                                                                    "Tool execution timed out"))
                                            .collect(java.util.stream.Collectors.toList()));
                        })
                .onErrorResume(
                        throwable -> {
                            logger.warn("Tool execution failed", throwable);
                            return Mono.just(
                                    toolCalls.stream()
                                            .map(
                                                    tc ->
                                                            ToolResponse.error(
                                                                    "Tool execution failed: "
                                                                            + throwable
                                                                                    .getMessage()))
                                            .collect(java.util.stream.Collectors.toList()));
                        });
    }

    /**
     * Shutdown the executor service if it was created internally.
     */
    public void shutdown() {
        if (!useCustomExecutor) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }
    }

    /**
     * Get statistics about the executor service.
     *
     * @return Map containing executor statistics
     */
    public java.util.Map<String, Object> getExecutorStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        if (executorService instanceof ThreadPoolExecutor tpe) {
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
