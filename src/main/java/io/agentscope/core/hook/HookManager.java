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
package io.agentscope.core.hook;

import io.agentscope.core.agent.AgentBase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Manager for executing agent hooks following the Python AgentScope pattern.
 *
 * This class provides the core hook execution functionality, including:
 * - Pre-hook and post-hook execution
 * - Instance-level and class-level hook management
 * - Thread-safe hook registration and execution
 * - Error handling and recovery
 *
 * Hook execution order follows Python implementation:
 * 1. Instance-level pre-hooks (in registration order)
 * 2. Class-level pre-hooks (in registration order)
 * 3. Core operation execution
 * 4. Instance-level post-hooks (in registration order)
 * 5. Class-level post-hooks (in registration order)
 */
public class HookManager {

    private static final Logger logger = LoggerFactory.getLogger(HookManager.class);

    // Instance-scoped hooks (per HookManager instance)
    private final Map<AgentHookType, LinkedHashMap<String, PreHook<? extends AgentBase>>>
            instancePreHooks = new ConcurrentHashMap<>();
    private final Map<AgentHookType, LinkedHashMap<String, PostHook<? extends AgentBase>>>
            instancePostHooks = new ConcurrentHashMap<>();

    /**
     * Execute a method with hooks applied.
     *
     * @param agent The agent instance
     * @param preHookType The pre-hook type to execute before the operation
     * @param methodName The name of the method being executed
     * @param coreFunction The core function to execute
     * @param args Original method arguments
     * @param <T> Return type of the core function
     * @return Flux emitting results with hooks applied
     */
    @SuppressWarnings("unchecked")
    public <T> Flux<T> executeWithHooks(
            AgentBase agent,
            AgentHookType preHookType,
            String methodName,
            Supplier<Flux<T>> coreFunction,
            Object... args) {

        return Mono.defer(
                        () -> {
                            HookArgs hookArgs = normalizeToArgs(methodName, args);
                            return executePreHooks(agent, preHookType, methodName, hookArgs);
                        })
                .flatMapMany(
                        finalArgs -> {
                            AgentHookType postHookType = getCorrespondingPostHookType(preHookType);
                            return coreFunction
                                    .get()
                                    .collectList()
                                    .flatMapMany(
                                            items -> {
                                                if (items.isEmpty()) {
                                                    return executePostHooks(
                                                                    agent,
                                                                    postHookType,
                                                                    methodName,
                                                                    finalArgs,
                                                                    null)
                                                            .thenMany(Flux.empty());
                                                }
                                                return Flux.fromIterable(items)
                                                        .concatMap(
                                                                item ->
                                                                        executePostHooks(
                                                                                        agent,
                                                                                        postHookType,
                                                                                        methodName,
                                                                                        finalArgs,
                                                                                        item)
                                                                                .flatMapMany(
                                                                                        o ->
                                                                                                o
                                                                                                                == null
                                                                                                        ? Flux
                                                                                                                .empty()
                                                                                                        : Flux
                                                                                                                .just(
                                                                                                                        (T)
                                                                                                                                o)));
                                            });
                        })
                .onErrorResume(
                        e -> {
                            logger.warn("Hook execution failed for {}", methodName, e);
                            return Flux.empty();
                        });
    }

    /**
     * Execute pre-hooks for the given hook type.
     *
     * @param agent The agent instance
     * @param hookType Hook type to execute
     * @param methodName Method name for context
     * @param kwargs Initial arguments
     * @return Modified arguments after all pre-hooks
     */
    @SuppressWarnings("unchecked")
    private Mono<HookArgs> executePreHooks(
            AgentBase agent, AgentHookType hookType, String methodName, HookArgs args) {
        Mono<HookArgs> chain = Mono.just(args);

        LinkedHashMap<String, PreHook<? extends AgentBase>> instanceHooks =
                instancePreHooks.get(hookType);
        if (instanceHooks != null) {
            for (Map.Entry<String, PreHook<? extends AgentBase>> entry : instanceHooks.entrySet()) {
                PreHook<AgentBase> hook = (PreHook<AgentBase>) entry.getValue();
                chain =
                        chain.flatMap(
                                currentArgs ->
                                        Mono.defer(() -> hook.execute(agent, currentArgs))
                                                .onErrorResume(
                                                        e -> {
                                                            logger.warn(
                                                                    "Instance pre-hook '{}' failed"
                                                                            + " for {}",
                                                                    entry.getKey(),
                                                                    methodName,
                                                                    e);
                                                            return Mono.just(currentArgs);
                                                        })
                                                .defaultIfEmpty(currentArgs));
            }
        }

        return chain;
    }

