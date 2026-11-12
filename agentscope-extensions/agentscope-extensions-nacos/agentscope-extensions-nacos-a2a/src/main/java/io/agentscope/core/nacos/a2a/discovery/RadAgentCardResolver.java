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

import com.alibaba.nacos.api.ai.AgentService;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentDiscoveryListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentDiscoveryEvent;
import com.alibaba.nacos.api.ai.listener.NacosAgentDiscoveryEventType;
import com.alibaba.nacos.api.ai.model.agent.AgentDiscoveryFilter;
import com.alibaba.nacos.api.ai.model.agent.AgentReference;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Resolves AgentCards through the native Nacos RAD API for {@link NacosAgentCardResolver}.
 *
 * <p>Each subscription selects the agent's {@code latest} label and requests A2A call interfaces.
 * Discovery snapshots are converted into A2A AgentCards using their native descriptors and
 * discovered endpoints. An {@link NacosAgentDiscoveryEventType#UNAVAILABLE} event clears the
 * cached Card; a later snapshot can populate it through the same subscription. Watch recovery is
 * managed by the Nacos SDK, and this delegate does not resubscribe on cache misses.
 *
 * <p>Subscription initialization is serialized per agent, independently of callback synchronization.
 * Initial state and later updates use the same SDK callback channel. A lookup waiting for its first
 * callback does not block other agents' lookups. The owning resolver excludes lookups during closure;
 * closing this delegate cancels its subscriptions without shutting down the supplied {@link AiService}.
 *
 * @see NacosAgentCardResolver
 * @see RadAgentCardConverter
 */
final class RadAgentCardResolver implements NacosAgentCardResolver.Delegate {
    private final AiService client;
    private final Duration initialTimeout;
    private final AgentDiscoveryFilter filter = new AgentDiscoveryFilter();
    private final Map<String, CardUpdater> subscriptions = new ConcurrentHashMap<>();
    private AgentService service;

    /**
     * Creates a native RAD discovery delegate using an existing Nacos AI service.
     *
     * <p>The Agent service is obtained on the first lookup. The selected server must support RAD;
     * this delegate does not fall back to the A2A API when a RAD operation fails.
     *
     * @param client Nacos AI service whose lifecycle is managed by the owning resolver or caller
     */
    RadAgentCardResolver(AiService client) {
        this(client, Duration.ofSeconds(10));
    }

    /**
     * Creates a delegate with a bounded wait for the first discovery callback.
     *
     * @param client shared Nacos AI service
     * @param initialTimeout maximum wait after subscription returns, excluding SDK request time
     */
    RadAgentCardResolver(AiService client, Duration initialTimeout) {
        this.client = client;
        this.initialTimeout = initialTimeout;
        filter.setProtocols(List.of("a2a"));
    }

    /**
     * Gets the cached AgentCard, subscribing to RAD snapshots if no subscription has completed yet.
     *
     * <p>After subscription, lookups use the state maintained by discovery callbacks, including
     * {@code null} after an unavailable event, without creating another subscription.
     *
     * @param agentName name of the remote agent, resolved through its {@code latest} label
     * @return the latest AgentCard, or {@code null} after a confirmed unavailable event
     * @throws NacosRuntimeException if subscribing fails, preserving its error code and cause
     * @throws IllegalStateException if waiting for the first callback times out or is interrupted;
     *     a later lookup reuses the accepted subscription
     * @throws IllegalArgumentException if the current snapshot cannot be converted to an A2A Card
     */
    @Override
    public AgentCard getAgentCard(String agentName) {
        AgentService agentService = getService();
        CardUpdater updater = subscriptions.computeIfAbsent(agentName, CardUpdater::new);
        synchronized (updater.subscriptionLock) {
            if (!updater.initialized) {
                try {
                    agentService.subscribeAgent(updater.reference, filter, updater);
                    updater.initialized = true;
                } catch (NacosException e) {
                    throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
                }
            }
        }
        try {
            if (updater.firstEvent.getCount() != 0
                    && !updater.firstEvent.await(
                            initialTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for the first RAD state for " + agentName,
                        new TimeoutException("RAD initial callback has not arrived"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for the first RAD state for " + agentName, e);
        }
        synchronized (updater) {
            if (updater.failure != null) throw updater.failure;
            return updater.card;
        }
    }

    private synchronized AgentService getService() {
        if (service == null) service = client.agent();
        return service;
    }

    /**
     * Cancels all RAD subscriptions created by this delegate and clears its cached Cards.
     *
     * <p>Unsubscribes with the same agent reference and protocol filter used at subscription time.
     * Late callbacks are ignored, and every subscription is processed even if an unsubscribe
     * fails. The supplied Nacos AI service remains open.
     *
     * @throws NacosException if unsubscribing fails; additional failures are attached as
     *     suppressed exceptions
     */
    @Override
    public void close() throws NacosException {
        NacosException failure = null;
        for (CardUpdater updater : subscriptions.values()) {
            synchronized (updater) {
                updater.active = false;
                updater.card = null;
                updater.failure = null;
                updater.firstEvent.countDown();
            }
            try {
                service.unsubscribeAgent(updater.reference, filter, updater);
            } catch (NacosException e) {
                failure = NacosAgentCardResolver.accumulate(failure, e);
            }
        }
        subscriptions.clear();
        if (failure != null) throw failure;
    }

    /** Tracks snapshots and unavailability for one RAD subscription, including conversion failures. */
    private static final class CardUpdater extends AbstractNacosAgentDiscoveryListener {
        private final AgentReference reference = new AgentReference();
        private final Object subscriptionLock = new Object();
        private AgentCard card;
        private RuntimeException failure;
        private final CountDownLatch firstEvent = new CountDownLatch(1);
        private boolean initialized;
        private boolean active = true;

        private CardUpdater(String agentName) {
            reference.setAgentName(agentName);
            reference.setLabel("latest");
        }

        /** {@inheritDoc} */
        @Override
        public synchronized void onEvent(NacosAgentDiscoveryEvent event) {
            if (!active || event == null) return;
            if (event.getType() == NacosAgentDiscoveryEventType.UNAVAILABLE) {
                card = null;
                failure = null;
                firstEvent.countDown();
                // Only a confirmed missing reference is unavailable; the SDK keeps observing.
            } else if (event.getAgentDiscoveryResult() != null
                    && reference
                            .getAgentName()
                            .equals(event.getAgentDiscoveryResult().getAgentName())) {
                card = null;
                failure = null;
                try {
                    card = RadAgentCardConverter.convert(event.getAgentDiscoveryResult());
                } catch (RuntimeException e) {
                    // Expose conversion failures to the reader, not to the SDK callback executor.
                    failure = e;
                } finally {
                    firstEvent.countDown();
                }
            }
        }
    }
}
