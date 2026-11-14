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

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Resolves AgentCards through the Nacos A2A API for {@link NacosAgentCardResolver}.
 *
 * <p>The first lookup subscribes to AgentCard updates for the requested agent. Subsequent lookups
 * return the cached Card, which is replaced when the SDK delivers another non-null Card. An empty
 * event does not invalidate the cache, preserving the legacy A2A discovery behavior. Protocol
 * adaptation and server capability negotiation remain the responsibility of the Nacos SDK.
 *
 * <p>Lookups initialize subscriptions independently for each agent. The initialization lock is
 * separate from callback synchronization so callbacks can run while the SDK subscribes. The owning
 * resolver excludes lookups during closure. Closing this delegate cancels its subscriptions without
 * shutting down the supplied {@link AiService}.
 *
 * @see NacosAgentCardResolver
 * @see AiService#subscribeAgentCard(String, AbstractNacosAgentCardListener)
 */
final class A2aAgentCardResolver implements NacosAgentCardResolver.Delegate {
    private final AiService service;
    private final Map<String, CardUpdater> subscriptions = new ConcurrentHashMap<>();

    /**
     * Creates an A2A discovery delegate using an existing Nacos AI service.
     *
     * @param service Nacos AI service whose lifecycle is managed by the owning resolver or caller
     */
    A2aAgentCardResolver(AiService service) {
        this.service = service;
    }

    /**
     * Gets the cached AgentCard, subscribing to updates if no subscription has completed yet.
     *
     * <p>A Card delivered by a callback takes precedence over the initial subscription result.
     * Once established, the subscription is retained even when its initial result is absent.
     *
     * @param agentName name of the remote agent
     * @return the last non-null AgentCard received, or {@code null} if none has been received
     * @throws NacosRuntimeException if subscribing fails, preserving the Nacos error code and cause
     */
    @Override
    public AgentCard getAgentCard(String agentName) {
        CardUpdater updater = subscriptions.computeIfAbsent(agentName, CardUpdater::new);
        synchronized (updater.subscriptionLock) {
            if (!updater.initialized) {
                try {
                    AgentCardDetailInfo initial = service.subscribeAgentCard(agentName, updater);
                    synchronized (updater) {
                        // Preserve a Card already supplied by a callback, as in the legacy cache.
                        if (updater.card == null && initial != null) {
                            updater.card = AgentCardConverterUtil.convertToA2aAgentCard(initial);
                        }
                    }
                    updater.initialized = true;
                } catch (NacosException e) {
                    throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
                }
            }
        }
        synchronized (updater) {
            return updater.card;
        }
    }

    /**
     * Cancels all A2A subscriptions created by this delegate and clears its cached Cards.
     *
     * <p>Late callbacks are ignored. Every subscription is processed even if an unsubscribe
     * fails; the supplied Nacos AI service remains open.
     *
     * @throws NacosException if unsubscribing fails; additional failures are attached as
     *     suppressed exceptions
     */
    @Override
    public void close() throws NacosException {
        NacosException failure = null;
        for (var entry : subscriptions.entrySet()) {
            CardUpdater updater = entry.getValue();
            synchronized (updater) {
                updater.active = false;
                updater.card = null;
            }
            try {
                service.unsubscribeAgentCard(entry.getKey(), updater);
            } catch (NacosException e) {
                failure = NacosAgentCardResolver.accumulate(failure, e);
            }
        }
        subscriptions.clear();
        if (failure != null) throw failure;
    }

    /** Keeps the last non-null Card for one legacy subscription and rejects callbacks after close. */
    private static final class CardUpdater extends AbstractNacosAgentCardListener {
        private final String agentName;
        private final Object subscriptionLock = new Object();
        private AgentCard card;
        private boolean initialized;
        private boolean active = true;

        private CardUpdater(String agentName) {
            this.agentName = agentName;
        }

        /** {@inheritDoc} */
        @Override
        public synchronized void onEvent(NacosAgentCardEvent event) {
            if (!active
                    || event == null
                    || !agentName.equals(event.getAgentName())
                    || event.getAgentCard() == null) return;
            card = AgentCardConverterUtil.convertToA2aAgentCard(event.getAgentCard());
        }
    }
}
