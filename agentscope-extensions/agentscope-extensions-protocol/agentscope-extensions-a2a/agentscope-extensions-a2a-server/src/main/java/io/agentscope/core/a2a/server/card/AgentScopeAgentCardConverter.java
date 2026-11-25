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

import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.TransportProtocol;
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
 *     <li>capabilities.extendedAgentCard: only {@code false} for now.</li>
 *     <li>securitySchemes: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>securityRequirements: from the security of {@link ConfigurableAgentCard}, default null.</li>
 *     <li>iconUrl: only from {@link ConfigurableAgentCard}, default null.</li>
 *     <li>supportedInterfaces: the preferred interface first, then the other interfaces. The
 *     preferred interface is the url and preferredTransport of {@link ConfigurableAgentCard}, by
 *     default the `JSONRPC` interface, or any interface if there is no `JSONRPC` one. The other
 *     interfaces are the additionalInterfaces of {@link ConfigurableAgentCard}, by default one
 *     per available transport.</li>
 *     <li>protocolVersion of each interface: only from agentscope implementation, not supported
 *     to config. current version is `1.0`</li>
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
        return AgentCard.builder()
                .name(getName(agentCard, agentRunner))
                .description(getDescription(agentCard, agentRunner))
                .provider(agentCard.getProvider())
                .version(getVersion(agentCard))
                .documentationUrl(agentCard.getDocumentationUrl())
                .capabilities(capabilities)
                .defaultInputModes(getModes(agentCard.getDefaultInputModes()))
                .defaultOutputModes(getModes(agentCard.getDefaultOutputModes()))
                .skills(null != agentCard.getSkills() ? agentCard.getSkills() : List.of())
                .securitySchemes(agentCard.getSecuritySchemes())
                .securityRequirements(toSecurityRequirements(agentCard.getSecurity()))
                .iconUrl(agentCard.getIconUrl())
                .supportedInterfaces(
                        supportedInterfaces(preferredTransportInterface, additionalInterfaces))
                .build();
    }

    private AgentCapabilities createDefaultCapabilities() {
        return AgentCapabilities.builder()
                .streaming(true)
                .pushNotifications(false)
                .extendedAgentCard(false)
                .build();
    }

    private List<SecurityRequirement> toSecurityRequirements(
            List<Map<String, List<String>>> security) {
        return null == security ? null : security.stream().map(SecurityRequirement::new).toList();
    }

    /** The preferred interface comes first: A2A clients use the first one they support. */
    private List<AgentInterface> supportedInterfaces(
            AgentInterface preferred, List<AgentInterface> additionalInterfaces) {
        List<AgentInterface> result = new ArrayList<>();
        if (null != preferred) {
            result.add(preferred);
        }
        additionalInterfaces.stream().filter(each -> !each.equals(preferred)).forEach(result::add);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "No interface for the agent card: configure a url and preferred transport, or"
                            + " at least one transport");
        }
        return result;
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
                                                .equals(transport.protocolBinding()))
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
