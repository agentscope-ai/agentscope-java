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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

    private static final String EMPTY_TENANT = "";

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

        // Build A2A specification AgentCard object using Builder pattern, setting properties one by
        // one
        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name(agentCard.getName())
                .description(agentCard.getDescription())
                .version(agentCard.getVersion())
                .iconUrl(agentCard.getIconUrl())
                .capabilities(convertToA2aAgentCapabilities(agentCard.getCapabilities()))
                .skills(convertToA2aAgentSkills(agentCard.getSkills()))
                .url(agentCard.getUrl())
                .preferredTransport(agentCard.getPreferredTransport())
                .additionalInterfaces(
                        convertToA2aLegacyAgentInterfaces(agentCard.getAdditionalInterfaces()))
                .provider(convertToA2aAgentProvider(agentCard.getProvider()))
                .documentationUrl(agentCard.getDocumentationUrl())
                .securitySchemes(convertToA2aAgentSecuritySchemes(agentCard.getSecuritySchemes()))
                .securityRequirements(convertToA2aSecurityRequirements(agentCard.getSecurity()))
                .defaultInputModes(agentCard.getDefaultInputModes())
                .defaultOutputModes(agentCard.getDefaultOutputModes())
                .supportedInterfaces(convertToA2aAgentInterfaces(agentCard))
                .build();
    }

    private static Map<String, org.a2aproject.sdk.spec.SecurityScheme>
            convertToA2aAgentSecuritySchemes(Map<String, SecurityScheme> securitySchemes) {
        if (null == securitySchemes) {
            return null;
        }
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> result =
                new java.util.LinkedHashMap<>();
        securitySchemes.forEach(
                (key, value) -> {
                    String type = value.get("type") instanceof String s ? s : null;
                    org.a2aproject.sdk.spec.SecurityScheme scheme =
                            convertToA2aSecurityScheme(type, value);
                    if (scheme != null) {
                        result.put(key, scheme);
                    }
                });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static org.a2aproject.sdk.spec.SecurityScheme convertToA2aSecurityScheme(
            String type, Map<String, Object> data) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "mtlsSecurityScheme", "mutualTLS" ->
                    new org.a2aproject.sdk.spec.MutualTLSSecurityScheme(
                            (String) data.get("description"));
            default -> null;
        };
    }

    private static org.a2aproject.sdk.spec.AgentProvider convertToA2aAgentProvider(
            AgentProvider provider) {
        if (null == provider) {
            return null;
        }
        return new org.a2aproject.sdk.spec.AgentProvider(
                provider.getOrganization(), provider.getUrl());
    }

    private static List<org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface>
            convertToA2aLegacyAgentInterfaces(List<AgentInterface> nacosInterfaces) {
        if (nacosInterfaces == null || nacosInterfaces.isEmpty()) {
            return List.of();
        }
        return nacosInterfaces.stream()
                .filter(Objects::nonNull)
                .map(AgentCardConverterUtil::convertToA2aLegacyAgentInterface)
                .collect(Collectors.toList());
    }

    private static org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface
            convertToA2aLegacyAgentInterface(AgentInterface agentInterface) {
        return new org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface(
                agentInterface.getTransport(), agentInterface.getUrl());
    }

    private static List<org.a2aproject.sdk.spec.AgentInterface> convertToA2aAgentInterfaces(
            AgentCard agentCard) {
        Map<String, org.a2aproject.sdk.spec.AgentInterface> result = new LinkedHashMap<>();
        putA2aAgentInterface(result, agentCard.getPreferredTransport(), agentCard.getUrl());
        List<AgentInterface> additionalInterfaces = agentCard.getAdditionalInterfaces();
        if (additionalInterfaces != null) {
            additionalInterfaces.stream()
                    .filter(Objects::nonNull)
                    .forEach(
                            agentInterface ->
                                    putA2aAgentInterface(
                                            result,
                                            agentInterface.getTransport(),
                                            agentInterface.getUrl()));
        }
        return List.copyOf(result.values());
    }

    private static void putA2aAgentInterface(
            Map<String, org.a2aproject.sdk.spec.AgentInterface> result,
            String transport,
            String url) {
        if (isBlank(transport) || isBlank(url)) {
            return;
        }
        String protocolBinding = normalizeTransport(transport);
        result.putIfAbsent(
                protocolBinding,
                new org.a2aproject.sdk.spec.AgentInterface(
                        protocolBinding, url.trim(), EMPTY_TENANT, null));
    }

    private static org.a2aproject.sdk.spec.AgentCapabilities convertToA2aAgentCapabilities(
            AgentCapabilities nacosCapabilities) {
        if (nacosCapabilities == null) {
            return org.a2aproject.sdk.spec.AgentCapabilities.builder().build();
        }

        return org.a2aproject.sdk.spec.AgentCapabilities.builder()
                .streaming(nacosCapabilities.getStreaming())
                .pushNotifications(nacosCapabilities.getPushNotifications())
                .extendedAgentCard(nacosCapabilities.getStateTransitionHistory())
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
        AgentCard card = new AgentCard();
        List<AgentInterface> nacosAgentInterfaces =
                convertToNacosAgentInterfaces(agentCard.additionalInterfaces());
        if (nacosAgentInterfaces.isEmpty()) {
            nacosAgentInterfaces =
                    convertToNacosAgentInterfacesFromSupported(agentCard.supportedInterfaces());
        }
        String url = firstNonBlank(agentCard.url(), firstInterfaceUrl(nacosAgentInterfaces));
        String preferredTransport =
                firstNonBlank(
                        agentCard.preferredTransport(),
                        firstInterfaceTransport(nacosAgentInterfaces));
        card.setName(agentCard.name());
        card.setDescription(agentCard.description());
        card.setVersion(agentCard.version());
        card.setIconUrl(agentCard.iconUrl());
        card.setCapabilities(convertToNacosAgentCapabilities(agentCard.capabilities()));
        card.setSkills(
                agentCard.skills().stream()
                        .map(AgentCardConverterUtil::convertToNacosAgentSkill)
                        .toList());
        card.setUrl(url);
        card.setPreferredTransport(preferredTransport);
        card.setAdditionalInterfaces(nacosAgentInterfaces);
        card.setProvider(convertToNacosAgentProvider(agentCard.provider()));
        card.setDocumentationUrl(agentCard.documentationUrl());
        card.setSecuritySchemes(convertToNacosSecuritySchemes(agentCard.securitySchemes()));
        card.setSecurity(convertToNacosSecurity(agentCard.securityRequirements()));
        card.setDefaultInputModes(agentCard.defaultInputModes());
        card.setDefaultOutputModes(agentCard.defaultOutputModes());
        String protocolVersion = resolveProtocolVersion(agentCard);
        card.setProtocolVersion(protocolVersion);
        return card;
    }

    private static AgentCapabilities convertToNacosAgentCapabilities(
            org.a2aproject.sdk.spec.AgentCapabilities capabilities) {
        AgentCapabilities nacosCapabilities = new AgentCapabilities();
        nacosCapabilities.setStreaming(capabilities.streaming());
        nacosCapabilities.setPushNotifications(capabilities.pushNotifications());
        nacosCapabilities.setStateTransitionHistory(capabilities.extendedAgentCard());
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
            List<org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface> agentInterfaces) {
        if (agentInterfaces == null) {
            return List.of();
        }
        return agentInterfaces.stream()
                .map(AgentCardConverterUtil::convertToNacosAgentInterface)
                .collect(Collectors.toList());
    }

    private static AgentInterface convertToNacosAgentInterface(
            org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url());
        nacosAgentInterface.setTransport(agentInterface.transport());
        return nacosAgentInterface;
    }

    private static List<AgentInterface> convertToNacosAgentInterfacesFromSupported(
            List<org.a2aproject.sdk.spec.AgentInterface> agentInterfaces) {
        if (agentInterfaces == null) {
            return List.of();
        }
        return agentInterfaces.stream()
                .filter(Objects::nonNull)
                .filter(
                        agentInterface ->
                                !isBlank(agentInterface.protocolBinding())
                                        && !isBlank(agentInterface.url()))
                .map(AgentCardConverterUtil::convertSupportedInterfaceToNacosAgentInterface)
                .collect(Collectors.toList());
    }

    private static AgentInterface convertSupportedInterfaceToNacosAgentInterface(
            org.a2aproject.sdk.spec.AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url().trim());
        nacosAgentInterface.setTransport(normalizeTransport(agentInterface.protocolBinding()));
        return nacosAgentInterface;
    }

    private static String firstInterfaceUrl(List<AgentInterface> agentInterfaces) {
        return agentInterfaces.stream()
                .map(AgentInterface::getUrl)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse(null);
    }

    private static String firstInterfaceTransport(List<AgentInterface> agentInterfaces) {
        return agentInterfaces.stream()
                .map(AgentInterface::getTransport)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse(null);
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

    private static List<org.a2aproject.sdk.spec.SecurityRequirement>
            convertToA2aSecurityRequirements(List<Map<String, List<String>>> security) {
        if (security == null) {
            return List.of();
        }
        return security.stream()
                .map(org.a2aproject.sdk.spec.SecurityRequirement::new)
                .collect(Collectors.toList());
    }

    private static List<Map<String, List<String>>> convertToNacosSecurity(
            List<org.a2aproject.sdk.spec.SecurityRequirement> securityRequirements) {
        if (securityRequirements == null) {
            return null;
        }
        return securityRequirements.stream()
                .map(org.a2aproject.sdk.spec.SecurityRequirement::schemes)
                .collect(Collectors.toList());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
    }

    private static String normalizeTransport(String transport) {
        return transport.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String resolveProtocolVersion(org.a2aproject.sdk.spec.AgentCard agentCard) {
        List<org.a2aproject.sdk.spec.AgentInterface> interfaces = agentCard.supportedInterfaces();
        if (interfaces != null && !interfaces.isEmpty()) {
            for (org.a2aproject.sdk.spec.AgentInterface iface : interfaces) {
                if (iface.protocolVersion() != null) {
                    return iface.protocolVersion();
                }
            }
        }
        return org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION;
    }
}
