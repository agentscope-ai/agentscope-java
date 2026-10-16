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
package io.agentscope.core.tool.circuitbreaker;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * Withholds failing tools from each model request and observes their execution outcomes.
 *
 * <p>Recovery admits one reasoning turn. The selected probe is bound to the tool-call ID and saved
 * in the owning assistant message's metadata, so permission and external-execution resumes can use
 * a fresh {@link RuntimeContext}, including after session state is reloaded on another replica.
 * Probe metadata is removed from incoming messages and model requests; tool arguments and toolkit
 * registrations are never modified.
 *
 * <p>The default binding namespace is {@code default}. Replicas sharing the same breaker and session
 * state must use the same namespace. Independent breakers on the same agent must use different
 * namespaces. Direct reasoning calls with a null context withhold half-open tools without acquiring
 * a claim; the normal agent API supplies a context through {@link #onAgent}.
 *
 * <p>Higher {@link #order()} values wrap lower ones; the default is {@code 1}. Place this middleware
 * inside reasoning retries so each retry acquires its own claim, and after schema transformations
 * that add tools. An acting cache outside this middleware bypasses outcome observation; put it
 * inside when cached outcomes should count. Tool execution retries should finish before an outcome
 * is observed, so one logical execution contributes one outcome.
 */
public class ToolCircuitBreakerMiddleware implements MiddlewareBase {

    private static final Logger logger =
            LoggerFactory.getLogger(ToolCircuitBreakerMiddleware.class);
    private static final String METADATA_PREFIX =
            ToolCircuitBreakerMiddleware.class.getName() + ".probes.";

    private final ToolCircuitBreaker breaker;
    private final String probeAttributeKey;
    private final String claimAttributeKey;
    private final String metadataKey;
    private final String reasonedAttributeKey;
    private final int order;

    /** Creates middleware with binding namespace {@code default} and order {@code 1}. */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreaker breaker) {
        this(breaker, "default", 1);
    }

    /** Creates middleware with a stable binding namespace and order {@code 1}. */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreaker breaker, String bindingNamespace) {
        this(breaker, bindingNamespace, 1);
    }

    /**
     * Creates middleware with a stable namespace and configurable onion order.
     *
     * @param breaker policy and state shared by callers of the dependency
     * @param bindingNamespace stable across replicas; distinct for independent breakers on an agent
     * @param order higher values execute first, wrapping lower values
     */
    public ToolCircuitBreakerMiddleware(
            ToolCircuitBreaker breaker, String bindingNamespace, int order) {
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
        if (bindingNamespace == null || bindingNamespace.isBlank()) {
            throw new IllegalArgumentException("bindingNamespace must not be blank");
        }
        this.metadataKey = METADATA_PREFIX + bindingNamespace;
        this.probeAttributeKey = metadataKey + ".runtime";
        this.claimAttributeKey = metadataKey + ".claims";
        this.reasonedAttributeKey = metadataKey + ".reasoned";
        this.order = order;
    }

    /** Creates middleware with the default in-process store. */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreakerConfig config) {
        this(new ToolCircuitBreaker(config));
    }

    /** Creates middleware with the default store and a stable binding namespace. */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreakerConfig config, String bindingNamespace) {
        this(new ToolCircuitBreaker(config), bindingNamespace);
    }

    /** Creates middleware with the default store, a stable namespace and configurable order. */
    public ToolCircuitBreakerMiddleware(
            ToolCircuitBreakerConfig config, String bindingNamespace, int order) {
        this(new ToolCircuitBreaker(config), bindingNamespace, order);
    }

    public ToolCircuitBreaker getBreaker() {
        return breaker;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                context -> {
                    RuntimeContext effective =
                            ctx != null
                                    ? ctx
                                    : context.getOrDefault(
                                            AgentBase.RUNTIME_CONTEXT_KEY, RuntimeContext.empty());
                    probeBindings(effective).clear();
                    claims(effective).clear();
                    effective.put(reasonedAttributeKey, Boolean.FALSE);
                    AtomicBoolean finalResultSeen = new AtomicBoolean();
                    return next.apply(new AgentInput(withoutProbeMetadata(input.msgs())))
                            .contextWrite(c -> c.put(AgentBase.RUNTIME_CONTEXT_KEY, effective))
                            .doOnNext(
                                    event -> {
                                        if (event
                                                instanceof ExternalExecutionResultEvent external) {
                                            for (ToolResultBlock result :
                                                    external.getToolResults()) {
                                                String name =
                                                        toolName(
                                                                effective.getAgentState(),
                                                                result.getId());
                                                recordOutcome(
                                                        effective,
                                                        result.getId(),
                                                        name,
                                                        resultState(result));
                                            }
                                        } else if (event
                                                instanceof UserConfirmResultEvent confirmation) {
                                            confirmation.getConfirmResults().stream()
                                                    .filter(result -> !result.isConfirmed())
                                                    .forEach(
                                                            result -> {
                                                                String id =
                                                                        result.getToolCall()
                                                                                .getId();
                                                                recordOutcome(
                                                                        effective,
                                                                        id,
                                                                        toolName(
                                                                                effective
                                                                                        .getAgentState(),
                                                                                id),
                                                                        ToolResultState.DENIED);
                                                            });
                                        } else if (event instanceof AgentResultEvent result) {
                                            finalResultSeen.set(true);
                                            reconcileBoundResults(effective);
                                            GenerateReason reason =
                                                    result.getResult().getGenerateReason();
                                            if (reason != GenerateReason.PERMISSION_ASKING
                                                    && reason != GenerateReason.TOOL_SUSPENDED) {
                                                releaseAll(effective);
                                            }
                                        }
                                    })
                            .map(this::withoutProbeResultMetadata)
                            .doOnComplete(() -> reconcileBoundResults(effective))
                            .doFinally(
                                    signal -> {
                                        if (signal != SignalType.ON_COMPLETE
                                                && !finalResultSeen.get()) {
                                            releaseAll(effective);
                                        }
                                    });
                });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> reasoning(ctx, input, next));
    }

    private Flux<AgentEvent> reasoning(
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            restoreBindings(ctx);
            reconcileBoundResults(ctx);
            ctx.put(reasonedAttributeKey, Boolean.TRUE);
        }
        List<ToolSchema> tools = input.tools();
        List<ToolSchema> visible = tools == null ? null : new ArrayList<>(tools.size());
        Map<String, String> probes = new LinkedHashMap<>();
        if (tools != null) {
            for (ToolSchema tool : tools) {
                String name = tool == null ? null : tool.getName();
                ToolCircuitState state =
                        name == null ? ToolCircuitState.CLOSED : breaker.state(name);
                if (state == ToolCircuitState.CLOSED) {
                    visible.add(tool);
                    continue;
                }
                Optional<String> probe =
                        state == ToolCircuitState.HALF_OPEN && ctx != null
                                ? breaker.tryAcquireProbe(name)
                                : Optional.empty();
                if (probe.isPresent()) {
                    probes.put(name, probe.get());
                    visible.add(tool);
                } else {
                    logger.debug("Withholding tripped tool from reasoning: {}", name);
                }
            }
        }
        boolean filtered = tools != null && visible.size() != tools.size();
        List<Msg> messages = withoutProbeMetadata(input.messages());
        GenerateOptions options =
                filtered ? normalizedOptions(input.options(), visible) : input.options();
        ReasoningInput forwarded =
                !filtered && messages == input.messages()
                        ? input
                        : new ReasoningInput(messages, filtered ? visible : tools, options);
        Map<String, ProbeBinding> bindings = ctx == null ? null : probeBindings(ctx);
        if (ctx != null) {
            claims(ctx).putAll(probes);
        }
        return Flux.defer(() -> next.apply(forwarded))
                .doOnNext(
                        event -> {
                            if (event instanceof ToolCallStartEvent call
                                    && call.getToolCallId() != null) {
                                String token = probes.get(call.getToolCallName());
                                if (token != null) {
                                    bindings.put(
                                            call.getToolCallId(),
                                            new ProbeBinding(call.getToolCallName(), token, null));
                                }
                            }
                        })
                .doFinally(
                        signal -> {
                            probes.forEach(
                                    (name, token) -> {
                                        boolean bound =
                                                bindings != null
                                                        && bindings.values().stream()
                                                                .anyMatch(
                                                                        binding ->
                                                                                token.equals(
                                                                                        binding
                                                                                                .token()));
                                        if (signal != SignalType.ON_COMPLETE || !bound) {
                                            releaseToken(ctx, name, token);
                                        }
                                        if (ctx != null) {
                                            claims(ctx).remove(name, token);
                                        }
                                    });
                        });
    }

    private GenerateOptions normalizedOptions(GenerateOptions options, List<ToolSchema> visible) {
        if (options == null) {
            return null;
        }
        ToolChoice choice = options.getToolChoice();
        boolean unavailable =
                choice instanceof ToolChoice.Specific specific
                        && visible.stream()
                                .noneMatch(
                                        tool ->
                                                tool != null
                                                        && specific.toolName()
                                                                .equals(tool.getName()));
        if (unavailable || (visible.isEmpty() && choice instanceof ToolChoice.Required)) {
            return GenerateOptions.mergeOptions(
                    GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build(), options);
        }
        return options;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (ctx != null) {
                        if (!Boolean.TRUE.equals(ctx.get(reasonedAttributeKey))) {
                            restoreBindings(ctx);
                        }
                        for (ToolUseBlock use : input.toolCalls()) {
                            String token = claims(ctx).get(use.getName());
                            if (token != null && use.getId() != null) {
                                probeBindings(ctx)
                                        .put(
                                                use.getId(),
                                                new ProbeBinding(use.getName(), token, null));
                            }
                        }
                        persistBindings(ctx);
                    }
                    return Flux.defer(() -> next.apply(input))
                            .doOnNext(
                                    event -> {
                                        if (event instanceof ToolResultEndEvent result) {
                                            recordOutcome(
                                                    ctx,
                                                    result.getToolCallId(),
                                                    result.getToolCallName(),
                                                    result.getState());
                                        } else if (event instanceof AllToolsDeniedEvent denied) {
                                            denied.getDeniedToolCalls()
                                                    .forEach(
                                                            use ->
                                                                    recordOutcome(
                                                                            ctx,
                                                                            use.getId(),
                                                                            use.getName(),
                                                                            ToolResultState
                                                                                    .DENIED));
                                        }
                                    })
                            .doOnComplete(
                                    () -> {
                                        if (ctx != null) {
                                            reconcileBoundResults(ctx);
                                        }
                                    })
                            .doFinally(
                                    signal -> {
                                        if (signal != SignalType.ON_COMPLETE && ctx != null) {
                                            releaseAll(ctx);
                                        }
                                    });
                });
    }

    /** Observe results written directly into session state without a terminal acting event. */
    private void reconcileBoundResults(RuntimeContext ctx) {
        AgentState state = ctx.getAgentState();
        if (state == null) {
            return;
        }
        if (!Boolean.TRUE.equals(ctx.get(reasonedAttributeKey))) {
            restoreBindings(ctx);
        }
        List<Msg> messages = state.getContext();
        for (Map.Entry<String, ProbeBinding> pending :
                new ArrayList<>(probeBindings(ctx).entrySet())) {
            String id = pending.getKey();
            ProbeBinding binding = pending.getValue();
            boolean ownerSeen = false;
            for (Msg msg : messages) {
                boolean ownsId =
                        msg.getRole() == MsgRole.ASSISTANT
                                && msg.getContentBlocks(ToolUseBlock.class).stream()
                                        .anyMatch(use -> id.equals(use.getId()));
                if (ownsId) {
                    if (!msg.getId().equals(binding.ownerMessageId())) {
                        if (ownerSeen) {
                            break;
                        }
                        continue;
                    }
                    if (ownerSeen) {
                        break;
                    }
                    Object stored = msg.getMetadata().get(metadataKey);
                    ownerSeen =
                            stored instanceof Map<?, ?> map
                                    && map.get(id) instanceof Map<?, ?> entry
                                    && binding.token().equals(entry.get("token"));
                } else if (ownerSeen) {
                    for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                        if (id.equals(result.getId())
                                && resultState(result) != ToolResultState.RUNNING
                                && resultState(result) != ToolResultState.SUSPENDED) {
                            recordOutcome(ctx, id, binding.toolName(), resultState(result));
                            ownerSeen = false;
                            break;
                        }
                    }
                }
            }
        }
    }

    private void recordOutcome(
            RuntimeContext ctx, String callId, String name, ToolResultState state) {
        if (name == null
                || state == null
                || state == ToolResultState.RUNNING
                || state == ToolResultState.SUSPENDED) {
            return;
        }
        String token = null;
        if (ctx != null && callId != null) {
            if (!Boolean.TRUE.equals(ctx.get(reasonedAttributeKey))) {
                restoreBindings(ctx);
            }
            ProbeBinding binding = probeBindings(ctx).remove(callId);
            if (binding != null) {
                if (!name.equals(binding.toolName())) {
                    breaker.releaseProbe(binding.toolName(), binding.token());
                } else {
                    token = binding.token();
                }
                persistBindings(ctx);
            }
        }
        switch (state) {
            case ERROR -> breaker.recordFailure(name, token);
            case SUCCESS -> breaker.recordSuccess(name, token);
            case DENIED, INTERRUPTED -> breaker.releaseProbe(name, token);
            default -> {}
        }
    }

    private ToolResultState resultState(ToolResultBlock result) {
        if (result.isSuspended()) {
            return ToolResultState.SUSPENDED;
        }
        if (result.getState() != null && result.getState() != ToolResultState.RUNNING) {
            return result.getState();
        }
        return result.getOutput().stream()
                        .anyMatch(
                                block ->
                                        block instanceof TextBlock text
                                                && text.getText() != null
                                                && text.getText().startsWith("[ERROR]"))
                ? ToolResultState.ERROR
                : ToolResultState.SUCCESS;
    }

    private String toolName(AgentState state, String callId) {
        if (state == null || callId == null) {
            return null;
        }
        for (int i = state.contextMutable().size() - 1; i >= 0; i--) {
            Msg msg = state.contextMutable().get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                    if (callId.equals(use.getId())) {
                        return use.getName();
                    }
                }
            }
        }
        return null;
    }

    private void restoreBindings(RuntimeContext ctx) {
        AgentState state = ctx.getAgentState();
        if (state == null) {
            return;
        }
        Map<String, ProbeBinding> bindings = probeBindings(ctx);
        for (Msg msg : state.getContext()) {
            if (msg.getRole() != MsgRole.ASSISTANT
                    || !(msg.getMetadata().get(metadataKey) instanceof Map<?, ?> stored)) {
                continue;
            }
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                if (stored.get(use.getId()) instanceof Map<?, ?> entry
                        && use.getName().equals(entry.get("name"))
                        && entry.get("token") instanceof String token) {
                    bindings.putIfAbsent(
                            use.getId(), new ProbeBinding(use.getName(), token, msg.getId()));
                }
            }
        }
    }

    private void persistBindings(RuntimeContext ctx) {
        AgentState state = ctx.getAgentState();
        if (state == null) {
            return;
        }
        Map<String, ProbeBinding> bindings = probeBindings(ctx);
        bindings.replaceAll(
                (callId, binding) -> {
                    if (binding.ownerMessageId() != null) {
                        return binding;
                    }
                    for (int i = state.contextMutable().size() - 1; i >= 0; i--) {
                        Msg msg = state.contextMutable().get(i);
                        if (msg.getRole() == MsgRole.ASSISTANT
                                && msg.getContentBlocks(ToolUseBlock.class).stream()
                                        .anyMatch(
                                                use ->
                                                        callId.equals(use.getId())
                                                                && binding.toolName()
                                                                        .equals(use.getName()))) {
                            return new ProbeBinding(
                                    binding.toolName(), binding.token(), msg.getId());
                        }
                    }
                    return binding;
                });
        for (int i = 0; i < state.contextMutable().size(); i++) {
            Msg msg = state.contextMutable().get(i);
            if (msg.getRole() != MsgRole.ASSISTANT || !msg.hasContentBlocks(ToolUseBlock.class)) {
                continue;
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                ProbeBinding binding = bindings.get(use.getId());
                if (binding != null
                        && msg.getId().equals(binding.ownerMessageId())
                        && use.getName().equals(binding.toolName())) {
                    stored.put(
                            use.getId(),
                            Map.of("name", binding.toolName(), "token", binding.token()));
                }
            }
            Map<String, Object> metadata = new HashMap<>(msg.getMetadata());
            if (stored.isEmpty()) {
                metadata.remove(metadataKey);
            } else {
                metadata.put(metadataKey, stored);
            }
            state.contextMutable().set(i, msg.withMetadata(metadata));
        }
    }

    private void releaseToken(RuntimeContext ctx, String name, String token) {
        breaker.releaseProbe(name, token);
        if (ctx != null) {
            probeBindings(ctx).entrySet().removeIf(entry -> token.equals(entry.getValue().token()));
            persistBindings(ctx);
        }
    }

    private void releaseAll(RuntimeContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(reasonedAttributeKey))) {
            restoreBindings(ctx);
        }
        new ArrayList<>(probeBindings(ctx).values())
                .forEach(binding -> breaker.releaseProbe(binding.toolName(), binding.token()));
        probeBindings(ctx).clear();
        claims(ctx).forEach(breaker::releaseProbe);
        claims(ctx).clear();
        persistBindings(ctx);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ProbeBinding> probeBindings(RuntimeContext ctx) {
        Map<String, ProbeBinding> bindings = ctx.get(probeAttributeKey);
        if (bindings == null) {
            bindings = new ConcurrentHashMap<>();
            ctx.put(probeAttributeKey, bindings);
        }
        return bindings;
    }

    private Map<String, String> claims(RuntimeContext ctx) {
        Map<String, String> claimed = ctx.get(claimAttributeKey);
        if (claimed == null) {
            claimed = new ConcurrentHashMap<>();
            ctx.put(claimAttributeKey, claimed);
        }
        return claimed;
    }

    private AgentEvent withoutProbeResultMetadata(AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return event;
        }
        List<Msg> original = List.of(result.getResult());
        List<Msg> cleaned = withoutProbeMetadata(original);
        if (cleaned == original) {
            return event;
        }
        return new AgentResultEvent(event.getId(), event.getCreatedAt(), cleaned.get(0))
                .withSource(event.getSource())
                .withExecution(event.getExecution())
                .withMetadata(event.getMetadata());
    }

    private List<Msg> withoutProbeMetadata(List<Msg> messages) {
        if (messages == null
                || messages.stream()
                        .noneMatch(
                                msg ->
                                        msg.getMetadata().keySet().stream()
                                                .anyMatch(
                                                        key -> key.startsWith(METADATA_PREFIX)))) {
            return messages;
        }
        return messages.stream()
                .map(
                        msg -> {
                            Map<String, Object> metadata = new HashMap<>(msg.getMetadata());
                            metadata.keySet().removeIf(key -> key.startsWith(METADATA_PREFIX));
                            return msg.withMetadata(metadata);
                        })
                .toList();
    }

    private record ProbeBinding(String toolName, String token, String ownerMessageId) {}
}
