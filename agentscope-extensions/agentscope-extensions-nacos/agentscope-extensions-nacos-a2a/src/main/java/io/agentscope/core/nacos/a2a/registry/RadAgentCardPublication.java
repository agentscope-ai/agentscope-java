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

import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.agent.AgentCallInterface;
import com.alibaba.nacos.api.ai.model.agent.AgentProvider;
import com.alibaba.nacos.api.ai.model.agent.Endpoint;
import com.alibaba.nacos.api.ai.model.agent.EndpointSet;
import com.alibaba.nacos.api.ai.model.agent.EndpointSource;
import com.alibaba.nacos.api.ai.model.agent.client.AgentPublishRequest;
import com.alibaba.nacos.api.ai.utils.EndpointCanonicalizer;
import com.alibaba.nacos.api.utils.json.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an A2A Card into a RAD publication with explicit protocol-specific endpoint metadata.
 *
 * <p>RAD does not infer tenant or protocol version from the native descriptor. Each declared
 * endpoint therefore carries those values under the Nacos public A2A metadata keys. The original
 * Card remains in the descriptor, and runtime addresses retain precedence over declared addresses.
 * Publication still follows the ordinary Nacos submission and review workflow.
 */
final class RadAgentCardPublication {
    private RadAgentCardPublication() {}

    /**
     * Builds a publication without modifying the supplied Card.
     *
     * @param card converted Nacos Card with its supported interfaces
     * @param autoSubmit whether to submit the version through the ordinary publication pipeline
     * @return a publication containing the native Card and its declared endpoints
     */
    static AgentPublishRequest from(AgentCard card, boolean autoSubmit) {
        // TODO: Replace this mapping when Nacos exposes a public A2A publication builder.
        // Track the convenience API separately from model bugs: alibaba/nacos#15975.
        AgentCallInterface call = new AgentCallInterface();
        call.setProtocol("a2a");
        call.setProtocolVersion(card.getProtocolVersion());
        call.setDescriptorMediaType("application/json");
        call.setNativeDescriptor(JsonUtils.toObj(JsonUtils.toJson(card), Map.class));
        call.setEndpointSourceOrder(List.of(EndpointSource.RUNTIME, EndpointSource.DECLARED));
        EndpointSet declared = new EndpointSet();
        declared.setSource(EndpointSource.DECLARED);
        declared.setEndpoints(
                card.getSupportedInterfaces().stream()
                        .map(RadAgentCardPublication::endpoint)
                        .toList());
        call.setEndpointSets(List.of(declared));
        AgentPublishRequest request = new AgentPublishRequest();
        request.setAgentName(card.getName());
        request.setVersion(card.getVersion());
        request.setDescription(card.getDescription());
        request.setIconUrl(card.getIconUrl());
        if (card.getProvider() != null) {
            AgentProvider provider = new AgentProvider();
            provider.setName(card.getProvider().getOrganization());
            provider.setUrl(card.getProvider().getUrl());
            request.setProvider(provider);
        }
        request.setCallInterfaces(List.of(call));
        request.setAutoSubmit(autoSubmit);
        return request;
    }

    /**
     * Converts one interface to an independent endpoint using the SDK's URI normalization.
     *
     * @param address A2A interface to publish
     * @return endpoint retaining the interface's tenant, including an explicit empty tenant
     */
    static Endpoint endpoint(AgentInterface address) {
        Endpoint endpoint = new Endpoint();
        endpoint.setUri(address.getUrl());
        endpoint.setTransport(address.getProtocolBinding());
        Map<String, String> metadata = new HashMap<>();
        if (address.getProtocolVersion() != null) {
            metadata.put(AiConstants.A2a.ENDPOINT_PROTOCOL_VERSION, address.getProtocolVersion());
        }
        if (address.getTenant() != null) {
            metadata.put(AiConstants.A2a.ENDPOINT_TENANT, address.getTenant());
        }
        endpoint.setMetadata(metadata);
        return EndpointCanonicalizer.canonicalize(endpoint);
    }
}
