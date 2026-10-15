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

package io.agentscope.core.nacos.a2a.utils;

import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.ai.model.a2a.SecurityScheme;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow;
import org.a2aproject.sdk.spec.ClientCredentialsOAuthFlow;
import org.a2aproject.sdk.spec.DeviceCodeOAuthFlow;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;

/**
 * AgentCard Converter between Nacos {@link AgentCard} and A2A specification {@link org.a2aproject.sdk.spec.AgentCard}.
 *
 * <p>The Nacos model follows the A2A 0.3 agent card, the A2A SDK model the A2A 1.0 one:
 * <ul>
 *     <li>The A2A supported interfaces are the Nacos url and preferred transport, which come
 *     first, and the Nacos additional interfaces. The Nacos protocol version is the version of
 *     these interfaces.</li>
 *     <li>The A2A security requirements are the Nacos security.</li>
 *     <li>The A2A {@code capabilities.extendedAgentCard} is the Nacos
 *     {@code supportsAuthenticatedExtendedCard}.</li>
 *     <li>A2A security schemes are stored in Nacos as maps in the A2A 0.3 format, with the scheme
 *     kind in {@code type}. A scheme Nacos does not describe this way is left out.</li>
 * </ul>
 */
public class AgentCardConverterUtil {

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    /**
     * Converts Nacos AgentCard object to A2A specification AgentCard object.
     *
     * @param agentCard the Nacos AgentCard object
     * @return the converted A2A specification AgentCard object, or null if input is null
     */
    public static org.a2aproject.sdk.spec.AgentCard convertToA2aAgentCard(AgentCard agentCard) {
        if (agentCard == null) {
            return null;
        }
        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name(agentCard.getName())
                .description(agentCard.getDescription())
                .version(agentCard.getVersion())
                .iconUrl(agentCard.getIconUrl())
                .capabilities(
                        convertToA2aAgentCapabilities(
                                agentCard.getCapabilities(),
                                agentCard.getSupportsAuthenticatedExtendedCard()))
                .skills(
                        mapList(
                                agentCard.getSkills(),
                                AgentCardConverterUtil::convertToA2aAgentSkill))
                .supportedInterfaces(convertToA2aAgentInterfaces(agentCard))
                .provider(convertToA2aAgentProvider(agentCard.getProvider()))
                .documentationUrl(agentCard.getDocumentationUrl())
                .securitySchemes(convertToA2aSecuritySchemes(agentCard.getSecuritySchemes()))
                .securityRequirements(convertToA2aSecurityRequirements(agentCard.getSecurity()))
                .defaultInputModes(agentCard.getDefaultInputModes())
                .defaultOutputModes(agentCard.getDefaultOutputModes())
                .build();
    }

    private static org.a2aproject.sdk.spec.AgentProvider convertToA2aAgentProvider(
            AgentProvider provider) {
        if (null == provider) {
            return null;
        }
        return new org.a2aproject.sdk.spec.AgentProvider(
                provider.getOrganization(), provider.getUrl());
    }

    private static List<org.a2aproject.sdk.spec.AgentInterface> convertToA2aAgentInterfaces(
            AgentCard agentCard) {
        String protocolVersion =
                null != agentCard.getProtocolVersion()
                        ? agentCard.getProtocolVersion()
                        : org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION;
        List<org.a2aproject.sdk.spec.AgentInterface> result = new ArrayList<>();
        if (null != agentCard.getUrl()) {
            String transport =
                    null != agentCard.getPreferredTransport()
                            ? agentCard.getPreferredTransport()
                            : "JSONRPC";
            result.add(
                    new org.a2aproject.sdk.spec.AgentInterface(
                            transport, agentCard.getUrl(), null, protocolVersion));
        }
        if (null != agentCard.getAdditionalInterfaces()) {
            agentCard.getAdditionalInterfaces().stream()
                    .filter(Objects::nonNull)
                    .map(
                            each ->
                                    new org.a2aproject.sdk.spec.AgentInterface(
                                            each.getTransport(),
                                            each.getUrl(),
                                            null,
                                            protocolVersion))
                    .filter(each -> !result.contains(each))
                    .forEach(result::add);
        }
        return result;
    }

