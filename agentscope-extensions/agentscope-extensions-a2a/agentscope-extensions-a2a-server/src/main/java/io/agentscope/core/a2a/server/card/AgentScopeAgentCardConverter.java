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

package io.agentscope.core.a2a.server.card;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent Card Builder For Agent Scope A2A Server.
 *
 * <p> The Converter will convert input {@link ConfigurableAgentCard} to an {@link AgentCard} instance, available
 * {@link TransportProperties} and {@link AgentRunner}.
 *
 * <p>Attributes of {@link AgentCard} build rules:
 * <ul>
 *     <li>name: first from {@link ConfigurableAgentCard}, default from {@link AgentRunner#getAgentName()}.</li>
 *     <li>description: first from {@link ConfigurableAgentCard}, default from {@link AgentRunner#getAgentDescription()} ()}.</li>
 *     <li>provider: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>version: only from {@link ConfigurableAgentCard}, default 1.0.0</li>
 *     <li>documentationUrl: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>capabilities: only from agentscope implementation, not supported to config.</li>
 *     <li>defaultInputModes/defaultOutputModes: first from {@link ConfigurableAgentCard}, default `["text"]`.</li>
 *     <li>skills: first from {@link ConfigurableAgentCard}, default empty list.</li>
 *     <li>supportsAuthenticatedExtendedCard: only {@code false} for now.</li>
 *     <li>securitySchemes: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>security: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>iconUrl: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>additionalInterfaces: first from {@link ConfigurableAgentCard}, default from availableTransports.</li>
 *     <li>preferredTransport: first from {@link ConfigurableAgentCard}, default is `JSONRPC`, if not found in availableTransports, will random one from availableTransports. </li>
 *     <li>url: first from {@link ConfigurableAgentCard}, default from `JSONRPC` transportProperties, if not found in availableTransports, will random one from availableTransports. </li>
 *     <li>protocolVersion: only from agentscope implementation, not supported to config. current version is `0.3.0`</li>
 * </ul>
 */
public class AgentScopeAgentCardConverter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentCardConverter.class);

    private static final String HTTPS = "https";

    private static final String HTTP = "http";

    private static final String PATH_SPLITTER = "/";

    private static final String URL_PATTERN = "%s://%s:%d%s";

    public AgentCard createAgentCard(
            ConfigurableAgentCard agentCard,
            AgentRunner agentRunner,
            Set<TransportProperties> availableTransports) {
        AgentCapabilities capabilities = createDefaultCapabilities();
        List<AgentInterface> additionalInterfaces =
                createAdditionalInterfaces(agentCard, availableTransports);
        AgentInterface preferredTransportInterface =
                getPreferredTransport(agentCard, additionalInterfaces);
        AgentCard.Builder agentCardBuilder = new AgentCard.Builder();
        if (null != preferredTransportInterface) {
            agentCardBuilder.preferredTransport(preferredTransportInterface.transport());
            agentCardBuilder.url(preferredTransportInterface.url());
        }
        return agentCardBuilder
                .name(getName(agentCard, agentRunner))
                .description(getDescription(agentCard, agentRunner))
                .provider(agentCard.getProvider())
                .version(getVersion(agentCard))
                .documentationUrl(agentCard.getDocumentationUrl())
                .capabilities(capabilities)
                .defaultInputModes(getModes(agentCard.getDefaultInputModes()))
                .defaultOutputModes(getModes(agentCard.getDefaultOutputModes()))
                .skills(null != agentCard.getSkills() ? agentCard.getSkills() : List.of())
                .supportsAuthenticatedExtendedCard(false)
                .securitySchemes(agentCard.getSecuritySchemes())
                .security(agentCard.getSecurity())
                .iconUrl(agentCard.getIconUrl())
                .additionalInterfaces(additionalInterfaces)
                .protocolVersion("0.3.0")
                .build();
    }

    private AgentCapabilities createDefaultCapabilities() {
        return new AgentCapabilities.Builder()
                .streaming(true)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build();
    }

    private List<AgentInterface> createAdditionalInterfaces(
            ConfigurableAgentCard agentCard, Set<TransportProperties> availableTransports) {
        if (null != agentCard.getAdditionalInterfaces()) {
            return agentCard.getAdditionalInterfaces();
        }
        return availableTransports.stream().map(this::createAdditionalInterface).toList();
    }

    private AgentInterface getPreferredTransport(
            ConfigurableAgentCard agentCard, List<AgentInterface> availableTransports) {
        String preferredTransport = agentCard.getPreferredTransport();
        String url = agentCard.getUrl();
        if (null != preferredTransport && url != null) {
            return new AgentInterface(preferredTransport, url);
        }
        // Use default transport
        log.info("No preferred transport specified, using default transport `JSONRPC`.");
        Optional<AgentInterface> result =
                availableTransports.stream()
                        .filter(
                                transport ->
                                        TransportProtocol.JSONRPC
                                                .asString()
                                                .equals(transport.transport()))
                        .findFirst();
        return result.orElseGet(
                () -> {
                    log.warn(
                            "No found default transport `JSONRPC` in available transports, try to"
                                    + " random one from available transports");
                    return availableTransports.stream().findAny().orElse(null);
                });
    }

    private AgentInterface createAdditionalInterface(TransportProperties transport) {
        String schema = transport.supportTls() ? HTTPS : HTTP;
        String path = getPath(transport);
        int port = getPort(transport);
        String url = String.format(URL_PATTERN, schema, transport.host(), port, path);
        return new AgentInterface(transport.transportType(), url);
    }

    private String getPath(TransportProperties transport) {
        String result = null == transport.path() ? "" : transport.path();
        if (result.isEmpty()) {
            return result;
        }
        return result.startsWith(PATH_SPLITTER) ? result : PATH_SPLITTER + result;
    }

    private int getPort(TransportProperties transportProperties) {
        if (null == transportProperties.port()) {
            return transportProperties.supportTls() ? 443 : 80;
        }
        return transportProperties.port();
    }

    private String getName(ConfigurableAgentCard agentCard, AgentRunner runner) {
        return null == agentCard.getName() ? runner.getAgentName() : agentCard.getName();
    }

    private String getDescription(ConfigurableAgentCard agentCard, AgentRunner runner) {
        return null == agentCard.getDescription()
                ? runner.getAgentDescription()
                : agentCard.getDescription();
    }

    private String getVersion(ConfigurableAgentCard agentCard) {
        return null == agentCard.getVersion() ? "1.0.0" : agentCard.getVersion();
    }

    private List<String> getModes(List<String> modes) {
        return null == modes ? List.of("text") : modes;
    }
}
