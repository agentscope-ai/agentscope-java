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

import com.alibaba.nacos.api.ability.constant.AbilityStatus;
import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.AgentService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.utils.A2aEndpointUtils;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope Extensions for Registry A2A AgentCard and A2A instance endpoint to Nacos.
 *
 * <p>When an {@link AiService} reports RAD support, publication explicitly maps interface tenant
 * and protocol version into declared endpoint metadata. Older servers and an A2A-only service
 * retain the legacy publication API. Passing an {@link AgentService} selects native publication.
 * Runtime endpoint configuration inherits metadata from the matching Card interface.
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
     * @throws NacosRuntimeException if querying, publishing, or registering endpoints fails,
     *     preserving the Nacos error code and cause
     * @throws IllegalStateException if the SDK cannot yet determine server capability
     * @throws IllegalArgumentException if a configured runtime endpoint cannot be matched to
     *     interfaces with unambiguous tenant and protocol version metadata
     */
    public void registerAgent(AgentCard agentCard, NacosA2aRegistryProperties a2aProperties) {
        var nacosAgentCard = AgentCardConverterUtil.convertToNacosAgentCard(agentCard);
        Set<AgentEndpoint> endpoints = buildEndpoints(nacosAgentCard, a2aProperties);
        try {
            tryReleaseAgentCard(nacosAgentCard, a2aProperties);
            registerEndpoint(nacosAgentCard.getName(), endpoints);
        } catch (NacosException e) {
            log.error("Register agent card {} to Nacos failed.", agentCard.name(), e);
            throw new NacosRuntimeException(e.getErrCode(), e.getErrMsg(), e);
        }
    }

    private void tryReleaseAgentCard(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard,
            NacosA2aRegistryProperties a2aProperties)
            throws NacosException {
        AgentService nativeService = nativePublicationService();
        if (null != tryGetAgentCardFromNacos(agentCard)) {
            log.warn(
                    "Agent card {} already exists, agentCard release might be ignored.",
                    agentCard.getName());
        }
        log.info("Register agent card {} to Nacos.", agentCard.getName());
        if (nativeService != null) {
            nativeService.publishAgent(
                    RadAgentCardPublication.from(agentCard, a2aProperties.isSetAsLatest()));
        } else {
            a2aService.releaseAgentCard(
                    agentCard,
                    AiConstants.A2a.A2A_ENDPOINT_TYPE_SERVICE,
                    a2aProperties.isSetAsLatest());
        }
        log.info(
                "Agent card {} submitted to Nacos; serving availability depends on publication"
                        + " status.",
                agentCard.getName());
    }

    private AgentCardDetailInfo tryGetAgentCardFromNacos(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard) throws NacosException {
        try {
            return a2aService.getAgentCard(agentCard.getName(), agentCard.getVersion());
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) return null;
            throw e;
        }
    }

    private AgentService nativePublicationService() throws NacosException {
        if (a2aService instanceof AiService client) {
            AbilityStatus capability = client.getAgentDiscoveryCapability();
            if (capability == AbilityStatus.SUPPORTED) return client.agent();
            if (capability == AbilityStatus.NOT_SUPPORTED) return null;
            throw new IllegalStateException(
                    "Nacos Agent publication capability is unknown; retry after connection");
        }
        return a2aService instanceof AgentService service ? service : null;
    }

    private Set<AgentEndpoint> buildEndpoints(
            com.alibaba.nacos.api.ai.model.a2a.AgentCard agentCard,
            NacosA2aRegistryProperties properties) {
        if (!properties.enabledRegisterEndpoint() || properties.transportProperties().isEmpty()) {
            return Set.of();
        }
        return properties.transportProperties().values().stream()
                .map(transport -> buildAgentEndpoint(transport, agentCard))
                .collect(Collectors.toSet());
    }

    private void registerEndpoint(String agentName, Set<AgentEndpoint> endpoints)
            throws NacosException {
        if (endpoints.isEmpty()) return;
        log.info("Register {} endpoint(s) to Nacos", endpoints.size());
        if (endpoints.size() == 1) {
            a2aService.registerAgentEndpoint(agentName, endpoints.iterator().next());
        } else {
            a2aService.registerAgentEndpoint(agentName, endpoints);
        }
    }

    private AgentEndpoint buildAgentEndpoint(
            NacosA2aRegistryTransportProperties transportProperties,
            com.alibaba.nacos.api.ai.model.a2a.AgentCard card) {
        AgentEndpoint result = new AgentEndpoint();
        result.setTransport(transportProperties.transport());
        result.setAddress(transportProperties.host());
        result.setPort(transportProperties.port());
        result.setPath(transportProperties.path());
        result.setSupportTls(transportProperties.supportTls());
        result.setVersion(card.getVersion());
        result.setProtocol(transportProperties.protocol());
        result.setQuery(transportProperties.query());
        List<AgentInterface> matches =
                card.getSupportedInterfaces().stream()
                        .filter(
                                address ->
                                        Objects.equals(
                                                address.getProtocolBinding(),
                                                result.getTransport()))
                        .toList();
        if (matches.size() > 1) {
            String uri = A2aEndpointUtils.toEndpoint(result).getUri();
            List<AgentInterface> exact =
                    matches.stream()
                            .filter(
                                    address ->
                                            RadAgentCardPublication.endpoint(address)
                                                    .getUri()
                                                    .equals(uri))
                            .toList();
            if (!exact.isEmpty()) matches = exact;
        }
        if (!matches.isEmpty()) {
            AgentInterface selected = matches.get(0);
            if (matches.stream()
                    .anyMatch(
                            address ->
                                    !Objects.equals(address.getTenant(), selected.getTenant())
                                            || !Objects.equals(
                                                    address.getProtocolVersion(),
                                                    selected.getProtocolVersion()))) {
                throw new IllegalArgumentException(
                        "Runtime endpoint for transport "
                                + result.getTransport()
                                + " matches multiple interfaces with different tenant or protocol"
                                + " version");
            }
            result.setTenant(selected.getTenant());
            result.setProtocolVersion(selected.getProtocolVersion());
        } else {
            result.setProtocolVersion(card.getProtocolVersion());
        }
        return result;
    }
}
