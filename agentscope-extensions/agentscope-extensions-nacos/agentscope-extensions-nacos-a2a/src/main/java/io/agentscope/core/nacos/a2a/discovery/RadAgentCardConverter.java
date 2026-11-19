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

import com.alibaba.nacos.api.ai.converter.AgentConverters;
import com.alibaba.nacos.api.ai.model.agent.AgentDiscoveryResult;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Maps Nacos RAD discovery results into the external A2A model used by AgentScope.
 *
 * <p>The Nacos public converter owns descriptor validation and endpoint projection. This adapter
 * only connects its Nacos AgentCard output to AgentScope's external A2A model conversion. It does
 * not filter or reorder endpoints, perform network requests, or modify the input snapshot.
 *
 * @see AgentConverters#a2a()
 * @see AgentCardConverterUtil
 */
final class RadAgentCardConverter {
    private RadAgentCardConverter() {}

    /**
     * Converts a RAD snapshot through the Nacos public A2A converter.
     *
     * @param result complete discovery snapshot containing an A2A interface and retained endpoints
     * @return the equivalent external A2A AgentCard
     * @throws IllegalArgumentException if the snapshot cannot be represented as an A2A Card
     */
    static AgentCard convert(AgentDiscoveryResult result) {
        return AgentCardConverterUtil.convertToA2aAgentCard(AgentConverters.a2a().convert(result));
    }
}
