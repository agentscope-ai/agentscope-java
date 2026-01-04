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

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.StringUtils;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The implementation of {@link AgentRegistry} for Nacos A2A Registry.
 */
public class NacosAgentRegistry implements AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentRegistry.class);

    private static final String AGENT_INTERFACE_URL_PATTERN = "%s://%s:%s";

    private final NacosA2aRegistry nacosA2aRegistry;

    private final NacosA2aRegistryProperties nacosA2aProperties;

    private NacosAgentRegistry(
            NacosA2aRegistry nacosA2aRegistry, NacosA2aRegistryProperties nacosA2aProperties) {
        this.nacosA2aRegistry = nacosA2aRegistry;
        this.nacosA2aProperties = nacosA2aProperties;
    }

    @Override
    public String registryName() {
        return "Nacos";
    }

    @Override
    public void register(AgentCard agentCard, List<TransportProperties> transportProperties) {
        NacosA2aRegistryProperties targetProperties =
                NacosA2aRegistryProperties.builder(nacosA2aProperties).build();
        buildTransportProperties(transportProperties).forEach(targetProperties::addTransport);
        agentCard = tryOverwritePreferredTransport(agentCard, targetProperties);
        nacosA2aRegistry.registerAgent(agentCard, targetProperties);
    }

    private Collection<NacosA2aRegistryTransportProperties> buildTransportProperties(
            List<TransportProperties> transportProperties) {
        Map<String, NacosA2aRegistryTransportProperties> result =
                parseTransportsFromDeploy(transportProperties);
        getTransportPropertiesFromEnv()
                .forEach(
                        (transport, properties) ->
                                result.compute(
                                        transport,
                                        (key, oldValue) -> {
                                            String targetTransport = key.toUpperCase();
                                            if (null == oldValue) {
                                                LoggerUtil.warn(
                                                        log,
                                                        "Transport {} is not exported by"
                                                            + " agentscope, it might cause"
                                                            + " agentCard to include an unavailable"
                                                            + " endpoint.",
                                                        targetTransport);
                                                return overwriteAttributes(
                                                        null, properties, targetTransport);
                                            }
                                            NacosA2aRegistryTransportProperties newValue =
                                                    overwriteAttributes(
                                                            oldValue, properties, targetTransport);
                                            LoggerUtil.info(
                                                    log,
                                                    "Overwrite attributes for transport {} from {}"
                                                            + " to {}",
                                                    targetTransport,
                                                    oldValue,
                                                    newValue);
                                            return newValue;
                                        }));
        return result.values();
    }

    private Map<String, NacosA2aRegistryTransportProperties> parseTransportsFromDeploy(
            List<TransportProperties> transportProperties) {
        Map<String, NacosA2aRegistryTransportProperties> result = new HashMap<>();
        transportProperties.forEach(
                each -> {
                    NacosA2aRegistryTransportProperties properties =
                            parseTransportFromTransportProperties(each);
                    result.put(properties.transport(), properties);
                });
        return result;
    }

    private NacosA2aRegistryTransportProperties parseTransportFromTransportProperties(
            TransportProperties transportProperties) {
        return NacosA2aRegistryTransportProperties.builder()
                .transport(transportProperties.transportType())
                .host(transportProperties.host())
                .port(transportProperties.port())
                .path(transportProperties.path())
                .supportTls(transportProperties.supportTls())
                .build();
    }

    private Map<String, NacosA2aRegistryTransportProperties> getTransportPropertiesFromEnv() {
        return new NacosA2aTransportPropertiesEnvParser().getTransportProperties();
    }

    private NacosA2aRegistryTransportProperties overwriteAttributes(
            NacosA2aRegistryTransportProperties oldValue,
            NacosA2aRegistryTransportProperties newValue,
            String transport) {
        NacosA2aRegistryTransportProperties.Builder builder =
                NacosA2aRegistryTransportProperties.builder();
        if (null != oldValue) {
            builder.host(oldValue.host())
                    .port(oldValue.port())
                    .path(oldValue.path())
                    .supportTls(oldValue.supportTls())
                    .protocol(oldValue.protocol())
                    .query(oldValue.query());
        }
        if (StringUtils.isNotEmpty(newValue.host())) {
            builder.host(newValue.host());
        }
        if (newValue.port() > 0) {
            builder.port(newValue.port());
        }
        if (StringUtils.isNotEmpty(newValue.path())) {
            builder.path(newValue.path());
        }
        if (StringUtils.isNotEmpty(newValue.protocol())) {
            builder.protocol(newValue.protocol());
        }
        if (StringUtils.isNotEmpty(newValue.query())) {
            builder.query(newValue.query());
        }
        builder.supportTls(newValue.supportTls());
        builder.transport(transport);
        return builder.build();
    }

    private AgentCard tryOverwritePreferredTransport(
            AgentCard agentCard, NacosA2aRegistryProperties properties) {
        if (StringUtils.isEmpty(nacosA2aProperties.overwritePreferredTransport())) {
            return agentCard;
        }
        String preferredTransport = nacosA2aProperties.overwritePreferredTransport().toUpperCase();
        LoggerUtil.info(
                log,
                "Try to overwrite preferred transport from {} to {}",
                agentCard.preferredTransport(),
                preferredTransport);
        if (properties.transportProperties().containsKey(preferredTransport)) {
            return doOverwrite(agentCard, properties.transportProperties().get(preferredTransport));
        }
        LoggerUtil.warn(
                log,
                "Preferred transport {} is not found, will use original preferred transport {} with"
                        + " url {}",
                preferredTransport,
                agentCard.preferredTransport(),
                agentCard.url());
        return agentCard;
    }

    private AgentCard doOverwrite(
            AgentCard agentCard, NacosA2aRegistryTransportProperties transportProperties) {
        String newUrl = generateNewUrl(transportProperties);
        String transport = transportProperties.transport();
        AgentInterface agentInterface = new AgentInterface(transport, newUrl);
        List<AgentInterface> agentInterfaces = new LinkedList<>(agentCard.additionalInterfaces());
        agentInterfaces.add(agentInterface);
        AgentCard.Builder builder = new AgentCard.Builder(agentCard);
        builder.url(newUrl).preferredTransport(transport).additionalInterfaces(agentInterfaces);
        LoggerUtil.info(
                log,
                "Overwrite preferred transport from {} to {} with url from {} to {}",
                agentCard.preferredTransport(),
                transport,
                agentCard.url(),
                newUrl);
        return builder.build();
    }

    private String generateNewUrl(NacosA2aRegistryTransportProperties transportProperties) {
        String protocol = transportProperties.protocol();
        if (StringUtils.isEmpty(protocol)) {
            protocol = AiConstants.A2a.A2A_ENDPOINT_DEFAULT_PROTOCOL;
        }
        boolean isSupportTls = transportProperties.supportTls();
        protocol = handleTlsIfNeeded(protocol, isSupportTls);
        String url =
                String.format(
                        AGENT_INTERFACE_URL_PATTERN,
                        protocol,
                        transportProperties.host(),
                        transportProperties.port());
        String path = transportProperties.path();
        if (StringUtils.isNotBlank(path)) {
            url += path.startsWith("/") ? path : "/" + path;
        }
        String query = transportProperties.query();
        if (StringUtils.isNotBlank(query)) {
            url += "?" + query;
        }
        return url;
    }

    private String handleTlsIfNeeded(String protocol, boolean isSupportTls) {
        if (AiConstants.A2a.A2A_ENDPOINT_DEFAULT_PROTOCOL.equalsIgnoreCase(protocol)) {
            return isSupportTls ? Constants.PROTOCOL_TYPE_HTTPS : Constants.PROTOCOL_TYPE_HTTP;
        }
        return protocol;
    }

    /**
     * Creates a new Builder instance for {@link NacosAgentRegistry}.
     *
     * @param aiService nacos client for AI service
     * @return builder instance of {@link NacosAgentRegistry}
     */
    public static Builder builder(AiService aiService) {
        return new Builder(aiService);
    }

    /**
     * Creates a new Builder instance for {@link NacosAgentRegistry}.
     *
     * @param nacosServerProperties properties for nacos server
     * @return builder instance of {@link NacosAgentRegistry}
     * @throws NacosException during creating nacos client for AI service
     */
    public static Builder builder(Properties nacosServerProperties) throws NacosException {
        return new Builder(AiFactory.createAiService(nacosServerProperties));
    }

    /**
     * Builder for {@link NacosAgentRegistry}.
     */
    public static class Builder {

        private final AiService aiService;

        private NacosA2aRegistryProperties nacosA2aProperties;

        private Builder(AiService aiService) {
            this.aiService = aiService;
        }

        /**
         * Adds {@link NacosA2aRegistryProperties} instance to the builder.
         *
         * @param nacosA2aProperties properties for nacos a2a registry
         * @return this {@link Builder} instance for method chaining
         */
        public Builder nacosA2aProperties(NacosA2aRegistryProperties nacosA2aProperties) {
            this.nacosA2aProperties = nacosA2aProperties;
            return this;
        }

        /**
         * Build a new instance of {@link NacosAgentRegistry} from current nacos client and properties.
         *
         * <p>Nacos client is required to build {@link NacosAgentRegistry}. So if nacos client is null, will throw
         * {@link IllegalArgumentException}.
         * <p>{@code nacosA2aProperties} is optional. If it is null, will use default properties.
         *
         * @return new instance of {@link NacosAgentRegistry}
         */
        public NacosAgentRegistry build() {
            if (null == aiService) {
                throw new IllegalArgumentException("Nacos AI Client can not be null.");
            }
            if (null == nacosA2aProperties) {
                nacosA2aProperties = NacosA2aRegistryProperties.builder().build();
            }
            NacosA2aRegistry nacosA2aRegistry = new NacosA2aRegistry(aiService);
            return new NacosAgentRegistry(nacosA2aRegistry, nacosA2aProperties);
        }
    }
}