    private static org.a2aproject.sdk.spec.AgentCapabilities convertToA2aAgentCapabilities(
            AgentCapabilities nacosCapabilities, Boolean extendedAgentCard) {
        org.a2aproject.sdk.spec.AgentCapabilities.Builder builder =
                org.a2aproject.sdk.spec.AgentCapabilities.builder()
                        .extendedAgentCard(Boolean.TRUE.equals(extendedAgentCard));
        if (nacosCapabilities != null) {
            builder.streaming(Boolean.TRUE.equals(nacosCapabilities.getStreaming()))
                    .pushNotifications(
                            Boolean.TRUE.equals(nacosCapabilities.getPushNotifications()));
        }
        return builder.build();
    }

    private static org.a2aproject.sdk.spec.AgentSkill convertToA2aAgentSkill(
            AgentSkill nacosSkill) {
        return org.a2aproject.sdk.spec.AgentSkill.builder()
                .id(nacosSkill.getId())
                .tags(nacosSkill.getTags())
                .examples(nacosSkill.getExamples())
                .name(nacosSkill.getName())
                .description(nacosSkill.getDescription())
                .inputModes(nacosSkill.getInputModes())
                .outputModes(nacosSkill.getOutputModes())
                .build();
    }

    private static List<SecurityRequirement> convertToA2aSecurityRequirements(
            List<Map<String, List<String>>> security) {
        return null == security ? null : mapList(security, SecurityRequirement::new);
    }

    private static Map<String, org.a2aproject.sdk.spec.SecurityScheme> convertToA2aSecuritySchemes(
            Map<String, SecurityScheme> securitySchemes) {
        if (null == securitySchemes) {
            return null;
        }
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> result = new LinkedHashMap<>();
        securitySchemes.forEach(
                (name, scheme) -> {
                    org.a2aproject.sdk.spec.SecurityScheme converted =
                            convertToA2aSecurityScheme(scheme);
                    if (null != converted) {
                        result.put(name, converted);
                    }
                });
        return result;
    }

    private static org.a2aproject.sdk.spec.SecurityScheme convertToA2aSecurityScheme(
            Map<String, Object> scheme) {
        if (null == scheme) {
            return null;
        }
        String description = string(scheme, DESCRIPTION);
        return switch (String.valueOf(scheme.get(TYPE))) {
            case "apiKey" -> {
                APIKeySecurityScheme.Location location = apiKeyLocation(string(scheme, "in"));
                yield null == location
                        ? null
                        : new APIKeySecurityScheme(location, string(scheme, "name"), description);
            }
            case "http" ->
                    new HTTPAuthSecurityScheme(
                            string(scheme, "bearerFormat"), string(scheme, "scheme"), description);
            case "oauth2" ->
                    new OAuth2SecurityScheme(
                            convertToA2aOAuthFlows(map(scheme, "flows")),
                            description,
                            string(scheme, "oauth2MetadataUrl"));
            case "openIdConnect" ->
                    new OpenIdConnectSecurityScheme(
                            string(scheme, "openIdConnectUrl"), description);
            case "mutualTLS" -> new MutualTLSSecurityScheme(description);
            default -> null;
        };
    }

    private static APIKeySecurityScheme.Location apiKeyLocation(String in) {
        for (APIKeySecurityScheme.Location location : APIKeySecurityScheme.Location.values()) {
            if (location.asString().equals(in)) {
                return location;
            }
        }
        return null;
    }

