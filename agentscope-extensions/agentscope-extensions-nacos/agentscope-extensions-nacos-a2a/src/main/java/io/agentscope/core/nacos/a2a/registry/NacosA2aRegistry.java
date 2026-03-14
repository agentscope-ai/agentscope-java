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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.a2a.spec.AgentCard;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope Extensions for Registry A2A AgentCard and A2A instance endpoint to Nacos.
 */
public class NacosA2aRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosA2aRegistry.class);

    private final A2aService a2aService;

    /**
     * New instance of {@link NacosA2aRegistry} by Nacos server properties.
     *
     * @param nacosProperties the properties for Nacos server
     * @throws NacosException during building Nacos client failed
     */
    public NacosA2aRegistry(Properties nacosProperties) throws NacosException {
        this(AiFactory.createAiService(nacosProperties));
    }

    /**
     * New instance of {@link NacosA2aRegistry} by Nacos Client instance.
     *
     * @param a2aService the Nacos A2aService instance, which also can use {@link com.alibaba.nacos.api.ai.AiService}.
     */
    public NacosA2aRegistry(A2aService a2aService) {
        this.a2aService = a2aService;
    }

    /**
     * Registers an A2A agent card and endpoint to Nacos.
     *
     * @param agentCard     the agent card to register
     * @param a2aProperties the properties for A2A registry
     */
    public void registerAgent(AgentCard agentCard, NacosA2aRegistryProperties a2aProperties) {
        var nacosAgentCard = AgentCardConverterUtil.convertToNacosAgentCard(agentCard);
        try {
            NacosAgentRegistrationHelper.tryReleaseAgentCard(
                    a2aService, nacosAgentCard, a2aProperties);
            NacosAgentRegistrationHelper.registerEndpoint(
                    a2aService, nacosAgentCard, a2aProperties);
        } catch (NacosException e) {
            log.error("Register agent card {} to Nacos failed.", agentCard.name(), e);
            throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
        }
    }
}
