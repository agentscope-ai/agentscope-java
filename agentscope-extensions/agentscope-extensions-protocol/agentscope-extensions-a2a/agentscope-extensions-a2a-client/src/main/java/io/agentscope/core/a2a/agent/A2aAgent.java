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
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

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

    /** Reactor context key of the {@link ReplyEvents} of a call. */
    private static final String EVENT_EMITTER_KEY = A2aAgent.class.getName() + ".replyEvents";

    private final AgentCardResolver agentCardResolver;

    private final A2aAgentConfig a2aAgentConfig;

    private final Memory memory;

    private final ClientEventHandlerRouter clientEventHandlerRouter;

    /** Replaces the client built for a call, for tests; {@code null} to keep it. */
    private final UnaryOperator<Client> clientReplacer;

    /**
     * The call in progress, which {@link #interrupt()} cancels. According to the design, one agent
     * should not be called with multiple threads and tasks at the same time.
     */
    private final AtomicReference<A2aCall> currentCall = new AtomicReference<>();

    private A2aAgent(
            String name,
            String description,
            Memory memory,
            List<Hook> hooks,
            AgentCardResolver agentCardResolver,
            A2aAgentConfig a2aAgentConfig,
            UnaryOperator<Client> clientReplacer) {
        super(name, description, hooks);
        this.a2aAgentConfig = a2aAgentConfig;
        this.agentCardResolver = agentCardResolver;
        this.memory = memory;
        this.clientReplacer = clientReplacer;
        LoggerUtil.debug(log, "A2aAgent init with config: {}", a2aAgentConfig);
        this.clientEventHandlerRouter = new ClientEventHandlerRouter();
    }

    /**
     * Calls the remote agent and streams the events of the call: {@link AgentStartEvent}, the
     * text and thinking the remote agent streams as {@link TextBlockDeltaEvent}s and {@link
     * ThinkingBlockDeltaEvent}s, {@link AgentResultEvent} with the message {@code call()} returns,
     * and {@link AgentEndEvent}. Tool calls and tool results are only in the result message. A
     * failed call ends the stream with its error.
     *
     * @param msgs the input messages
     * @return the events of the call
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, null);
    }

    /**
     * Calls the remote agent and streams the events of the call, see {@link
     * #streamEvents(List)}.
     *
     * @param msg the input message
     * @return the events of the call
     */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg), null);
    }

    /**
     * Calls the remote agent and streams the events of the call, see {@link
     * #streamEvents(List)}.
     *
     * @param msgs the input messages
     * @param context the context of the call, or {@code null}
     * @return the events of the call
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
        return buildAgentStream(msgs, context, this::doCall);
    }

    /** {@code call()} returns the result of the same event stream, so that both agree. */
    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return buildAgentStream(msgs, context, doCallFn)
                .ofType(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .takeLast(1)
                .next();
    }

    private Flux<AgentEvent> buildAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return Flux.create(
                sink -> {
                    sink.next(
                            new AgentStartEvent(
                                    null == context ? null : context.getSessionId(),
                                    replyId,
                                    getName()));
                    Mono<Msg> lifecycle = runLifecycle(msgs, doCallFn);
                    if (null != context) {
                        lifecycle =
                                lifecycle.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
                    }
                    Disposable disposable =
                            lifecycle
                                    .contextWrite(
                                            c ->
                                                    c.put(
                                                            EVENT_EMITTER_KEY,
                                                            new ReplyEvents(replyId, sink::next)))
                                    .doFinally(
                                            signal -> {
                                                sink.next(new AgentEndEvent(replyId));
                                                sink.complete();
                                            })
                                    .contextWrite(sink.contextView())
                                    .subscribe(
                                            result -> sink.next(new AgentResultEvent(result)),
                                            sink::error);
                    sink.onCancel(disposable);
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }
        // The client is closed however the call ends, also when it is cancelled.
        return Mono.deferContextual(
                        view ->
                                Mono.using(
                                        this::startCall,
                                        call -> {
                                            ReplyEvents events =
                                                    view.getOrDefault(EVENT_EMITTER_KEY, null);
                                            if (null != events) {
                                                call.eventContext()
                                                        .setEventEmitter(
                                                                events.replyId(), events.emitter());
                                            }
                                            return execute(call);
                                        },
                                        this::endCall))
                .doOnNext(this.memory::addMessage);
    }

    private A2aCall startCall() {
        String requestId = UUID.randomUUID().toString();
        Client client = buildA2aClient(getName());
        LoggerUtil.debug(
                log,
                "[{}] A2aAgent build A2a Client with Agent Card: {}.",
                requestId,
                agentCardResolver.getAgentCard(getName()));
        A2aCall call = new A2aCall(requestId, client, new ClientEventContext(requestId, this));
        currentCall.set(call);
        return call;
    }

    private Mono<Msg> execute(A2aCall call) {
        LoggerUtil.info(log, "[{}] A2aAgent start call.", call.requestId());
        LoggerUtil.debug(log, "[{}] A2aAgent call with input messages: ", call.requestId());
        LoggerUtil.logTextMsgDetail(log, memory.getMessages());
        call.eventContext().setHooks(getSortedHooks());
        call.eventContext().setInputMessages(memory.getMessages());
        return Mono.defer(
                        () -> {
                            Message message =
                                    MessageConvertUtil.convertFromMsg(memory.getMessages());
                            return checkInterruptedAsync().then(doExecute(call, message));
                        })
                .doOnError(
                        e ->
                                LoggerUtil.error(
                                        log, "[{}] A2aAgent execute error.", call.requestId(), e));
    }

    private void endCall(A2aCall call) {
        currentCall.compareAndSet(call, null);
        call.client().close();
        LoggerUtil.debug(log, "[{}] A2aAgent close A2a Client.", call.requestId());
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
        memory.addMessage(msg);
        return Mono.empty();
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        A2aCall call = currentCall.get();
        LoggerUtil.debug(log, "[{}] A2aAgent handle interrupt.", call.requestId());
        try {
            String taskId = call.eventContext().getTask().id();
            call.client().cancelTask(new CancelTaskParams(taskId), null);
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
        Client client = builder.build();
        return null == clientReplacer ? client : clientReplacer.apply(client);
    }

    private Mono<Msg> doExecute(A2aCall call, Message message) {
        return Mono.create(
                sink -> {
                    String requestId = call.requestId();
                    ClientEventContext eventContext = call.eventContext();
                    Client client = call.client();
                    eventContext.setSink(sink);
                    BiConsumer<ClientEvent, AgentCard> a2aEventConsumer =
                            (event, agentCard) -> {
                                LoggerUtil.trace(
                                        log,
                                        "[{}] A2aAgent receive event {}: ",
                                        requestId,
                                        event.getClass().getSimpleName());
                                LoggerUtil.logA2aClientEventDetail(log, event);
                                clientEventHandlerRouter.handle(event, eventContext);
                            };
                    client.sendMessage(
                            message,
                            List.of(a2aEventConsumer),
                            err -> {
                                if (err == null) {
                                    if (eventContext.isTerminalDelivered()) {
                                        return;
                                    }
                                    LoggerUtil.warn(
                                            log,
                                            "[{}] A2aAgent stream completed before final response.",
                                            requestId);
                                    eventContext.completeExceptionally(
                                            new IllegalStateException(
                                                    "A2A stream completed before final response."));
                                    return;
                                }
                                if (err instanceof CancellationException) {
                                    LoggerUtil.warn(
                                            log, "[{}] A2aAgent sendMessage cancelled.", requestId);
                                    eventContext.completeExceptionally(err);
                                    return;
                                }
                                eventContext.completeExceptionally(err);
                            });
                });
    }

    /** Where the events of a reply go. */
    private record ReplyEvents(String replyId, AgentEventEmitter emitter) {}

    /** The client and the event context of one call. */
    private record A2aCall(String requestId, Client client, ClientEventContext eventContext) {}

    public static class Builder {

        private String name;

        private AgentCardResolver agentCardResolver;

        private A2aAgentConfig a2aAgentConfig;

        private Memory memory = new InMemoryMemory();

        private final List<Hook> hooks = new ArrayList<>();

        private UnaryOperator<Client> clientReplacer;

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
         * Has no effect, see {@link AgentBase#AgentBase(String, String, boolean, List)}.
         *
         * @param checkRunning ignored
         * @return the current Builder instance for method chaining
         * @deprecated since 2.0.0, has no effect.
         */
        @Deprecated(since = "2.0.0")
        public Builder checkRunning(boolean checkRunning) {
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

        /** Replaces the client built for a call, for tests. */
        Builder clientReplacer(UnaryOperator<Client> clientReplacer) {
            this.clientReplacer = clientReplacer;
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
                    this.memory,
                    this.hooks,
                    this.agentCardResolver,
                    this.a2aAgentConfig,
                    this.clientReplacer);
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