    private static OAuthFlows convertToA2aOAuthFlows(Map<String, Object> flows) {
        Map<String, Object> code = map(flows, "authorizationCode");
        Map<String, Object> client = map(flows, "clientCredentials");
        Map<String, Object> device = map(flows, "deviceCode");
        return new OAuthFlows(
                null == code
                        ? null
                        : new AuthorizationCodeOAuthFlow(
                                string(code, "authorizationUrl"),
                                string(code, "refreshUrl"),
                                scopes(code),
                                string(code, "tokenUrl"),
                                Boolean.TRUE.equals(code.get("pkceRequired"))),
                null == client
                        ? null
                        : new ClientCredentialsOAuthFlow(
                                string(client, "refreshUrl"),
                                scopes(client),
                                string(client, "tokenUrl")),
                null == device
                        ? null
                        : new DeviceCodeOAuthFlow(
                                string(device, "deviceAuthorizationUrl"),
                                string(device, "tokenUrl"),
                                string(device, "refreshUrl"),
                                scopes(device)));
    }

    /**
     * Converts A2A specification AgentCard object to Nacos AgentCard object.
     *
     * @param agentCard the A2A specification AgentCard object
     * @return the converted Nacos AgentCard object
     */
    public static AgentCard convertToNacosAgentCard(org.a2aproject.sdk.spec.AgentCard agentCard) {
        AgentCard card = new AgentCard();
        List<org.a2aproject.sdk.spec.AgentInterface> interfaces = agentCard.supportedInterfaces();
        if (!interfaces.isEmpty()) {
            org.a2aproject.sdk.spec.AgentInterface preferred = interfaces.get(0);
            card.setProtocolVersion(preferred.protocolVersion());
            card.setUrl(preferred.url());
            card.setPreferredTransport(preferred.protocolBinding());
        }
        card.setAdditionalInterfaces(
                mapList(interfaces, AgentCardConverterUtil::convertToNacosAgentInterface));
        card.setName(agentCard.name());
        card.setDescription(agentCard.description());
        card.setVersion(agentCard.version());
        card.setIconUrl(agentCard.iconUrl());
        card.setCapabilities(convertToNacosAgentCapabilities(agentCard.capabilities()));
        card.setSkills(
                mapList(agentCard.skills(), AgentCardConverterUtil::convertToNacosAgentSkill));
        card.setProvider(convertToNacosAgentProvider(agentCard.provider()));
        card.setDocumentationUrl(agentCard.documentationUrl());
        card.setSecuritySchemes(convertToNacosSecuritySchemes(agentCard.securitySchemes()));
        card.setSecurity(
                null == agentCard.securityRequirements()
                        ? null
                        : mapList(agentCard.securityRequirements(), SecurityRequirement::schemes));
        card.setDefaultInputModes(agentCard.defaultInputModes());
        card.setDefaultOutputModes(agentCard.defaultOutputModes());
        card.setSupportsAuthenticatedExtendedCard(agentCard.capabilities().extendedAgentCard());
        return card;
    }

    private static AgentCapabilities convertToNacosAgentCapabilities(
            org.a2aproject.sdk.spec.AgentCapabilities capabilities) {
        AgentCapabilities nacosCapabilities = new AgentCapabilities();
        nacosCapabilities.setStreaming(capabilities.streaming());
        nacosCapabilities.setPushNotifications(capabilities.pushNotifications());
        // Not part of A2A 1.0.
        nacosCapabilities.setStateTransitionHistory(false);
        return nacosCapabilities;
    }

    private static AgentSkill convertToNacosAgentSkill(
            org.a2aproject.sdk.spec.AgentSkill agentSkill) {
        AgentSkill skill = new AgentSkill();
        skill.setId(agentSkill.id());
        skill.setName(agentSkill.name());
        skill.setDescription(agentSkill.description());
        skill.setTags(agentSkill.tags());
        skill.setExamples(agentSkill.examples());
        skill.setInputModes(agentSkill.inputModes());
        skill.setOutputModes(agentSkill.outputModes());
        return skill;
    }

