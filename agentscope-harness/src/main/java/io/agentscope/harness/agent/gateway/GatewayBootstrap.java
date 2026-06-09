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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Multi-agent + channel routing bootstrap. The primary user-facing entry point for building a
 * gateway-managed agent system.
 *
 * <h2>Minimal usage — single agent + ChatUI</h2>
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(model).name("assistant").sysPrompt("You are helpful.")
 *     .build();
 *
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel();
 * Msg reply = chat.send("hello").block();
 * }</pre>
 *
 * <h2>Multi-agent + binding routing</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("sales", salesAgent)
 *     .agent("support", supportAgent)
 *     .mainAgent("sales")
 *     .build();
 *
 * ChannelConfig config = ChannelConfig.builder("chatui")
 *     .dmScope(DmScope.PER_PEER)
 *     .binding(ChannelBinding.forPeer("direct:vip-user-1", "support"))
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel(config);
 * chat.send("vip-user-1", "help me").block();  // routes to support agent
 * chat.send("normal-user", "hi").block();      // routes to sales (default)
 * }</pre>
 *
 * <h2>External channels</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .channel(mySlackChannel)
 *     .build();
 *
 * gw.start();   // init + start all channels
 * gw.stop();    // stop all channels
 * }</pre>
 */
public final class GatewayBootstrap {

    private final HarnessGateway gateway;
    private final ChannelManager channelManager;
    private final Map<String, HarnessAgent> agents;
    private final String mainAgentId;

    private GatewayBootstrap(
            HarnessGateway gateway,
            ChannelManager channelManager,
            Map<String, HarnessAgent> agents,
            String mainAgentId) {
        this.gateway = gateway;
        this.channelManager = channelManager;
        this.agents = Collections.unmodifiableMap(agents);
        this.mainAgentId = mainAgentId;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The underlying gateway (advanced usage). */
    public HarnessGateway gateway() {
        return gateway;
    }

    /** The channel manager (advanced usage). */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /** The registered agents keyed by id. */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    /** The main agent id. */
    public String mainAgentId() {
        return mainAgentId;
    }

    /**
     * Returns a {@link SubagentGatewayBridge} that exposes subagents as user-addressable threads
     * within this gateway. Pass this to
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#setGatewayBridge} to enable
     * the {@code expose_to_user} parameter on {@code agent_spawn}.
     */
    public SubagentGatewayBridge gatewayBridge() {
        return (agentId, sessionId, agent, replyTo) -> {
            String subagentId = gateway.exposeSubagent(agentId, sessionId, agent, replyTo);
            return new SubagentGatewayBridge.ExposeResult(subagentId);
        };
    }

    /**
     * Returns a {@link ChatUiChannel} with default DmScope.MAIN config, pre-wired to this
     * gateway. All conversations share a single session.
     */
    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(gateway);
    }

