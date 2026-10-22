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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
 *     <li>A2A security schemes are stored in Nacos as maps in the A2A 0.3 format, see
 *     {@code SecuritySchemeConverterUtil}.</li>
 * </ul>
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
                .securitySchemes(
                        SecuritySchemeConverterUtil.convertToA2aSecuritySchemes(
                                agentCard.getSecuritySchemes()))
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
        card.setSecuritySchemes(
                SecuritySchemeConverterUtil.convertToNacosSecuritySchemes(
                        agentCard.securitySchemes()));
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

    private static <T, R> List<R> mapList(List<T> values, Function<T, R> mapper) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(mapper).toList();
    }
}