    private static AgentInterface convertToNacosAgentInterface(
            org.a2aproject.sdk.spec.AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url());
        nacosAgentInterface.setTransport(agentInterface.protocolBinding());
        return nacosAgentInterface;
    }

    private static AgentProvider convertToNacosAgentProvider(
            org.a2aproject.sdk.spec.AgentProvider agentProvider) {
        if (null == agentProvider) {
            return null;
        }
        AgentProvider nacosAgentProvider = new AgentProvider();
        nacosAgentProvider.setOrganization(agentProvider.organization());
        nacosAgentProvider.setUrl(agentProvider.url());
        return nacosAgentProvider;
    }

    private static Map<String, SecurityScheme> convertToNacosSecuritySchemes(
            Map<String, org.a2aproject.sdk.spec.SecurityScheme> securitySchemes) {
        if (securitySchemes == null) {
            return null;
        }
        Map<String, SecurityScheme> result = new LinkedHashMap<>();
        securitySchemes.forEach(
                (name, scheme) -> result.put(name, convertToNacosSecurityScheme(scheme)));
        return result;
    }

    private static SecurityScheme convertToNacosSecurityScheme(
            org.a2aproject.sdk.spec.SecurityScheme scheme) {
        SecurityScheme result = new SecurityScheme();
        if (scheme instanceof APIKeySecurityScheme apiKey) {
            result.put(TYPE, "apiKey");
            putIfPresent(result, "name", apiKey.name());
            putIfPresent(result, "in", apiKey.location().asString());
            putIfPresent(result, DESCRIPTION, apiKey.description());
        } else if (scheme instanceof HTTPAuthSecurityScheme http) {
            result.put(TYPE, "http");
            putIfPresent(result, "scheme", http.scheme());
            putIfPresent(result, "bearerFormat", http.bearerFormat());
            putIfPresent(result, DESCRIPTION, http.description());
        } else if (scheme instanceof OAuth2SecurityScheme oauth2) {
            result.put(TYPE, "oauth2");
            result.put("flows", convertToNacosOAuthFlows(oauth2.flows()));
            putIfPresent(result, "oauth2MetadataUrl", oauth2.oauth2MetadataUrl());
            putIfPresent(result, DESCRIPTION, oauth2.description());
        } else if (scheme instanceof OpenIdConnectSecurityScheme openIdConnect) {
            result.put(TYPE, "openIdConnect");
            putIfPresent(result, "openIdConnectUrl", openIdConnect.openIdConnectUrl());
            putIfPresent(result, DESCRIPTION, openIdConnect.description());
        } else if (scheme instanceof MutualTLSSecurityScheme mutualTls) {
            result.put(TYPE, "mutualTLS");
            putIfPresent(result, DESCRIPTION, mutualTls.description());
        }
        return result;
    }

    private static Map<String, Object> convertToNacosOAuthFlows(OAuthFlows flows) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (null == flows) {
            return result;
        }
        if (null != flows.authorizationCode()) {
            AuthorizationCodeOAuthFlow code = flows.authorizationCode();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "authorizationUrl", code.authorizationUrl());
            putIfPresent(flow, "tokenUrl", code.tokenUrl());
            putIfPresent(flow, "refreshUrl", code.refreshUrl());
            flow.put("scopes", code.scopes());
            flow.put("pkceRequired", code.pkceRequired());
            result.put("authorizationCode", flow);
        }
        if (null != flows.clientCredentials()) {
            ClientCredentialsOAuthFlow client = flows.clientCredentials();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "tokenUrl", client.tokenUrl());
            putIfPresent(flow, "refreshUrl", client.refreshUrl());
            flow.put("scopes", client.scopes());
            result.put("clientCredentials", flow);
        }
        if (null != flows.deviceCode()) {
            DeviceCodeOAuthFlow device = flows.deviceCode();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "deviceAuthorizationUrl", device.deviceAuthorizationUrl());
            putIfPresent(flow, "tokenUrl", device.tokenUrl());
            putIfPresent(flow, "refreshUrl", device.refreshUrl());
            flow.put("scopes", device.scopes());
            result.put("deviceCode", flow);
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (null != value) {
            map.put(key, value);
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = null == map ? null : map.get(key);
        return null == value ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = null == map ? null : map.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> scopes(Map<String, Object> flow) {
        Object value = flow.get("scopes");
        return value instanceof Map<?, ?> scopes ? (Map<String, String>) scopes : Map.of();
    }

    private static <T, R> List<R> mapList(List<T> values, Function<T, R> mapper) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(mapper).toList();
    }
}
