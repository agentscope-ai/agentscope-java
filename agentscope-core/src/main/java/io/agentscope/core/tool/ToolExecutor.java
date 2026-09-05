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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.ExceptionUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
    private final ExecutorService executorService;
    private BiConsumer<ToolUseBlock, ToolResultBlock> userChunkCallback;

    /**
     * Create a tool executor with Reactor Schedulers (recommended).
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config) {
        this(toolkit, toolRegistry, groupManager, config, null);
    }

    /**
     * Create a tool executor with custom executor service.
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config,
            ExecutorService executorService) {
        this.toolkit = toolkit;
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.config = config;
        this.executorService = executorService;
    }

    /**
     * Set the user-defined chunk callback for streaming tool responses.
     */
    void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.userChunkCallback = callback;
    }

    /**
     * Get the user-defined chunk callback.
     * Used by {@link Toolkit#copy()} to preserve user callbacks across a build-time isolation copy.
     */
    BiConsumer<ToolUseBlock, ToolResultBlock> getChunkCallback() {
        return this.userChunkCallback;
    }

    /**
     * Combine the user-defined and the per-call internal chunk callbacks.
     *
     * @param internal the per-call internal chunk callback (may be {@code null})
     */
    private BiConsumer<ToolUseBlock, ToolResultBlock> getEffectiveChunkCallback(
            BiConsumer<ToolUseBlock, ToolResultBlock> internal) {
        if (internal == null) {
            return userChunkCallback != null
                    ? (toolUse, chunk) ->
                            invokeChunkCallback("user", userChunkCallback, toolUse, chunk)
                    : null;
        }
        if (userChunkCallback == null) {
            return (toolUse, chunk) -> invokeChunkCallback("internal", internal, toolUse, chunk);
        }
        return (toolUse, chunk) -> {
            invokeChunkCallback("internal", internal, toolUse, chunk);
            invokeChunkCallback("user", userChunkCallback, toolUse, chunk);
        };
    }

    /**
     * Invoke a chunk callback without allowing it to block other callbacks.
     */
    private void invokeChunkCallback(
            String callbackType,
            BiConsumer<ToolUseBlock, ToolResultBlock> callback,
            ToolUseBlock toolUse,
            ToolResultBlock chunk) {
        try {
            callback.accept(toolUse, chunk);
        } catch (Exception e) {
            logger.warn(
                    "Chunk callback '{}' failed for tool '{}': {}",
                    callbackType,
                    toolUse.getName(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    e);
        }
    }

    // ==================== Single Tool Execution ====================

    /**
     * Execute a single tool call with full infrastructure support.
     *
     * @param param Tool call parameters
     * @return Mono containing execution result
     */
    Mono<ToolResultBlock> execute(ToolCallParam param) {
        return execute(param, ToolRequestConfig.NONE, null);
    }

    /**
     * Execute a single tool call with a per-call tool request config and a per-call internal chunk
     * callback. This is the single core entry point; the no-arg {@link #execute(ToolCallParam)}
     * delegates here with {@link ToolRequestConfig#NONE} and a {@code null} callback.
     */
    Mono<ToolResultBlock> execute(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        return TracerRegistry.get()
                .callTool(
                        this.toolkit,
                        param,
                        () -> executeCore(param, requestConfig, internalChunkCallback));
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
    private Mono<ToolResultBlock> executeCore(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        if (requestConfig == null) {
            requestConfig = ToolRequestConfig.NONE;
        }
        ToolUseBlock toolCall = param.getToolUseBlock();
        AgentTool tool = resolveTool(toolCall.getName(), requestConfig);

        if (tool == null) {
            return Mono.just(ToolResultBlock.error("Tool not found: " + toolCall.getName()));
        }

        // Check tool activation. This gate only applies to backend tools: an external tool injected
        // via the request config is ungrouped and always callable, so a same-named-but-inactive
        // backend tool must not reject it (the model was shown the external override and the call
        // must be honoured).
        boolean fromRequestConfig = requestConfig.overrides(toolCall.getName());
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolCall.getName());
        if (!fromRequestConfig
                && registered != null
                && !groupManager.isActiveTool(toolCall.getName(), resolveActiveGroups(param))) {
            String errorMsg =
                    String.format(
                            "Unauthorized tool call: '%s' is not available", toolCall.getName());
            logger.warn(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
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

        // External tool short-circuit: once availability and schema are validated, surface the call
        // without preset injection or local invocation. SchemaOnlyTool and any
        // @Tool(externalTool=true) method end up here.
        if (tool instanceof ToolBase tb && tb.isExternalTool()) {
            return Mono.just(ToolResultBlock.suspended(toolCall));
        }

        // Merge runtime context: param-level > toolkit default
        RuntimeContext runtimeContext = param.getRuntimeContext();
        @SuppressWarnings("deprecation")
        ToolExecutionContext toolkitDefault = config.getDefaultContext();
        if (runtimeContext == null && toolkitDefault != null) {
            runtimeContext = RuntimeContext.builder().toolExecutionContext(toolkitDefault).build();
        } else if (runtimeContext != null && toolkitDefault != null) {
            ToolExecutionContext merged =
                    ToolExecutionContext.merge(
                            runtimeContext.asToolExecutionContext(), toolkitDefault);
            runtimeContext =
                    RuntimeContext.builder(runtimeContext).toolExecutionContext(merged).build();
        }

        // Create emitter for streaming
        ToolEmitter toolEmitter =
                new DefaultToolEmitter(toolCall, getEffectiveChunkCallback(internalChunkCallback));

        // Merge input with preset parameters. Preset values win so framework-controlled
        // parameters remain immutable from the caller/LLM perspective.
        Map<String, Object> mergedInput = new HashMap<>();
        if (!param.getInput().isEmpty()) {
            mergedInput.putAll(param.getInput());
        } else if (!toolCall.getInput().isEmpty()) {
            mergedInput.putAll(toolCall.getInput());
        }
        if (registered != null) {
            mergedInput.putAll(registered.getPresetParameters());
        }

        // Build final execution param
        ToolCallParam executionParam =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(mergedInput)
                        .agent(param.getAgent())
                        .runtimeContext(runtimeContext)
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
                        })
                .switchIfEmpty(
                        Mono.just(
                                ToolResultBlock.error(
                                        "Tool execution failed: Tool completed without returning a"
                                                + " result")));
    }

    /**
     * Resolve a tool by name against the executor's own {@link #toolRegistry}. The merge policy
     * ("external → hide-backend → registry") lives in {@link
     * ToolRequestConfig#resolveTool(String, ToolRegistry, boolean)}; here it is applied against the
     * executor's <em>injected</em> {@link #toolRegistry}/{@link #config} (in production the same
     * object as the toolkit's, but injected so {@code ToolExecutor} stays testable).
     */
    private AgentTool resolveTool(String name, ToolRequestConfig requestConfig) {
        return (requestConfig != null ? requestConfig : ToolRequestConfig.NONE)
                .resolveTool(name, this.toolRegistry, this.config.isAllowToolDeletion());
    }

    /**
     * Resolve the per-call active groups from the tool call's runtime context, or {@code null} when
     * unavailable (falling back to the shared activation flags in {@code ToolGroupManager}).
     */
    private Collection<String> resolveActiveGroups(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        if (rc != null && rc.getAgentState() != null) {
            return rc.getAgentState().getToolContext().getActivatedGroups();
        }
        return null;
    }

    // ==================== Batch Tool Execution ====================

    /**
     * Execute multiple tool calls with concurrency control, timeout, and retry.
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute in parallel
     * @param executionConfig Execution configuration
     * @param agent The agent making the calls (may be null)
     * @param agentRuntimeContext The agent-level runtime context (may be null)
     * @return Mono containing list of results
     */
    Mono<List<ToolResultBlock>> executeAll(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext) {
        return executeAll(
                toolCalls,
                parallel,
                executionConfig,
                agent,
                agentRuntimeContext,
                ToolRequestConfig.NONE,
                null);
    }

    /**
     * Execute multiple tool calls with an explicit per-call tool request config and an internal
     * chunk callback, both threaded down to every single-tool execution.
     */
    Mono<List<ToolResultBlock>> executeAll(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }

        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);

        // Sequential mode: nothing to partition, run in declared order.
        if (!parallel) {
            List<Mono<ToolResultBlock>> monos =
                    toolCalls.stream()
                            .map(
                                    toolCall ->
                                            executeWithInfrastructure(
                                                    toolCall,
                                                    executionConfig,
                                                    agent,
                                                    agentRuntimeContext,
                                                    requestConfig,
                                                    internalChunkCallback))
                            .toList();
            return Flux.concat(monos).collectList();
        }

        // Parallel mode with concurrency-safe partitioning: contiguous runs of safe tools execute
        // concurrently (output order preserved via mergeSequential); unsafe tools (or unknown
        // legacy AgentTools we cannot inspect) form their own serial slots so two invocations
        // that share state never overlap.
        List<Flux<ToolResultBlock>> chunks = new ArrayList<>();
        List<Mono<ToolResultBlock>> safeBatch = new ArrayList<>();
        for (ToolUseBlock toolCall : toolCalls) {
            Mono<ToolResultBlock> mono =
                    executeWithInfrastructure(
                            toolCall,
                            executionConfig,
                            agent,
                            agentRuntimeContext,
                            requestConfig,
                            internalChunkCallback);
            if (isConcurrencySafe(toolCall, requestConfig)) {
                safeBatch.add(mono);
            } else {
                if (!safeBatch.isEmpty()) {
                    chunks.add(Flux.mergeSequential(safeBatch));
                    safeBatch = new ArrayList<>();
                }
                chunks.add(mono.flux());
            }
        }
        if (!safeBatch.isEmpty()) {
            chunks.add(Flux.mergeSequential(safeBatch));
        }
        return Flux.concat(chunks).collectList();
    }

    /**
     * Whether the tool backing {@code toolCall} can run in parallel with itself. Resolves the tool
     * through {@link #resolveTool} so the safety classification matches the tool that will actually
     * execute (external tools from the request config are schema-only/suspended and therefore safe).
     * Defaults to {@code true} for legacy {@link AgentTool} instances that do not extend {@link
     * ToolBase}, so existing tools keep their pre-2.0 concurrent behaviour.
     */
    private boolean isConcurrencySafe(ToolUseBlock toolCall, ToolRequestConfig requestConfig) {
        AgentTool tool = resolveTool(toolCall.getName(), requestConfig);
        if (tool instanceof ToolBase tb) {
            return tb.isConcurrencySafe();
        }
        return true;
    }

    /**
     * Execute a single tool call with infrastructure (scheduling, timeout, retry).
     */
    private Mono<ToolResultBlock> executeWithInfrastructure(
            ToolUseBlock toolCall,
            ExecutionConfig executionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        // Build tool call parameter
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .agent(agent)
                        .runtimeContext(agentRuntimeContext)
                        .build();

        // Get core execution
        Mono<ToolResultBlock> execution = execute(param, requestConfig, internalChunkCallback);

        // Apply infrastructure layers
        execution = applyScheduling(execution);
        execution = applyTimeout(execution, executionConfig, toolCall);
        execution = applyRetry(execution, executionConfig, toolCall);
        execution = applyShutdownGuard(execution);

        // Add tool metadata and error handling
        return execution
                .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            String errorMsg = ExceptionUtils.getErrorMessage(e);
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg)
                                            .withIdAndName(toolCall.getId(), toolCall.getName()));
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
                                                "Retrying tool call '{}' (attempt {}/{}) due to:"
                                                    + " {}. The previous attempt is cancelled and"
                                                    + " may still be consuming resources.",
                                                toolCall.getName(),
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

    /**
     * Race tool execution against the global shutdown timeout signal.
     * When the signal fires, the tool Mono is cancelled and an error is emitted,
     * which flows through {@code onErrorResume} into a normal {@code ToolResultBlock.error}.
     */
    private Mono<ToolResultBlock> applyShutdownGuard(Mono<ToolResultBlock> execution) {
        Mono<ToolResultBlock> shutdownGuard =
                GracefulShutdownManager.getInstance()
                        .getShutdownTimeoutSignal()
                        .then(
                                Mono.error(
                                        new RuntimeException(
                                                "Tool execution timeout due to system graceful"
                                                        + " shutdown.")));
        return Mono.firstWithSignal(execution, shutdownGuard);
    }
}
