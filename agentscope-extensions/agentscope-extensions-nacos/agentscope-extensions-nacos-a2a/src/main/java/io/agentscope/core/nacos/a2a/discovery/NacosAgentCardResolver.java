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

package io.agentscope.core.nacos.a2a.discovery;

import com.alibaba.nacos.api.ability.constant.AbilityStatus;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Agent Card Producer from Nacos A2A Registry.
 *
 * <p>Example Usage:
 * <pre>{@code
 *  Properties properties = new Properties();
 *  properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
 *  // put other Nacos server properties
 *  // create NacosAgentCardResolver with new Nacos Client
 *  NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(properties);
 *  // Equals like:
 *  // AiService a2aService = AiFactory.createAiService(properties);
 *  // NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(a2aService)
 *
 *  // Or Reuse Nacos Client
 *  AiService a2aService = getAiServiceFromCacheOrOtherComponent();
 *  NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(a2aService);
 *
 *  // Then new A2aAgent
 *  A2aAgent a2aAgent = A2aAgent.builder().name("remote-agent-name").agentCardResolver(nacosAgentCardResolver).build();
 * }</pre>
 *
 * <p>On the first lookup, discovery selects RAD when the Nacos SDK reports support, or A2A
 * when it reports no support. Unknown capability and query failures are reported to the caller;
 * a later lookup may retry the query. Once selected, the discovery API is fixed for this resolver.
 * Set {@code agentscope.nacos.a2a.discovery.mode=a2a} or {@code rad} to bypass automatic selection.
 * This override is read once at construction; the default value is {@code auto}.
 *
 * <p>A2A retains its last Card while an agent is absent. RAD clears the Card on an unavailable
 * event and waits up to ten seconds for its first callback after subscribing. Conversion failures
 * are reported on lookup until another valid snapshot arrives.
 *
 * @see A2aAgent
 * @see A2aAgent.Builder
 */
public class NacosAgentCardResolver implements AgentCardResolver, AutoCloseable {
    private static final String DISCOVERY_MODE_PROPERTY = "agentscope.nacos.a2a.discovery.mode";

    private final AiService aiService;
    private final boolean ownsClient;
    private final String mode;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private Delegate delegate;
    private boolean closed;

    /**
     * Creates a new instance for {@link NacosAgentCardResolver}.
     *
     * <p>The resolver creates its own Nacos AI service and shuts it down on {@link #close()}.
     *
     * @param properties properties for nacos server to create nacos client AI service
     * @throws NacosException during building nacos client
     * @throws IllegalArgumentException if the discovery mode is not auto, a2a, or rad
     */
    public NacosAgentCardResolver(Properties properties) throws NacosException {
        this(properties, discoveryMode());
    }

    private NacosAgentCardResolver(Properties properties, String mode) throws NacosException {
        this(AiFactory.createAiService(properties), true, mode);
    }

    /**
     * Creates a new instance for {@link NacosAgentCardResolver}.
     *
     * <p>The supplied service remains managed by the caller and is not shut down on {@link #close()}.
     *
     * @param aiService nacos client AI service
     * @throws NullPointerException if aiService is null
     * @throws IllegalArgumentException if the discovery mode is not auto, a2a, or rad
     */
    public NacosAgentCardResolver(AiService aiService) {
        this(aiService, false, discoveryMode());
    }

    private NacosAgentCardResolver(AiService aiService, boolean ownsClient, String mode) {
        this.aiService = Objects.requireNonNull(aiService, "aiService");
        this.ownsClient = ownsClient;
        this.mode = mode;
    }

    private static String discoveryMode() {
        String mode =
                System.getProperty(DISCOVERY_MODE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode) && !"a2a".equals(mode) && !"rad".equals(mode)) {
            throw new IllegalArgumentException(
                    DISCOVERY_MODE_PROPERTY + " must be auto, a2a, or rad");
        }
        return mode;
    }

    /**
     * Gets the cached AgentCard, subscribing through the selected API on the first lookup.
     *
     * @param agentName name of the remote agent
     * @return the latest observed AgentCard, or null when no Card is available
     * @throws IllegalStateException if this resolver is closed or automatic selection has no
     *     conclusive capability evidence, or waiting for the first RAD callback times out or is
     *     interrupted
     * @throws NacosRuntimeException if capability lookup or subscription fails
     * @throws IllegalArgumentException if the current RAD snapshot cannot be converted to a Card
     */
    @Override
    public AgentCard getAgentCard(String agentName) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Agent card resolver is closed");
            return getDelegate().getAgentCard(agentName);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private synchronized Delegate getDelegate() {
        if (delegate == null) {
            String selected = mode;
            if ("auto".equals(selected)) {
                AbilityStatus capability;
                try {
                    capability = aiService.getAgentDiscoveryCapability();
                } catch (NacosException e) {
                    throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
                }
                if (capability == AbilityStatus.SUPPORTED) selected = "rad";
                else if (capability == AbilityStatus.NOT_SUPPORTED) selected = "a2a";
                else
                    throw new IllegalStateException(
                            "Nacos Agent discovery capability is unknown; retry after connection "
                                    + "or explicitly configure "
                                    + DISCOVERY_MODE_PROPERTY);
            }
            delegate =
                    "rad".equals(selected)
                            ? new RadAgentCardResolver(aiService)
                            : new A2aAgentCardResolver(aiService);
        }
        return delegate;
    }

    /**
     * Cancels this resolver's subscriptions and clears its cached agent cards.
     *
     * <p>Shuts down the Nacos AI service only when it was created by the {@link
     * #NacosAgentCardResolver(Properties)} constructor. Further lookups are rejected; repeated
     * calls to {@code close} have no effect. Closing waits for in-flight lookups to finish before
     * cancelling subscriptions, so a concurrent first lookup cannot leave a subscription behind.
     *
     * @throws NacosException if unsubscribing or shutting down the service fails; additional
     *     failures are attached as suppressed exceptions
     */
    @Override
    public void close() throws NacosException {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            NacosException failure = null;
            try {
                if (delegate != null) delegate.close();
            } catch (NacosException e) {
                failure = e;
            }
            if (ownsClient) {
                try {
                    aiService.shutdown();
                } catch (NacosException e) {
                    failure = accumulate(failure, e);
                }
            }
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    static NacosException accumulate(NacosException first, NacosException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    /** Internal strategy supporting concurrent lookups; the owner excludes lookups during closure. */
    interface Delegate extends AgentCardResolver {
        /**
         * Cancels this strategy's subscriptions without shutting down the shared Nacos client.
         *
         * @throws NacosException if subscription cleanup fails
         */
        void close() throws NacosException;
    }
}