    /**
     * Execute post-hooks for the given hook type.
     *
     * @param agent The agent instance
     * @param hookType Hook type to execute
     * @param methodName Method name for context
     * @param kwargs Arguments used for the operation
     * @param output Output from the core operation
     * @return Modified output after all post-hooks
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> executePostHooks(
            AgentBase agent,
            AgentHookType hookType,
            String methodName,
            HookArgs args,
            Object output) {
        Mono<java.util.Optional<Object>> chain = Mono.just(java.util.Optional.ofNullable(output));

        LinkedHashMap<String, PostHook<? extends AgentBase>> instanceHooks =
                instancePostHooks.get(hookType);
        if (instanceHooks != null) {
            for (Map.Entry<String, PostHook<? extends AgentBase>> entry :
                    instanceHooks.entrySet()) {
                PostHook<AgentBase> hook = (PostHook<AgentBase>) entry.getValue();
                chain =
                        chain.flatMap(
                                currentOpt ->
                                        Mono.defer(
                                                        () ->
                                                                hook.execute(
                                                                        agent,
                                                                        args,
                                                                        currentOpt.orElse(null)))
                                                .map(java.util.Optional::ofNullable)
                                                .onErrorResume(
                                                        e -> {
                                                            logger.warn(
                                                                    "Instance post-hook '{}' failed"
                                                                            + " for {}",
                                                                    entry.getKey(),
                                                                    methodName,
                                                                    e);
                                                            return Mono.just(currentOpt);
                                                        })
                                                .defaultIfEmpty(currentOpt));
            }
        }

        return chain.flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Register an instance-level pre-hook.
     *
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook The hook to register
     */
    public synchronized void registerInstancePreHook(
            AgentHookType hookType, String hookName, PreHook<? extends AgentBase> hook) {
        instancePreHooks.computeIfAbsent(hookType, k -> new LinkedHashMap<>()).put(hookName, hook);
    }

    /**
     * Register an instance-level post-hook.
     *
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook The hook to register
     */
    public synchronized void registerInstancePostHook(
            AgentHookType hookType, String hookName, PostHook<? extends AgentBase> hook) {
        instancePostHooks.computeIfAbsent(hookType, k -> new LinkedHashMap<>()).put(hookName, hook);
    }

    /**
     * Register a class-level pre-hook.
     *
     * @param agentClass Class to register hook for
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook The hook to register
     */
    // Class-level hooks have been removed to ensure agent-scoped isolation.

    /**
     * Register a class-level post-hook.
     *
     * @param agentClass Class to register hook for
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook The hook to register
     */
    // Class-level hooks have been removed to ensure agent-scoped isolation.

    /**
     * Remove an instance-level hook.
     *
     * @param hookType Type of hook
     * @param hookName Name of the hook to remove
     * @return True if hook was removed, false if not found
     */
    public synchronized boolean removeInstanceHook(AgentHookType hookType, String hookName) {
        boolean removed = false;
        LinkedHashMap<String, PreHook<? extends AgentBase>> preHooks =
                instancePreHooks.get(hookType);
        if (preHooks != null) {
            removed = preHooks.remove(hookName) != null;
        }
        LinkedHashMap<String, PostHook<? extends AgentBase>> postHooks =
                instancePostHooks.get(hookType);
        if (postHooks != null) {
            removed = postHooks.remove(hookName) != null || removed;
        }
        return removed;
    }

    /**
     * Clear all instance-level hooks for a specific type, or all types if null.
     *
     * @param hookType Hook type to clear, or null for all types
     */
    public synchronized void clearInstanceHooks(AgentHookType hookType) {
        if (hookType == null) {
            instancePreHooks.clear();
            instancePostHooks.clear();
            return;
        }
        LinkedHashMap<String, PreHook<? extends AgentBase>> preHooks =
                instancePreHooks.get(hookType);
        if (preHooks != null) preHooks.clear();
        LinkedHashMap<String, PostHook<? extends AgentBase>> postHooks =
                instancePostHooks.get(hookType);
        if (postHooks != null) postHooks.clear();
    }

    /**
     * Convert method arguments to a normalized kwargs map.
     * This follows the Python _normalize_to_kwargs pattern.
     *
     * @param methodName Method name for context
     * @param args Method arguments
     * @return Normalized arguments map
     */
    private HookArgs normalizeToArgs(String methodName, Object... args) {
        if (args == null || args.length == 0) return null;
        Object x = args[0];
        return switch (methodName) {
            case "reply" -> {
                if (x instanceof io.agentscope.core.message.Msg m) yield new ReplyArgs(m);
                if (x instanceof java.util.List<?> l) {
                    @SuppressWarnings("unchecked")
                    java.util.List<io.agentscope.core.message.Msg> casted =
                            (java.util.List<io.agentscope.core.message.Msg>) l;
                    yield new ReplyListArgs(casted);
                }
                yield null;
            }
            case "observe" -> {
                if (x instanceof io.agentscope.core.message.Msg m) yield new ObserveArgs(m);
                yield null;
            }
            case "print" -> {
                if (x instanceof String s) yield new PrintArgs(s);
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Get the corresponding post-hook type for a given pre-hook type.
     *
     * @param preHookType The pre-hook type
     * @return The corresponding post-hook type
     */
    private AgentHookType getCorrespondingPostHookType(AgentHookType preHookType) {
        return switch (preHookType) {
            case PRE_REPLY -> AgentHookType.POST_REPLY;
            default ->
                    throw new IllegalArgumentException(
                            "No corresponding post-hook type for: " + preHookType);
        };
    }
}
