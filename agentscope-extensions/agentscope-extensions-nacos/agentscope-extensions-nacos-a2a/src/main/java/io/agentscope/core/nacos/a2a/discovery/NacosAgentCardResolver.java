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

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

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
 * @see A2aAgent
 * @see A2aAgent.Builder
 */
public class NacosAgentCardResolver implements AgentCardResolver {

    private static final int DEFAULT_INITIAL_CAPACITY = 2;

    private final AiService aiService;

    private final Map<String, AgentCard> agentCardCaches;

    private final Map<String, AgentCardUpdater> agentCardUpdaters;

    /**
     * Creates a new instance for {@link NacosAgentCardResolver}.
     *
     * @param properties properties for nacos server to create nacos client AI service
     * @throws NacosException during building nacos client
     */
    public NacosAgentCardResolver(Properties properties) throws NacosException {
        this(AiFactory.createAiService(properties));
    }

    /**
     * Creates a new instance for {@link NacosAgentCardResolver}.
     *
     * @param aiService nacos client AI service
     */
    public NacosAgentCardResolver(AiService aiService) {
        this.aiService = aiService;
        this.agentCardCaches = new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY);
        this.agentCardUpdaters = new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY);
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        AgentCard cachedAgentCard = agentCardCaches.get(agentName);
        if (cachedAgentCard != null) {
            return cachedAgentCard;
        }
        AgentCard result = getAndSubscribe(agentName);
        // If already put by listener, use listener put value
        return agentCardCaches.computeIfAbsent(agentName, name -> result);
    }

    private AgentCard getAndSubscribe(String agentName) {
        try {
            AgentCardUpdater updater =
                    agentCardUpdaters.computeIfAbsent(agentName, name -> new AgentCardUpdater());
            AgentCardDetailInfo agentCardDetailInfo =
                    aiService.subscribeAgentCard(agentName, updater);
            return AgentCardConverterUtil.convertToA2aAgentCard(agentCardDetailInfo);
        } catch (NacosException e) {
            throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
        }
    }

    private class AgentCardUpdater extends AbstractNacosAgentCardListener {

        @Override
        public void onEvent(NacosAgentCardEvent event) {
            agentCardCaches.put(
                    event.getAgentName(),
                    AgentCardConverterUtil.convertToA2aAgentCard(event.getAgentCard()));
        }
    }
}
