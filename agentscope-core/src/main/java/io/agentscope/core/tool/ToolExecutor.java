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
import io.agentscope.core.observation.ActionObservationException;
import io.agentscope.core.observation.ActionObservations;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.ExceptionUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
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

    /** Interceptor chain order: higher {@code order()} runs outermost; stable for equal orders. */
    private static final Comparator<ToolCallInterceptor> INTERCEPTOR_ORDER =
            Comparator.comparingInt(ToolCallInterceptor::order).reversed();

    private final Toolkit toolkit;
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolkitConfig config;
    private final ExecutorService executorService;
    private final List<ToolCallInterceptor> interceptors;
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
        this.interceptors = sortInterceptors(config);
    }

    /**
     * Sorts interceptors once at construction ({@link #INTERCEPTOR_ORDER}); empty stays empty.
     *
     * @param config the toolkit config carrying the registered interceptors, may be {@code null}
     * @return the sorted, immutable interceptor list (outermost first)
     */
    private static List<ToolCallInterceptor> sortInterceptors(ToolkitConfig config) {
        List<ToolCallInterceptor> registered =
                config != null ? config.getInterceptors() : List.of();
        return registered.isEmpty()
                ? List.of()
                : registered.stream().sorted(INTERCEPTOR_ORDER).toList();
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
        return execute(
                param,
                param.getRuntimeContext() == null
                        ? ToolRequestConfig.NONE
                        : param.getRuntimeContext().getToolRequestConfig(),
                null);
    }

    /**
     * Execute a single tool call with a per-call tool request config and a per-call internal chunk
     * callback. This is the single core entry point; the no-arg {@link #execute(ToolCallParam)}
     * resolves the request config from its explicit runtime context and uses no internal callback.
     */
    Mono<ToolResultBlock> execute(
            ToolCallParam param,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        return ActionObservations.observe(
                param, () -> executeUnobserved(param, requestConfig, internalChunkCallback));
    }

    private Mono<ToolResultBlock> executeUnobserved(
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
        final RuntimeContext effectiveRuntimeContext = runtimeContext;

        // Create emitter for streaming
        ToolEmitter toolEmitter =
                new DefaultToolEmitter(
                        toolCall,
                        (use, chunk) -> {
                            SessionRecorder recorder =
                                    SessionRecorder.from(param.getRuntimeContext());
                            if (recorder != null)
                                recorder.append(
                                        "tool/chunk",
                                        Map.of(
                                                "toolCallId",
                                                use.getId(),
                                                "actionId",
                                                recorder.actionId(use.getId()),
                                                "chunk",
                                                chunk));
                            var callback = getEffectiveChunkCallback(internalChunkCallback);
                            if (callback != null) callback.accept(use, chunk);
                        });

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

        SessionRecorder recorder = SessionRecorder.from(runtimeContext);

        // Zero interceptors: existing execution path, zero behavior change.
        if (interceptors.isEmpty()) {
            return applyResultErrorMapping(
                    dispatchRecord(recorder, executionParam, toolCall, mergedInput)
                            .then(Mono.defer(() -> tool.callAsync(executionParam))),
                    toolCall);
        }

        // The interceptor chain wraps dispatch logging + real execution: the dispatch record
        // captures the post-interception arguments, and denied calls produce no allowed/dispatch
        // records. Agent identity travels as agentId/agentName only, null on agentless calls.
        Agent callingAgent = param.getAgent();
        String agentId = callingAgent != null ? callingAgent.getAgentId() : null;
        String agentName = callingAgent != null ? callingAgent.getName() : null;
        Function<ToolInvocation, Mono<ToolResultBlock>> terminal =
                iv ->
                        dispatchRecord(recorder, executionParam, toolCall, iv.input())
                                .then(
                                        Mono.defer(
                                                () ->
                                                        tool.callAsync(
                                                                ToolCallParam.builder(
                                                                                executionParam)
                                                                        .input(iv.input())
                                                                        .build())));
        return applyResultErrorMapping(
                        Mono.defer(
                                () ->
                                        applyInterceptorChain(
                                                interceptors,
                                                new ToolInvocation(
                                                        toolCall.getName(),
                                                        tool,
                                                        mergedInput,
                                                        effectiveRuntimeContext,
                                                        agentId,
                                                        agentName),
                                                terminal)),
                        toolCall)
                // Interceptor-produced results (e.g. denials) carry no id/name — backfill them;
                // tool results already carry theirs. Error/empty-synthesized results also gain
                // id/name here (direct path only; normalized on the batch path).
                .map(
                        result ->
                                result.getId() == null
                                        ? result.withIdAndName(toolCall.getId(), toolCall.getName())
                                        : result);
    }

    /**
     * Records the pre-execution state snapshot plus the {@code allowed} decision and {@code
     * tool/dispatch} log for the given (post-interception) arguments; a no-op without a session
     * recorder.
     */
    private static Mono<Void> dispatchRecord(
            SessionRecorder recorder,
            ToolCallParam executionParam,
            ToolUseBlock toolCall,
            Map<String, Object> arguments) {
        if (recorder == null) {
            return Mono.empty();
        }
        return Mono.defer(
                () -> {
                    recorder.captureState(
                            RuntimeContext.resolveAgentState(
                                    executionParam.getRuntimeContext(), executionParam.getAgent()),
                            "before_tool");
                    recorder.append(
                            "tool/decision",
                            Map.of("toolCallId", toolCall.getId(), "decision", "allowed"));
                    return recorder.record(
                            "tool/dispatch",
                            Map.of(
                                    "toolCallId",
                                    toolCall.getId(),
                                    "actionId",
                                    recorder.actionId(toolCall.getId()),
                                    "name",
                                    toolCall.getName(),
                                    "arguments",
                                    arguments));
                });
    }

    /**
     * Assembles the ordered interceptor chain around the terminal execution (onion pattern, same
     * delegation shape as {@code MiddlewareBase}).
     *
     * <p>{@code interceptors} must already be stable-sorted by {@code order()} descending (see
     * {@link #sortInterceptors(ToolkitConfig)}): higher order runs outermost, equal orders keep
     * registration order. Each {@code intercept} call is wrapped in {@link Mono#defer} so a
     * synchronous throw becomes an error signal.
     *
     * <p>Errors are deliberately not attributed per layer: in an onion chain an error signal
     * cannot be reliably attributed to one layer, so they propagate as-is and are mapped by the
     * caller's {@link #applyResultErrorMapping(Mono, ToolUseBlock)}. Interceptors that need
     * attribution should encode their identity in exception messages.
     *
     * @param interceptors interceptors sorted by {@code order()} descending
     * @param invocation   the call snapshot entering the outermost interceptor
     * @param terminal     the chain endpoint: session dispatch logging + real tool execution
     * @return the result produced by the chain (outermost interceptor's result wins)
     */
    private static Mono<ToolResultBlock> applyInterceptorChain(
            List<ToolCallInterceptor> interceptors,
            ToolInvocation invocation,
            Function<ToolInvocation, Mono<ToolResultBlock>> terminal) {
        Function<ToolInvocation, Mono<ToolResultBlock>> next = terminal;
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            ToolCallInterceptor current = interceptors.get(i);
            Function<ToolInvocation, Mono<ToolResultBlock>> downstream = next;
            next = iv -> Mono.defer(() -> current.intercept(iv, downstream));
        }
        return next.apply(invocation);
    }

    /**
     * Uniform result error mapping for tool executions: {@link ToolSuspendException} becomes a
     * suspended result, observation/session-log errors propagate unchanged, and other errors and
     * empty results become model-visible {@code ToolResultBlock.error}.
     *
     * @param execution the execution pipeline whose errors and empty signals are mapped
     * @param toolCall  the tool call, for suspended-result identity and logging
     * @return the pipeline with suspend/error/empty cases mapped to result blocks
     */
    private Mono<ToolResultBlock> applyResultErrorMapping(
            Mono<ToolResultBlock> execution, ToolUseBlock toolCall) {
        return execution
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
                            if (ActionObservationException.causedBy(e)
                                    || SessionLogException.causedBy(e)) return Mono.error(e);
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
     * ToolRequestConfig#resolveTool(String, ToolRegistry)}; here it is applied against the
     * executor's <em>injected</em> {@link #toolRegistry}/{@link #config} (in production the same
     * object as the toolkit's, but injected so {@code ToolExecutor} stays testable).
     */
    private AgentTool resolveTool(String name, ToolRequestConfig requestConfig) {
        return (requestConfig != null ? requestConfig : ToolRequestConfig.NONE)
                .resolveTool(name, this.toolRegistry);
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
        // Persist outside the execution timeout so a slow observation commit cannot replay a tool.
        Mono<ToolResultBlock> execution =
                ActionObservations.observe(
                        param,
                        () ->
                                applyTimeout(
                                        applyScheduling(
                                                executeUnobserved(
                                                        param,
                                                        requestConfig,
                                                        internalChunkCallback)),
                                        executionConfig,
                                        toolCall));

        // Every retry receives a separate observed attempt.
        execution = applyRetry(execution, executionConfig, toolCall);
        execution = applyShutdownGuard(execution);

        // Add tool metadata and error handling
        return execution
                .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            if (ActionObservationException.causedBy(e)
                                    || SessionLogException.causedBy(e)) return Mono.error(e);
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
                        .filter(
                                error ->
                                        !ActionObservationException.causedBy(error)
                                                && !SessionLogException.causedBy(error)
                                                && retryOn.test(error))
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
