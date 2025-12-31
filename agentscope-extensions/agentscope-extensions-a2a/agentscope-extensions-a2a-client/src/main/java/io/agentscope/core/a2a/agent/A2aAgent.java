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

import io.a2a.client.Client;
import io.a2a.client.ClientBuilder;
import io.a2a.client.ClientEvent;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.TaskIdParams;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.FixedAgentCardResolver;
import io.agentscope.core.a2a.agent.event.ClientEventContext;
import io.agentscope.core.a2a.agent.event.ClientEventHandlerRouter;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final AgentCardResolver agentCardResolver;

    private final A2aAgentConfig a2aAgentConfig;

    private final Memory memory;

    private final ClientEventHandlerRouter clientEventHandlerRouter;

    private Client a2aClient;

    /**
     * According to the design, one agent should not be call with multiple threads and tasks at the same time.
     */
    private String currentRequestId;

    /**
     * The context of client event, one agent should not be call with multiple threads and tasks at the same time.
     */
    private ClientEventContext clientEventContext;

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
        getHooks().add(new A2aClientLifecycleHook());
        this.clientEventHandlerRouter = new ClientEventHandlerRouter();
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }
        LoggerUtil.info(log, "[{}] A2aAgent start call.", currentRequestId);
        LoggerUtil.debug(log, "[{}] A2aAgent call with input messages: ", currentRequestId);
        LoggerUtil.logTextMsgDetail(log, memory.getMessages());
        clientEventContext.setHooks(getSortedHooks());
        return Mono.defer(
                () -> {
                    Message message = MessageConvertUtil.convertFromMsg(memory.getMessages());
                    return checkInterruptedAsync().then(doExecute(message));
                });
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
        LoggerUtil.debug(log, "[{}] A2aAgent handle interrupt.", currentRequestId);
        try {
            String taskId = clientEventContext.getTask().getId();
            TaskIdParams taskIdParams = new TaskIdParams(taskId);
            a2aClient.cancelTask(taskIdParams, null);
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

    private Mono<Msg> doExecute(Message message) {
        return Mono.create(
                sink -> {
                    clientEventContext.setSink(sink);
                    BiConsumer<ClientEvent, AgentCard> a2aEventConsumer =
                            (event, agentCard) -> {
                                LoggerUtil.trace(
                                        log,
                                        "[{}] A2aAgent receive event {}: ",
                                        currentRequestId,
                                        event.getClass().getSimpleName());
                                LoggerUtil.logA2aClientEventDetail(log, event);
                                clientEventHandlerRouter.handle(event, clientEventContext);
                            };
                    a2aClient.sendMessage(message, List.of(a2aEventConsumer), sink::error);
                });
    }

    private class A2aClientLifecycleHook implements Hook {

        /**
         * According to {@link Hook#priority()} comment, value `500` is the lowest priority in Normal(business logic).
         */
        private static final int HOOK_PRIORITY = 500;

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreCallEvent preCallEvent) {
                currentRequestId = UUID.randomUUID().toString();
                clientEventContext = new ClientEventContext(currentRequestId, A2aAgent.this);
                a2aClient = buildA2aClient(preCallEvent.getAgent().getName());
                LoggerUtil.debug(
                        log,
                        "[{}] A2aAgent build A2a Client with Agent Card: {}.",
                        currentRequestId,
                        agentCardResolver.getAgentCard(getName()));
            } else if (event instanceof PostCallEvent) {
                tryReleaseResource();
            } else if (event instanceof ErrorEvent errorEvent) {
                tryReleaseResource();
                LoggerUtil.error(
                        log,
                        "[{}] A2aAgent execute error.",
                        currentRequestId,
                        errorEvent.getError());
            }
            return Mono.just(event);
        }

        @Override
        public int priority() {
            return HOOK_PRIORITY;
        }

        private void tryReleaseResource() {
            clientEventContext = null;
            if (null != a2aClient) {
                a2aClient.close();
                a2aClient = null;
                LoggerUtil.debug(log, "[{}] A2aAgent close A2a Client.", currentRequestId);
            }
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
