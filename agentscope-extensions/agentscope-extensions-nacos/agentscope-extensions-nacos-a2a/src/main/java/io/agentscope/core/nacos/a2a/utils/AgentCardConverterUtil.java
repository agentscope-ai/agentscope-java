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
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;

/**
 * AgentCard Converter between Nacos {@link AgentCard} and A2A specification {@link org.a2aproject.sdk.spec.AgentCard}.
 *
 * <p>This utility also converts sub-specifications when converting AgentCard:
 * <ul>
 *     <li>Nacos {@link AgentInterface} and A2A {@link org.a2aproject.sdk.spec.AgentInterface}.</li>
 *     <li>Nacos {@link AgentProvider} and A2A {@link org.a2aproject.sdk.spec.AgentProvider}.</li>
 *     <li>Nacos {@link AgentCapabilities} and A2A {@link org.a2aproject.sdk.spec.AgentCapabilities}.</li>
 *     <li>Nacos {@link AgentSkill} and A2A {@link org.a2aproject.sdk.spec.AgentSkill}.</li>
 * </ul>
 *
 * <p>Unlike the sub-specifications listed above, {@link SecurityScheme} is handled differently.
 * All types of A2A {@link org.a2aproject.sdk.spec.SecurityScheme} are serialized and stored as a generic {@link Map}
 * representation, and Nacos does not retain or expose the concrete subtype of {@link org.a2aproject.sdk.spec.SecurityScheme}.
 */
public class AgentCardConverterUtil {

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

