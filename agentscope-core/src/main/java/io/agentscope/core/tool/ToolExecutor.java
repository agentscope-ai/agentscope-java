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
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.ExceptionUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Unified executor for tool execution with infrastructure concerns.
 *
 * <p>This class consolidates all tool execution logic including:
 * <ul>
 *   <li>Single and batch tool execution</li>
 *   <li>Parallel/sequential execution control</li>
 *   <li>Timeout and retry handling</li>
 *   <li>Thread scheduling</li>
 *   <li>Schema validation before execution</li>
 * </ul>
 *
 * <p>Execution modes:
 * <ul>
 *   <li>Default: Uses Reactor's Schedulers.boundedElastic() for async I/O operations</li>
 *   <li>Custom: Uses user-provided ExecutorService</li>
 * </ul>
 */
class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final Toolkit toolkit;
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolkitConfig config;
    private final ToolMethodInvoker methodInvoker;
    private final ExecutorService executorService;
    private BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback;

    /**
     * Create a tool executor with Reactor Schedulers (recommended).
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config,
            ToolMethodInvoker methodInvoker) {
        this(toolkit, toolRegistry, groupManager, config, methodInvoker, null);
    }

    /**
     * Create a tool executor with custom executor service.
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config,
            ToolMethodInvoker methodInvoker,
            ExecutorService executorService) {
        this.toolkit = toolkit;
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.config = config;
        this.methodInvoker = methodInvoker;
        this.executorService = executorService;
    }

    /**
     * Set chunk callback for streaming tool responses.
     */
    void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.chunkCallback = callback;
        methodInvoker.setChunkCallback(callback);
    }

    // ==================== Single Tool Execution ====================

    /**
     * Execute a single tool call with full infrastructure support.
     *
     * @param param Tool call parameters
     * @return Mono containing execution result
     */
    Mono<ToolResultBlock> execute(ToolCallParam param) {
        return TracerRegistry.get().callTool(this.toolkit, param, () -> executeCore(param));
    }

    /**
     * Core tool execution logic.
     *
     * <p>This method handles:
     * <ul>
     *   <li>Tool lookup and validation</li>
     *   <li>Group activation check</li>
     *   <li>Parameter merging (preset + input)</li>
     *   <li>Context merging</li>
     *   <li>Schema validation</li>
     *   <li>Actual tool invocation</li>
     * </ul>
     */
    private Mono<ToolResultBlock> executeCore(ToolCallParam param) {
        ToolUseBlock toolCall = param.getToolUseBlock();
        AgentTool tool = toolRegistry.getTool(toolCall.getName());

        if (tool == null) {
            return Mono.just(ToolResultBlock.error("Tool not found: " + toolCall.getName()));
        }

        // Check group activation
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolCall.getName());
        if (registered != null) {
            String groupName = registered.getGroupName();
            if (!groupManager.isInActiveGroup(groupName)) {
                String errorMsg =
                        String.format(
                                "Unauthorized tool call: '%s' is not available",
                                toolCall.getName());
                logger.warn(errorMsg);
                return Mono.just(ToolResultBlock.error(errorMsg));
            }
        }

        // Validate input against schema
        String validationError =
                ToolValidator.validateInput(toolCall.getContent(), tool.getParameters());
        if (validationError != null) {
            String errorMsg =
                    String.format(
                            "Parameter validation failed for tool '%s': %s\n"
                                    + "Please correct the parameters and try again.",
                            toolCall.getName(), validationError);
            logger.debug(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
        }

        // Merge context
        ToolExecutionContext toolkitContext = config.getDefaultContext();
        ToolExecutionContext finalContext =
                ToolExecutionContext.merge(param.getContext(), toolkitContext);

        // Create emitter for streaming
        ToolEmitter toolEmitter = new DefaultToolEmitter(toolCall, chunkCallback);

        // Merge preset parameters with input
        Map<String, Object> mergedInput = new HashMap<>();
        if (registered != null) {
            mergedInput.putAll(registered.getPresetParameters());
        }
        if (param.getInput() != null && !param.getInput().isEmpty()) {
            mergedInput.putAll(param.getInput());
        } else if (toolCall.getInput() != null) {
            mergedInput.putAll(toolCall.getInput());
        }

        // Build final execution param
        ToolCallParam executionParam =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(mergedInput)
                        .agent(param.getAgent())
                        .context(finalContext)
                        .emitter(toolEmitter)
                        .build();

        return tool.callAsync(executionParam)
                .onErrorResume(
                        ToolSuspendException.class,
                        e -> {
                            // Convert ToolSuspendException to suspended result
                            logger.debug(
                                    "Tool '{}' suspended: {}",
                                    toolCall.getName(),
                                    e.getReason() != null ? e.getReason() : "no reason");
                            return Mono.just(ToolResultBlock.suspended(toolCall, e));
                        })
                .onErrorResume(
                        e -> {
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    // ==================== Batch Tool Execution ====================

    /**
     * Execute multiple tool calls with concurrency control, timeout, and retry.
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute in parallel
     * @param executionConfig Execution configuration
     * @param agent The agent making the calls (may be null)
     * @param agentContext The agent-level context (may be null)
     * @return Mono containing list of results
     */
    Mono<List<ToolResultBlock>> executeAll(
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
     * Execute a single tool call with infrastructure (scheduling, timeout, retry).
     */
    private Mono<ToolResultBlock> executeWithInfrastructure(
            ToolUseBlock toolCall,
            ExecutionConfig executionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        // Build tool call parameter
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .agent(agent)
                        .context(agentContext)
                        .build();

        // Get core execution
        Mono<ToolResultBlock> execution = execute(param);

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

    // ==================== Infrastructure Methods ====================

    private Mono<ToolResultBlock> applyScheduling(Mono<ToolResultBlock> execution) {
        if (executorService == null) {
            return execution.subscribeOn(Schedulers.boundedElastic());
        }
        return execution.subscribeOn(Schedulers.fromExecutor(executorService));
    }

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
                                                signal.failure().getMessage(),
                                                signal.failure()));

        logger.debug(
                "Applied retry config: maxAttempts={} for tool: {}",
                maxAttempts,
                toolCall.getName());

        return execution.retryWhen(retrySpec);
    }
}