    /**
     * Returns a {@link ChatUiChannel} with custom routing config, pre-wired to this gateway.
     * Use to configure DmScope, bindings, or default agent overrides.
     */
    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(gateway, config);
    }

    /**
     * Initializes and starts all pre-registered channels (injecting the gateway into each).
     * Call this after build() when using external channels (Slack, Telegram, etc.).
     */
    public GatewayBootstrap start() {
        channelManager.initAll(gateway);
        channelManager.startAll();
        return this;
    }

    /** Stops all channels and releases resources. */
    public void stop() {
        channelManager.stopAll();
    }

    // =========================================================================
    //  Builder
    // =========================================================================

    public static final class Builder {

        private final LinkedHashMap<String, HarnessAgent> agents = new LinkedHashMap<>();
        private String mainAgentId;
        private final List<Channel> channels = new ArrayList<>();
        private Consumer<HarnessAgent.Builder> agentCustomizer;
        private DistributedStore distributedStore;

        private Builder() {}

        /** Registers a pre-built agent under the given id. */
        public Builder agent(String id, HarnessAgent agent) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(agent, "agent");
            agents.put(id, agent);
            return this;
        }

        /**
         * Registers an agent declared via a builder lambda. The lambda receives a
         * {@link HarnessAgent.Builder} pre-configured with any global customizer.
         */
        public Builder agent(String id, Consumer<HarnessAgent.Builder> configurator) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configurator, "configurator");
            HarnessAgent.Builder b = HarnessAgent.builder();
            if (agentCustomizer != null) {
                agentCustomizer.accept(b);
            }
            configurator.accept(b);
            agents.put(id, b.build());
            return this;
        }

        /**
         * Sets the main agent id (used as the routing fallback). If not called, the first
         * registered agent becomes the main agent.
         */
        public Builder mainAgent(String id) {
            this.mainAgentId = id;
            return this;
        }

        /** Registers one or more external channels for gateway management. */
        public Builder channel(Channel... channels) {
            for (Channel ch : channels) {
                this.channels.add(Objects.requireNonNull(ch, "channel"));
            }
            return this;
        }

        /**
         * Applies a customizer to every agent builder created via the lambda-based
         * {@link #agent(String, Consumer)} method. Useful for setting a shared model or workspace.
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            this.agentCustomizer = customizer;
            return this;
        }

        /**
         * Sets the distributed store used to build a durable
         * {@link SubagentRegistry}, making subagents exposed via {@code expose_to_user} resolvable
         * and re-materializable across nodes / restarts. When not set, the main agent's own
         * {@code distributedStore} (if any) is used as a fallback; otherwise exposure stays
         * in-process.
         */
        public Builder distributedStore(DistributedStore store) {
            this.distributedStore = store;
            return this;
        }

        /**
         * Builds the gateway bootstrap. At least one agent must be registered.
         *
         * @throws IllegalStateException if no agents are registered
         */
        public GatewayBootstrap build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException(
                        "At least one agent must be registered via agent(...)");
            }

            String resolvedMainId = mainAgentId;
            if (resolvedMainId == null) {
                resolvedMainId = agents.keySet().iterator().next();
            }
            if (!agents.containsKey(resolvedMainId)) {
                throw new IllegalStateException(
                        "mainAgent('" + resolvedMainId + "') not found in registered agents");
            }

            ChannelManager cm = new ChannelManager();
            for (Channel ch : channels) {
                cm.register(ch);
            }

            HarnessGateway gw = HarnessGateway.create(cm);

            // Register all agents, bind the main one
            HarnessAgent mainHa = agents.get(resolvedMainId);
            gw.bindMainAgent(mainHa);
            for (Map.Entry<String, HarnessAgent> entry : agents.entrySet()) {
                if (!entry.getKey().equals(resolvedMainId)) {
                    gw.registerAgent(entry.getKey(), entry.getValue());
                }
            }

            // ---- Exposed-subagent recovery wiring (cross-node / post-restart) ----
            // A composite materializer rebuilds a subagent on any node by trying each registered
            // agent's manager in turn; a durable registry (when a distributed store is present)
            // makes the subagentId resolvable beyond this process. Without these, exposure stays
            // in-process (legacy behaviour).
            List<DefaultAgentManager> managers =
                    agents.values().stream()
                            .map(HarnessAgent::getSubagentAgentManager)
                            .filter(Objects::nonNull)
                            .toList();
            if (!managers.isEmpty()) {
                gw.setSubagentMaterializer(
                        (agentId, rc) -> {
                            for (DefaultAgentManager m : managers) {
                                Optional<Agent> agent = m.createAgentIfPresent(agentId, rc);
                                if (agent.isPresent()) {
                                    return agent;
                                }
                            }
                            return Optional.empty();
                        });
            }
            DistributedStore store = distributedStore;
            if (store == null) {
                store = mainHa.getDistributedStore();
            }
            if (store != null) {
                gw.setSubagentRegistry(new StoreBackedSubagentRegistry(store.baseStore()));
            }

            return new GatewayBootstrap(gw, cm, new LinkedHashMap<>(agents), resolvedMainId);
        }
    }
}
