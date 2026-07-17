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

package io.agentscope.core.a2a.agent;

import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.FixedAgentCardResolver;
import io.agentscope.core.a2a.agent.event.ClientEventContext;
import io.agentscope.core.a2a.agent.event.ClientEventHandlerRouter;
import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aHitlResponse;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonUtils;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * The implementation of Agent for A2A(Agent2Agent).
 *
 * <p>Agent description should get from AgentCard. If AgentCard get failed, description will be default value from
 * {@link Agent#getDescription()}
 *
 * <p>Example Usage:
 * <pre>{@code
 *  // Simple usage.
 *  AgentCard agentCard = generateAgentCardByCode();
 *  A2aAgent a2aAgent = A2aAgent.builder().name("remote-agent-name").agentCard(agentCard).build();
 *
 *  // Auto get AgentCard
 *  AgentCardResolver agentCardResolver = new WellKnownAgentCardResolver("http://127.0.0.1:8080", "/.well-known/agent-card.json", Map.of());
 *  A2aAgent a2aAgent = A2aAgent.builder().name("remote-agent-name").agentCardResolver(agentCardResolver).build();
 * }</pre>
 */
public class A2aAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(A2aAgent.class);

    private static final String INTERRUPT_HINT_PATTERN = "Task %s interrupt successfully.";

    private static final String RESUME_LIFECYCLE_PLACEHOLDER = "Resume A2A HITL handoff";

    private static final String CANCEL_LIFECYCLE_PLACEHOLDER = "Cancel A2A HITL handoff";

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private static final Object INVOCATION_CONTEXT_KEY = new Object();

    private static final Object CALL_SCOPE_CONTEXT_KEY = new Object();

    private final AgentCardResolver agentCardResolver;

    private final A2aAgentConfig a2aAgentConfig;

    private final Memory memory;

    private final ClientEventHandlerRouter clientEventHandlerRouter;

    /* Compatibility alias used by existing client-injection hooks; execution owns CallScope.client. */
    private volatile Client a2aClient;

    private final AtomicReference<CallScope> activeCall = new AtomicReference<>();

    private A2aAgent(
            String name,
            String description,
            boolean checkRunning,
            Memory memory,
            List<Hook> hooks,
            AgentCardResolver agentCardResolver,
            A2aAgentConfig a2aAgentConfig) {
        super(name, description, checkRunning, hooks);
        this.a2aAgentConfig = a2aAgentConfig;
        this.agentCardResolver = agentCardResolver;
        this.memory = memory;
        LoggerUtil.debug(log, "A2aAgent init with config: {}", a2aAgentConfig);
        addHook(new A2aClientLifecycleHook());
        this.clientEventHandlerRouter = new ClientEventHandlerRouter();
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.deferContextual(
                contextView -> {
                    CallScope scope = requireCallScope(contextView);
                    scope.adoptClient(a2aClient);
                    HitlTurn hitlTurn = scope.invocation().hitlTurn();
                    if (hitlTurn == null && msgs != null && !msgs.isEmpty()) {
                        msgs.forEach(memory::addMessage);
                    }
                    LoggerUtil.info(log, "[{}] A2aAgent start call.", scope.requestId());
                    LoggerUtil.debug(
                            log, "[{}] A2aAgent call with input messages: ", scope.requestId());
                    LoggerUtil.logTextMsgDetail(log, memory.getMessages());
                    scope.eventContext().setHooks(getSortedHooks());
                    scope.eventContext()
                            .setInputMessages(
                                    hitlTurn == null
                                            ? memory.getMessages()
                                            : (msgs == null ? List.of() : msgs));
                    Message message =
                            hitlTurn == null
                                    ? MessageConvertUtil.convertFromMsg(
                                            memory.getMessages(),
                                            getCurrentContextId(scope),
                                            getCurrentRequestMetadata(scope))
                                    : buildHitlMessage(scope, hitlTurn);
                    return checkInterruptedAsync()
                            .then(doExecute(scope, message))
                            .doOnNext(this.memory::addMessage);
                });
    }

    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        List<Msg> credentialFreeInputs = HitlMessageCodec.credentialFreeInputs(msgs);
        return Mono.deferContextual(
                contextView -> {
                    Invocation invocation =
                            contextView.getOrDefault(
                                    INVOCATION_CONTEXT_KEY,
                                    new Invocation(A2aRequestOptions.empty(), null));
                    CallScope scope = new CallScope(invocation);
                    if (!activeCall.compareAndSet(null, scope)) {
                        return Mono.error(
                                new IllegalStateException(
                                        "A2aAgent already has an active call; concurrent"
                                            + " subscriptions on one instance are unsupported"));
                    }
                    RuntimeContext effectiveContext = invocation.options().runtimeContext();
                    if (effectiveContext == null) {
                        effectiveContext = context;
                    }
                    try {
                        return super.callInternal(credentialFreeInputs, effectiveContext, doCallFn)
                                .map(
                                        msg -> {
                                            Msg enhanced =
                                                    HitlMessageCodec.enhanceTerminal(
                                                            msg,
                                                            scope.callerNextResumeToken()
                                                                    .getAndSet(null));
                                            releaseCall(scope);
                                            return enhanced;
                                        })
                                .doOnError(error -> releaseCall(scope))
                                .doFinally(
                                        signal -> {
                                            if (signal == SignalType.CANCEL) {
                                                abandonCall(scope);
                                            }
                                            releaseCall(scope);
                                        })
                                .contextWrite(c -> c.put(CALL_SCOPE_CONTEXT_KEY, scope));
                    } catch (RuntimeException | Error error) {
                        releaseCall(scope);
                        return Mono.error(error);
                    }
                });
    }

    public Mono<Msg> call(List<Msg> msgs, RuntimeContext runtimeContext) {
        return invoke(
                msgs,
                new Invocation(
                        A2aRequestOptions.builder().runtimeContext(runtimeContext).build(), null));
    }

    public Mono<Msg> call(List<Msg> msgs, A2aRequestOptions requestOptions) {
        return invoke(
                msgs,
                new Invocation(
                        requestOptions == null ? A2aRequestOptions.empty() : requestOptions, null));
    }

    /** Resume a durable HITL handoff using default request options. */
    public Mono<Msg> resume(A2aHandoff handoff, List<? extends A2aHitlResponse> responses) {
        return resume(handoff, responses, A2aRequestOptions.empty());
    }

    /** Resume a durable HITL handoff as a new A2A turn on the same task and context. */
    public Mono<Msg> resume(
            A2aHandoff handoff,
            List<? extends A2aHitlResponse> responses,
            A2aRequestOptions options) {
        return Mono.defer(
                () -> {
                    List<A2aHitlResponse> validated = validateResponses(handoff, responses);
                    return invoke(
                            List.of(
                                    Msg.builder()
                                            .textContent(RESUME_LIFECYCLE_PLACEHOLDER)
                                            .build()),
                            new Invocation(
                                    options == null ? A2aRequestOptions.empty() : options,
                                    new HitlTurn(HitlOperation.RESUME, handoff, validated)));
                });
    }

    /** Atomically cancel an open durable handoff. */
    public Mono<Msg> cancelHandoff(A2aHandoff handoff, A2aRequestOptions options) {
        return Mono.defer(
                () -> {
                    Objects.requireNonNull(handoff, "handoff must not be null");
                    return invoke(
                            List.of(
                                    Msg.builder()
                                            .textContent(CANCEL_LIFECYCLE_PLACEHOLDER)
                                            .build()),
                            new Invocation(
                                    options == null ? A2aRequestOptions.empty() : options,
                                    new HitlTurn(HitlOperation.CANCEL, handoff, List.of())));
                });
    }

    private Mono<Msg> invoke(List<Msg> msgs, Invocation invocation) {
        return call(msgs).contextWrite(c -> c.put(INVOCATION_CONTEXT_KEY, invocation));
    }

    @Override
    public void interrupt() {
        super.interrupt();
        handleInterrupt(InterruptContext.builder().build()).block();
    }

    @Override
    public void interrupt(Msg msg) {
        super.interrupt(msg);
        handleInterrupt(InterruptContext.builder().userMessage(msg).build()).block();
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        memory.addMessage(HitlMessageCodec.credentialFreeInput(msg));
        return Mono.empty();
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        CallScope scope = activeCall.get();
        if (scope == null) {
            return Mono.error(
                    new IllegalStateException("A2aAgent has no active call to interrupt"));
        }
        LoggerUtil.debug(log, "[{}] A2aAgent handle interrupt.", scope.requestId());
        try {
            String taskId = scope.eventContext().getTask().id();
            CancelTaskParams cancelTaskParams = new CancelTaskParams(taskId);
            scope.client().cancelTask(cancelTaskParams, scope.clientCallContext());
            return Mono.just(
                    Msg.builder()
                            .content(
                                    TextBlock.builder()
                                            .text(String.format(INTERRUPT_HINT_PATTERN, taskId))
                                            .build())
                            .build());
        } catch (A2AClientException e) {
            return Mono.just(
                    Msg.builder()
                            .content(TextBlock.builder().text(e.getMessage()).build())
                            .build());
        }
    }

    /**
     * Create a new {@link Builder} instance for {@link A2aAgent}.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public Memory getMemory() {
        return memory;
    }

    private Client buildA2aClient(String name) {
        ClientBuilder builder = Client.builder(this.agentCardResolver.getAgentCard(name));
        if (this.a2aAgentConfig.clientTransports().isEmpty()) {
            // Default Add The Basic JSON-RPC Transport
            builder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig());
        } else {
            this.a2aAgentConfig.clientTransports().forEach(builder::withTransport);
        }
        builder.clientConfig(this.a2aAgentConfig.clientConfig());
        return builder.build();
    }

    private Mono<Msg> doExecute(CallScope scope, Message message) {
        return Mono.create(
                sink -> {
                    String requestId = scope.requestId();
                    ClientEventContext eventContext = scope.eventContext();
                    ClientCallContext callContext = buildClientCallContext(scope);
                    scope.clientCallContext(callContext);
                    Client client = scope.client();
                    eventContext.setSink(sink);
                    MessageSendParams params = buildMessageSendParams(scope, message);
                    String currentResumeToken =
                            metadataString(
                                    params.metadata(), MessageConstants.RESUME_TOKEN_METADATA_KEY);
                    String nextResumeToken =
                            metadataString(
                                    params.metadata(),
                                    MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY);
                    BiConsumer<ClientEvent, AgentCard> a2aEventConsumer =
                            (event, agentCard) -> {
                                if (eventContext.isTerminalDelivered()) {
                                    return;
                                }
                                logInboundEventStructure(requestId, event);
                                clientEventHandlerRouter.handle(event, eventContext);
                            };
                    try {
                        client.sendMessage(
                                params,
                                List.of(a2aEventConsumer),
                                error -> {
                                    if (error == null) {
                                        if (eventContext.isTerminalDelivered()) {
                                            return;
                                        }
                                        LoggerUtil.warn(
                                                log,
                                                "[{}] A2aAgent stream completed before final"
                                                        + " response.",
                                                requestId);
                                        eventContext.completeExceptionally(
                                                new IllegalStateException(
                                                        "A2A stream completed before final"
                                                                + " response."));
                                        return;
                                    }
                                    if (error instanceof CancellationException) {
                                        LoggerUtil.warn(
                                                log,
                                                "[{}] A2aAgent sendMessage cancelled.",
                                                requestId);
                                    }
                                    eventContext.completeExceptionally(
                                            redactTransportError(
                                                    error, currentResumeToken, nextResumeToken));
                                },
                                callContext);
                    } catch (Throwable error) {
                        Throwable safe =
                                redactTransportError(error, currentResumeToken, nextResumeToken);
                        if (safe == error && Exceptions.isJvmFatal(error)) {
                            // Fatal synchronous errors cannot terminate the Mono sink. Release this
                            // exact scope before preserving the original fatal throwable.
                            releaseCall(scope);
                            Exceptions.throwIfFatal(error);
                        }
                        eventContext.completeExceptionally(safe);
                    }
                });
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return String.valueOf(metadata.get(key));
    }

    private static void logInboundEventStructure(String requestId, ClientEvent event) {
        String eventType = event == null ? "null" : event.getClass().getSimpleName();
        String taskId = null;
        TaskState state = null;
        boolean finalEvent = false;
        if (event instanceof TaskEvent taskEvent && taskEvent.getTask() != null) {
            taskId = taskEvent.getTask().id();
            if (taskEvent.getTask().status() != null) {
                state = taskEvent.getTask().status().state();
                finalEvent = isTerminalTaskState(state);
            }
        } else if (event instanceof TaskUpdateEvent updateEvent) {
            if (updateEvent.getTask() != null) {
                taskId = updateEvent.getTask().id();
            }
            if (updateEvent.getUpdateEvent() instanceof TaskStatusUpdateEvent statusUpdate) {
                taskId = statusUpdate.taskId();
                state = statusUpdate.status() == null ? null : statusUpdate.status().state();
                finalEvent = statusUpdate.isFinalOrInterrupted();
            } else if (updateEvent.getUpdateEvent()
                    instanceof TaskArtifactUpdateEvent artifactUpdate) {
                taskId = artifactUpdate.taskId();
                finalEvent = Boolean.TRUE.equals(artifactUpdate.lastChunk());
            }
        } else if (event instanceof MessageEvent messageEvent) {
            taskId = messageEvent.getMessage() == null ? null : messageEvent.getMessage().taskId();
            finalEvent = true;
        }
        LoggerUtil.trace(
                log,
                "[{}] A2aAgent receive event type={} taskId={} state={} final={}.",
                requestId,
                eventType,
                taskId,
                state,
                finalEvent);
    }

    private static boolean isTerminalTaskState(TaskState state) {
        return state != null && (state.isFinal() || state.isInterrupted());
    }

    private static Throwable redactTransportError(
            Throwable error, String currentResumeToken, String nextResumeToken) {
        if (!diagnosticGraphContains(error, currentResumeToken)
                && !diagnosticGraphContains(error, nextResumeToken)) {
            return error;
        }
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            message = "A2A transport failed.";
        }
        message = redactKnownValue(message, currentResumeToken);
        message = redactKnownValue(message, nextResumeToken);
        if (error instanceof CancellationException) {
            return new CancellationException(message);
        }
        return new A2aTransportException(message);
    }

    private static boolean diagnosticGraphContains(Throwable error, String sensitiveValue) {
        if (error == null || sensitiveValue == null || sensitiveValue.isEmpty()) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> pending = new ArrayList<>();
        pending.add(error);
        while (!pending.isEmpty()) {
            Throwable current = pending.remove(pending.size() - 1);
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (contains(current.getMessage(), sensitiveValue)
                    || contains(current.getLocalizedMessage(), sensitiveValue)) {
                return true;
            }
            pending.add(current.getCause());
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean contains(String value, String expected) {
        return value != null && value.contains(expected);
    }

    private static String redactKnownValue(String message, String sensitiveValue) {
        if (sensitiveValue == null || sensitiveValue.isEmpty()) {
            return message;
        }
        return message.replace(sensitiveValue, "<redacted>");
    }

    private MessageSendParams buildMessageSendParams(CallScope scope, Message message) {
        ClientConfig clientConfig = a2aAgentConfig.clientConfig();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (clientConfig != null && clientConfig.getMetadata() != null) {
            metadata.putAll(MessageConvertUtil.stripSensitiveMetadata(clientConfig.getMetadata()));
        }
        HitlTurn hitlTurn = scope.invocation().hitlTurn();
        if (hitlTurn != null) {
            metadata.put(
                    MessageConstants.RESUME_TOKEN_METADATA_KEY, hitlTurn.handoff().resumeToken());
        }
        if (scope.nextResumeToken() != null) {
            metadata.put(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY, scope.nextResumeToken());
        }
        MessageSendConfiguration configuration = null;
        if (clientConfig != null) {
            configuration =
                    MessageSendConfiguration.builder()
                            .acceptedOutputModes(clientConfig.getAcceptedOutputModes())
                            .returnImmediately(clientConfig.isPolling())
                            .historyLength(clientConfig.getHistoryLength())
                            .taskPushNotificationConfig(
                                    clientConfig.getTaskPushNotificationConfig())
                            .build();
        }
        return MessageSendParams.builder()
                .message(message)
                .configuration(configuration)
                .metadata(metadata)
                .build();
    }

    private Message buildHitlMessage(CallScope scope, HitlTurn turn) {
        Map<String, Object> metadata =
                new LinkedHashMap<>(
                        MessageConvertUtil.stripSensitiveMetadata(
                                getCurrentRequestMetadata(scope)));
        metadata.remove(MessageConstants.HITL_OPERATION_METADATA_KEY);
        metadata.remove(MessageConstants.HANDOFF_ID_METADATA_KEY);
        metadata.remove(MessageConstants.HITL_RESPONSES_METADATA_KEY);
        metadata.put(
                MessageConstants.HITL_OPERATION_METADATA_KEY,
                turn.operation() == HitlOperation.RESUME ? "resume" : "cancel");
        metadata.put(MessageConstants.HANDOFF_ID_METADATA_KEY, turn.handoff().handoffId());
        if (turn.operation() == HitlOperation.RESUME) {
            List<Map<String, Object>> responseData = new ArrayList<>();
            for (A2aHitlResponse response : turn.responses()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw =
                        JsonUtils.getJsonCodec().convertValue(response, Map.class);
                responseData.add(
                        MessageConvertUtil.protobufSafeMap(
                                MessageConvertUtil.stripSensitiveMetadata(raw)));
            }
            metadata.put(MessageConstants.HITL_RESPONSES_METADATA_KEY, responseData);
        }
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(
                        new TextPart(
                                turn.operation() == HitlOperation.RESUME
                                        ? "A2A HITL resume"
                                        : "A2A HITL cancel"))
                .taskId(turn.handoff().taskId())
                .contextId(turn.handoff().contextId())
                .metadata(metadata)
                .build();
    }

    private List<A2aHitlResponse> validateResponses(
            A2aHandoff handoff, List<? extends A2aHitlResponse> responses) {
        Objects.requireNonNull(handoff, "handoff must not be null");
        List<? extends A2aHitlResponse> supplied = responses == null ? List.of() : responses;
        Set<String> pendingIds =
                handoff.pendingTools().stream()
                        .map(A2aPendingTool::toolCallId)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> suppliedIds = new HashSet<>();
        for (A2aHitlResponse response : supplied) {
            Objects.requireNonNull(response, "responses must not contain null");
            if (!suppliedIds.add(response.toolCallId())) {
                throw new IllegalArgumentException(
                        "HITL responses must contain each pending tool exactly once");
            }
            boolean expectedType =
                    handoff.type() == A2aHandoffType.USER_CONFIRM
                            ? response instanceof A2aUserConfirmation
                            : response instanceof A2aExternalToolResponse;
            if (!expectedType) {
                throw new IllegalArgumentException(
                        "HITL response type does not match handoff type");
            }
        }
        if (!pendingIds.equals(suppliedIds)) {
            throw new IllegalArgumentException(
                    "HITL responses must answer exactly the complete pending tool set");
        }
        return List.copyOf(supplied);
    }

    private String getCurrentContextId(CallScope scope) {
        RuntimeContext runtimeContext = scope.invocation().options().runtimeContext();
        if (runtimeContext == null || !hasText(runtimeContext.getSessionId())) {
            return null;
        }
        return runtimeContext.getSessionId().trim();
    }

    private Map<String, Object> getCurrentRequestMetadata(CallScope scope) {
        A2aRequestOptions requestOptions = scope.invocation().options();
        Map<String, Object> metadata = new LinkedHashMap<>(a2aAgentConfig.defaultMetadata());
        metadata.putAll(requestOptions.metadata());
        if (hasText(requestOptions.agentId())) {
            metadata.put("agentId", requestOptions.agentId().trim());
        }
        RuntimeContext runtimeContext = requestOptions.runtimeContext();
        if (runtimeContext != null) {
            if (hasText(runtimeContext.getSessionId())) {
                metadata.put("sessionId", runtimeContext.getSessionId().trim());
            }
            if (hasText(runtimeContext.getUserId())) {
                metadata.put("userId", runtimeContext.getUserId().trim());
            }
        }
        return MessageConvertUtil.stripSensitiveMetadata(metadata);
    }

    private ClientCallContext buildClientCallContext(CallScope scope) {
        Map<String, String> headers = new LinkedHashMap<>(a2aAgentConfig.defaultHeaders());
        headers.putAll(scope.invocation().options().headers());
        if (headers.isEmpty()) {
            return null;
        }
        return new ClientCallContext(Map.of(), headers);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String generateResumeToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private class A2aClientLifecycleHook implements Hook {

        /**
         * According to {@link Hook#priority()} comment, value `500` is the lowest priority in Normal(business logic).
         */
        private static final int HOOK_PRIORITY = 500;

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (!(event instanceof PreCallEvent)
                    && !(event instanceof PostCallEvent)
                    && !(event instanceof ErrorEvent)) {
                return Mono.just(event);
            }
            return Mono.deferContextual(
                    contextView -> {
                        CallScope scope = requireCallScope(contextView);
                        if (event instanceof PreCallEvent preCallEvent) {
                            scope.requestId(UUID.randomUUID().toString());
                            scope.eventContext(
                                    new ClientEventContext(scope.requestId(), A2aAgent.this));
                            HitlTurn hitlTurn = scope.invocation().hitlTurn();
                            if (hitlTurn != null) {
                                scope.eventContext()
                                        .ignorePriorHandoffSnapshot(
                                                hitlTurn.handoff().taskId(),
                                                hitlTurn.handoff().contextId(),
                                                hitlTurn.handoff().handoffId());
                            }
                            scope.nextResumeToken(
                                    hitlTurn != null && hitlTurn.operation() == HitlOperation.CANCEL
                                            ? null
                                            : generateResumeToken());
                            scope.callerNextResumeToken().set(scope.nextResumeToken());
                            Client built = buildA2aClient(preCallEvent.getAgent().getName());
                            scope.adoptClient(built);
                            a2aClient = built;
                            LoggerUtil.debug(
                                    log,
                                    "[{}] A2aAgent build A2a Client with Agent Card: {}.",
                                    scope.requestId(),
                                    agentCardResolver.getAgentCard(getName()));
                        } else if (event instanceof PostCallEvent) {
                            releaseTransport(scope);
                        } else if (event instanceof ErrorEvent errorEvent) {
                            releaseTransport(scope);
                            LoggerUtil.error(
                                    log,
                                    "[{}] A2aAgent execute error.",
                                    scope.requestId(),
                                    errorEvent.getError());
                        }
                        return Mono.just(event);
                    });
        }

        @Override
        public int priority() {
            return HOOK_PRIORITY;
        }
    }

    private CallScope requireCallScope(reactor.util.context.ContextView contextView) {
        CallScope scope = contextView.getOrDefault(CALL_SCOPE_CONTEXT_KEY, null);
        if (scope == null) {
            throw new IllegalStateException("A2aAgent call scope is unavailable");
        }
        return scope;
    }

    private void releaseCall(CallScope scope) {
        try {
            releaseTransport(scope);
        } finally {
            scope.callerNextResumeToken().set(null);
            activeCall.compareAndSet(scope, null);
        }
    }

    private void abandonCall(CallScope scope) {
        ClientEventContext eventContext = scope.eventContext();
        if (eventContext != null) {
            eventContext.abandon();
        }
    }

    private void releaseTransport(CallScope scope) {
        Client client = scope.clearClient();
        if (client == null) {
            return;
        }
        try {
            client.close();
            LoggerUtil.debug(log, "[{}] A2aAgent close A2a Client.", scope.requestId());
        } catch (Throwable closeError) {
            // Cleanup is best effort and must not replace the selected business terminal. Log only
            // the class so a credential-bearing close diagnostic cannot cross this boundary.
            LoggerUtil.warn(
                    log,
                    "[{}] A2aAgent client close failed with {}.",
                    scope.requestId(),
                    closeError.getClass().getName());
        } finally {
            if (a2aClient == client) {
                a2aClient = null;
            }
        }
    }

    private enum HitlOperation {
        RESUME,
        CANCEL
    }

    private record HitlTurn(
            HitlOperation operation, A2aHandoff handoff, List<A2aHitlResponse> responses) {}

    private record Invocation(A2aRequestOptions options, HitlTurn hitlTurn) {}

    private static final class CallScope {

        private final Invocation invocation;
        private final AtomicReference<String> callerNextResumeToken = new AtomicReference<>();
        private final AtomicReference<Client> client = new AtomicReference<>();
        private volatile String requestId;
        private volatile ClientEventContext eventContext;
        private volatile ClientCallContext clientCallContext;
        private volatile String nextResumeToken;

        private CallScope(Invocation invocation) {
            this.invocation = invocation;
        }

        private Invocation invocation() {
            return invocation;
        }

        private AtomicReference<String> callerNextResumeToken() {
            return callerNextResumeToken;
        }

        private String requestId() {
            return requestId;
        }

        private void requestId(String requestId) {
            this.requestId = requestId;
        }

        private ClientEventContext eventContext() {
            return eventContext;
        }

        private void eventContext(ClientEventContext eventContext) {
            this.eventContext = eventContext;
        }

        private ClientCallContext clientCallContext() {
            return clientCallContext;
        }

        private void clientCallContext(ClientCallContext clientCallContext) {
            this.clientCallContext = clientCallContext;
        }

        private String nextResumeToken() {
            return nextResumeToken;
        }

        private void nextResumeToken(String nextResumeToken) {
            this.nextResumeToken = nextResumeToken;
        }

        private void adoptClient(Client value) {
            if (value != null) {
                client.set(value);
            }
        }

        private Client client() {
            Client value = client.get();
            if (value == null) {
                throw new IllegalStateException("A2aAgent client is unavailable");
            }
            return value;
        }

        private Client clearClient() {
            return client.getAndSet(null);
        }
    }

    private static final class A2aTransportException extends RuntimeException {

        private A2aTransportException(String message) {
            super(message);
        }
    }

    public static class Builder {

        private String name;

        private AgentCardResolver agentCardResolver;

        private A2aAgentConfig a2aAgentConfig;

        private Memory memory = new InMemoryMemory();

        private boolean checkRunning = true;

        private final List<Hook> hooks = new ArrayList<>();

        /**
         * Set the name of the A2aAgent.
         *
         * @param name the name to set
         * @return the current Builder instance for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the {@link AgentCard} for the A2aAgent.
         *
         * <p>It will be auto-generated to {@link FixedAgentCardResolver}.
         *
         * @param agentCard the AgentCard to set
         * @return the current Builder instance for method chaining
         * @see #agentCardResolver(AgentCardResolver)
         */
        public Builder agentCard(AgentCard agentCard) {
            return agentCardResolver(FixedAgentCardResolver.builder().agentCard(agentCard).build());
        }

        /**
         * Set the {@link AgentCardResolver} for the A2aAgent.
         *
         * <p>When both {@link #agentCard(AgentCard)} and this method are called on the same builder, the later call
         * will override the earlier one.
         *
         * @param agentCardResolver the AgentCardResolver to set, null value will be ignored.
         * @return the current Builder instance for method chaining
         */
        public Builder agentCardResolver(AgentCardResolver agentCardResolver) {
            if (null == agentCardResolver) {
                return this;
            }
            if (null != this.agentCardResolver) {
                LoggerUtil.warn(
                        log,
                        "agentCardResolver {} will be replaced by {}",
                        this.agentCardResolver.getClass().getSimpleName(),
                        agentCardResolver.getClass().getSimpleName());
            }
            this.agentCardResolver = agentCardResolver;
            return this;
        }

        /**
         * Set the {@link A2aAgentConfig} for the A2aAgent.
         *
         * @param a2aAgentConfig the A2aAgentConfig to set
         * @return the current Builder instance for method chaining
         */
        public Builder a2aAgentConfig(A2aAgentConfig a2aAgentConfig) {
            this.a2aAgentConfig = a2aAgentConfig;
            return this;
        }

        /**
         * Set the {@link Memory} for the A2aAgent.
         *
         * <p>Default is {@link InMemoryMemory}
         *
         * @param memory the Memory to set
         * @return the current Builder instance for method chaining
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * Set whether to check the running status of the A2aAgent.
         *
         * <p>Default is true
         *
         * @param checkRunning true to check the running status, false to ignore
         * @return the current Builder instance for method chaining
         */
        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * Add a {@link Hook} to the A2aAgent.
         *
         * @param hook the Hook to add
         * @return the current Builder instance for method chaining
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * Add multiple {@link Hook}s to the A2aAgent.
         *
         * @param hooks the list of Hooks to add
         * @return the current Builder instance for method chaining
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * Build the A2aAgent instance.
         *
         * @return the built A2aAgent instance
         * @throws IllegalArgumentException if agentCardResolver is not set
         */
        public A2aAgent build() {
            if (null == this.agentCardResolver) {
                throw new IllegalArgumentException("agentCardResolver is required");
            }
            if (null == this.a2aAgentConfig) {
                this.a2aAgentConfig = A2aAgentConfig.builder().build();
            }
            return new A2aAgent(
                    this.name,
                    getDescriptionFromAgentCard(),
                    this.checkRunning,
                    this.memory,
                    this.hooks,
                    this.agentCardResolver,
                    this.a2aAgentConfig);
        }

        private String getDescriptionFromAgentCard() {
            try {
                AgentCard agentCard = this.agentCardResolver.getAgentCard(this.name);
                return null != agentCard ? agentCard.description() : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