        List<org.a2aproject.sdk.spec.AgentInterface> interfaces =
                convertToA2aAgentInterfaces(agentCard.getAdditionalInterfaces());

        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name(agentCard.getName())
                .description(agentCard.getDescription())
                .version(agentCard.getVersion())
                .iconUrl(agentCard.getIconUrl())
                .capabilities(
                        convertToA2aAgentCapabilities(
                                agentCard.getCapabilities(),
                                agentCard.getSupportsAuthenticatedExtendedCard()))
                .skills(convertToA2aAgentSkills(agentCard.getSkills()))
                .url(agentCard.getUrl())
                .preferredTransport(agentCard.getPreferredTransport())
                .supportedInterfaces(interfaces)
                .additionalInterfaces(toLegacyInterfaces(interfaces))
                .provider(convertToA2aAgentProvider(agentCard.getProvider()))
                .documentationUrl(agentCard.getDocumentationUrl())
                .securitySchemes(convertToA2aAgentSecuritySchemes(agentCard.getSecuritySchemes()))
                .securityRequirements(convertToA2aSecurityRequirements(agentCard.getSecurity()))
                .defaultInputModes(agentCard.getDefaultInputModes())
                .defaultOutputModes(agentCard.getDefaultOutputModes())
                .build();
    }

    private static Map<String, org.a2aproject.sdk.spec.SecurityScheme>
            convertToA2aAgentSecuritySchemes(Map<String, SecurityScheme> securitySchemes) {
        if (null == securitySchemes) {
            return null;
        }
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> result = new HashMap<>();
        securitySchemes.forEach(
                (name, securityScheme) -> {
                    org.a2aproject.sdk.spec.SecurityScheme converted =
                            convertToA2aSecurityScheme(securityScheme);
                    if (converted != null) {
                        result.put(name, converted);
                    }
                });
        return result;
    }

    private static org.a2aproject.sdk.spec.SecurityScheme convertToA2aSecurityScheme(
            SecurityScheme securityScheme) {
        if (securityScheme == null) {
            return null;
        }
        String type = stringValue(securityScheme.get("type"));
        String description = stringValue(securityScheme.get("description"));
        if (type == null) {
            return null;
        }
        return switch (type) {
            case MutualTLSSecurityScheme.TYPE, "mutualTLS" ->
                    new MutualTLSSecurityScheme(description);
            case HTTPAuthSecurityScheme.TYPE, "http", "httpAuth" ->
                    HTTPAuthSecurityScheme.builder()
                            .scheme(stringValue(securityScheme.get("scheme")))
                            .bearerFormat(stringValue(securityScheme.get("bearerFormat")))
                            .description(description)
                            .build();
            case APIKeySecurityScheme.TYPE, "apiKey" ->
                    APIKeySecurityScheme.builder()
                            .name(stringValue(securityScheme.get("name")))
                            .location(apiKeyLocation(securityScheme))
                            .description(description)
                            .build();
            case OpenIdConnectSecurityScheme.TYPE, "openIdConnect" ->
                    OpenIdConnectSecurityScheme.builder()
                            .openIdConnectUrl(stringValue(securityScheme.get("openIdConnectUrl")))
                            .description(description)
                            .build();
            case OAuth2SecurityScheme.TYPE, "oauth2" ->
                    OAuth2SecurityScheme.builder()
                            .flows(oauthFlows(securityScheme.get("flows")))
                            .oauth2MetadataUrl(stringValue(securityScheme.get("oauth2MetadataUrl")))
                            .description(description)
                            .build();
            default -> null;
        };
    }

    private static APIKeySecurityScheme.Location apiKeyLocation(SecurityScheme securityScheme) {
        String location = stringValue(securityScheme.get("location"));
        if (location == null) {
            location = stringValue(securityScheme.get("in"));
        }
        if (location == null) {
            return APIKeySecurityScheme.Location.HEADER;
        }
        try {
            return APIKeySecurityScheme.Location.fromString(location);
        } catch (Exception ignored) {
            return APIKeySecurityScheme.Location.HEADER;
        }
    }

    private static OAuthFlows oauthFlows(Object flows) {
        if (flows == null) {
            return null;
        }
        try {
            return JacksonUtils.toObj(JacksonUtils.toJson(flows), OAuthFlows.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
            List<AgentInterface> nacosInterfaces) {
        if (nacosInterfaces == null || nacosInterfaces.isEmpty()) {
            return List.of();
        }
        return nacosInterfaces.stream()
                .filter(Objects::nonNull)
                .map(AgentCardConverterUtil::convertToA2aAgentInterface)
                .collect(Collectors.toList());
    }

    private static org.a2aproject.sdk.spec.AgentInterface convertToA2aAgentInterface(
            AgentInterface agentInterface) {
        return new org.a2aproject.sdk.spec.AgentInterface(
                agentInterface.getTransport(), agentInterface.getUrl());
    }

    private static List<Legacy_0_3_AgentInterface> toLegacyInterfaces(
            List<org.a2aproject.sdk.spec.AgentInterface> agentInterfaces) {
        if (agentInterfaces == null) {
            return null;
        }
        return agentInterfaces.stream()
                .filter(Objects::nonNull)
                .map(
                        agentInterface ->
                                new Legacy_0_3_AgentInterface(
                                        agentInterface.protocolBinding(), agentInterface.url()))
                .toList();
    }

    private static org.a2aproject.sdk.spec.AgentCapabilities convertToA2aAgentCapabilities(
            AgentCapabilities nacosCapabilities, Boolean supportsAuthenticatedExtendedCard) {
        if (nacosCapabilities == null) {
            return org.a2aproject.sdk.spec.AgentCapabilities.builder()
                    .extendedAgentCard(Boolean.TRUE.equals(supportsAuthenticatedExtendedCard))
                    .build();
        }

        return org.a2aproject.sdk.spec.AgentCapabilities.builder()
                .streaming(Boolean.TRUE.equals(nacosCapabilities.getStreaming()))
                .pushNotifications(Boolean.TRUE.equals(nacosCapabilities.getPushNotifications()))
                .extendedAgentCard(Boolean.TRUE.equals(supportsAuthenticatedExtendedCard))
                .build();
    }

    private static List<org.a2aproject.sdk.spec.AgentSkill> convertToA2aAgentSkills(
            List<AgentSkill> nacosSkills) {
        if (nacosSkills == null || nacosSkills.isEmpty()) {
            return List.of();
        }

        return nacosSkills.stream()
                .map(AgentCardConverterUtil::convertToA2aAgentSkill)
                .collect(Collectors.toList());
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

    /**
     * Converts A2A specification AgentCard object to Nacos AgentCard object.
     *
     * @param agentCard the A2A specification AgentCard object
     * @return the converted Nacos AgentCard object
     */
    public static AgentCard convertToNacosAgentCard(org.a2aproject.sdk.spec.AgentCard agentCard) {
        if (agentCard == null) {
            return null;
        }
        AgentCard card = new AgentCard();
        card.setProtocolVersion(getProtocolVersion(agentCard));
        card.setName(agentCard.name());
        card.setDescription(agentCard.description());
        card.setVersion(agentCard.version());
        card.setIconUrl(agentCard.iconUrl());
        card.setCapabilities(convertToNacosAgentCapabilities(agentCard.capabilities()));
        card.setSkills(
                agentCard.skills().stream()
                        .map(AgentCardConverterUtil::convertToNacosAgentSkill)
                        .toList());
        card.setUrl(agentCard.url());
        card.setPreferredTransport(agentCard.preferredTransport());
        card.setAdditionalInterfaces(
                convertToNacosAgentInterfaces(agentCard.supportedInterfaces()));
        card.setProvider(convertToNacosAgentProvider(agentCard.provider()));
        card.setDocumentationUrl(agentCard.documentationUrl());
        card.setSecuritySchemes(convertToNacosSecuritySchemes(agentCard.securitySchemes()));
        card.setSecurity(convertToNacosSecurityRequirements(agentCard.securityRequirements()));
        card.setDefaultInputModes(agentCard.defaultInputModes());
        card.setDefaultOutputModes(agentCard.defaultOutputModes());
        card.setSupportsAuthenticatedExtendedCard(
                agentCard.capabilities() != null && agentCard.capabilities().extendedAgentCard());
        return card;
    }

    private static String getProtocolVersion(org.a2aproject.sdk.spec.AgentCard agentCard) {
        if (agentCard.supportedInterfaces() == null || agentCard.supportedInterfaces().isEmpty()) {
            return org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION;
        }
        return agentCard.supportedInterfaces().get(0).protocolVersion();
    }

    private static AgentCapabilities convertToNacosAgentCapabilities(
            org.a2aproject.sdk.spec.AgentCapabilities capabilities) {
        AgentCapabilities nacosCapabilities = new AgentCapabilities();
        if (capabilities == null) {
            return nacosCapabilities;
        }
        nacosCapabilities.setStreaming(capabilities.streaming());
        nacosCapabilities.setPushNotifications(capabilities.pushNotifications());
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

    private static List<AgentInterface> convertToNacosAgentInterfaces(
            List<org.a2aproject.sdk.spec.AgentInterface> agentInterfaces) {
        if (agentInterfaces == null) {
            return List.of();
        }
        return agentInterfaces.stream()
                .map(AgentCardConverterUtil::convertToNacosAgentInterface)
                .collect(Collectors.toList());
    }

    private static AgentInterface convertToNacosAgentInterface(
            org.a2aproject.sdk.spec.AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url());
        nacosAgentInterface.setTransport(agentInterface.protocolBinding());
        return nacosAgentInterface;
    }

    private static List<SecurityRequirement> convertToA2aSecurityRequirements(
            List<Map<String, List<String>>> security) {
        if (security == null) {
            return null;
        }
        return security.stream().map(SecurityRequirement::new).toList();
    }

    private static List<Map<String, List<String>>> convertToNacosSecurityRequirements(
            List<SecurityRequirement> securityRequirements) {
        if (securityRequirements == null) {
            return null;
        }
        return securityRequirements.stream().map(SecurityRequirement::schemes).toList();
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
        String originalJson = JacksonUtils.toJson(securitySchemes);
        return JacksonUtils.toObj(originalJson, new TypeReference<>() {});
    }
}
