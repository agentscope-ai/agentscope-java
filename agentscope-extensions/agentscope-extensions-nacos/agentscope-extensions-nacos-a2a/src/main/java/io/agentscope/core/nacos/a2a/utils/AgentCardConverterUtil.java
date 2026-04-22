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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AgentCard Converter between Nacos {@link AgentCard} and A2A specification {@link io.a2a.spec.AgentCard}.
 *
 * <p>This utility also converts sub-specifications when converting AgentCard:
 * <ul>
 *     <li>Nacos {@link AgentInterface} and A2A {@link io.a2a.spec.AgentInterface}.</li>
 *     <li>Nacos {@link AgentProvider} and A2A {@link io.a2a.spec.AgentProvider}.</li>
 *     <li>Nacos {@link AgentCapabilities} and A2A {@link io.a2a.spec.AgentCapabilities}.</li>
 *     <li>Nacos {@link AgentSkill} and A2A {@link io.a2a.spec.AgentSkill}.</li>
 * </ul>
 *
 * <p>Unlike the sub-specifications listed above, {@link SecurityScheme} is handled differently.
 * All types of A2A {@link io.a2a.spec.SecurityScheme} are serialized and stored as a generic {@link Map}
 * representation, and Nacos does not retain or expose the concrete subtype of {@link io.a2a.spec.SecurityScheme}.
 */
public class AgentCardConverterUtil {

    /**
     * Converts Nacos AgentCard object to A2A specification AgentCard object.
     *
     * @param agentCard the Nacos AgentCard object
     * @return the converted A2A specification AgentCard object, or null if input is null
     */
    public static io.a2a.spec.AgentCard convertToA2aAgentCard(AgentCard agentCard) {
        if (agentCard == null) {
            return null;
        }

        // Build A2A specification AgentCard object using Builder pattern, setting properties one by
        // one
        return new io.a2a.spec.AgentCard.Builder()
                .protocolVersion(agentCard.getProtocolVersion())
                .name(agentCard.getName())
                .description(agentCard.getDescription())
                .version(agentCard.getVersion())
                .iconUrl(agentCard.getIconUrl())
                .capabilities(convertToA2aAgentCapabilities(agentCard.getCapabilities()))
                .skills(convertToA2aAgentSkills(agentCard.getSkills()))
                .url(agentCard.getUrl())
                .preferredTransport(agentCard.getPreferredTransport())
                .additionalInterfaces(
                        convertToA2aAgentInterfaces(agentCard.getAdditionalInterfaces()))
                .provider(convertToA2aAgentProvider(agentCard.getProvider()))
                .documentationUrl(agentCard.getDocumentationUrl())
                .securitySchemes(convertToA2aAgentSecuritySchemes(agentCard.getSecuritySchemes()))
                .security(agentCard.getSecurity())
                .defaultInputModes(agentCard.getDefaultInputModes())
                .defaultOutputModes(agentCard.getDefaultOutputModes())
                .supportsAuthenticatedExtendedCard(agentCard.getSupportsAuthenticatedExtendedCard())
                .build();
    }

    private static Map<String, io.a2a.spec.SecurityScheme> convertToA2aAgentSecuritySchemes(
            Map<String, SecurityScheme> securitySchemes) {
        if (null == securitySchemes) {
            return null;
        }
        String securitySchemesJson = JacksonUtils.toJson(securitySchemes);
        return JacksonUtils.toObj(securitySchemesJson, new TypeReference<>() {});
    }

    private static io.a2a.spec.AgentProvider convertToA2aAgentProvider(AgentProvider provider) {
        if (null == provider) {
            return null;
        }
        return new io.a2a.spec.AgentProvider(provider.getOrganization(), provider.getUrl());
    }

    private static List<io.a2a.spec.AgentInterface> convertToA2aAgentInterfaces(
            List<AgentInterface> nacosInterfaces) {
        if (nacosInterfaces == null || nacosInterfaces.isEmpty()) {
            return List.of();
        }
        return nacosInterfaces.stream()
                .filter(Objects::nonNull)
                .map(AgentCardConverterUtil::convertToA2aAgentInterface)
                .collect(Collectors.toList());
    }

    private static io.a2a.spec.AgentInterface convertToA2aAgentInterface(
            AgentInterface agentInterface) {
        return new io.a2a.spec.AgentInterface(
                agentInterface.getTransport(), agentInterface.getUrl());
    }

    private static io.a2a.spec.AgentCapabilities convertToA2aAgentCapabilities(
            AgentCapabilities nacosCapabilities) {
        if (nacosCapabilities == null) {
            return new io.a2a.spec.AgentCapabilities.Builder().build();
        }

        return new io.a2a.spec.AgentCapabilities.Builder()
                .streaming(nacosCapabilities.getStreaming())
                .pushNotifications(nacosCapabilities.getPushNotifications())
                .stateTransitionHistory(nacosCapabilities.getStateTransitionHistory())
                .build();
    }

    private static List<io.a2a.spec.AgentSkill> convertToA2aAgentSkills(
            List<AgentSkill> nacosSkills) {
        if (nacosSkills == null || nacosSkills.isEmpty()) {
            return List.of();
        }

        return nacosSkills.stream()
                .map(AgentCardConverterUtil::convertToA2aAgentSkill)
                .collect(Collectors.toList());
    }

    private static io.a2a.spec.AgentSkill convertToA2aAgentSkill(AgentSkill nacosSkill) {
        return new io.a2a.spec.AgentSkill.Builder()
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
    public static AgentCard convertToNacosAgentCard(io.a2a.spec.AgentCard agentCard) {
        AgentCard card = new AgentCard();
        card.setProtocolVersion(agentCard.protocolVersion());
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
                convertToNacosAgentInterfaces(agentCard.additionalInterfaces()));
        card.setProvider(convertToNacosAgentProvider(agentCard.provider()));
        card.setDocumentationUrl(agentCard.documentationUrl());
        card.setSecuritySchemes(convertToNacosSecuritySchemes(agentCard.securitySchemes()));
        card.setSecurity(agentCard.security());
        card.setDefaultInputModes(agentCard.defaultInputModes());
        card.setDefaultOutputModes(agentCard.defaultOutputModes());
        card.setSupportsAuthenticatedExtendedCard(agentCard.supportsAuthenticatedExtendedCard());
        return card;
    }

    private static AgentCapabilities convertToNacosAgentCapabilities(
            io.a2a.spec.AgentCapabilities capabilities) {
        AgentCapabilities nacosCapabilities = new AgentCapabilities();
        nacosCapabilities.setStreaming(capabilities.streaming());
        nacosCapabilities.setPushNotifications(capabilities.pushNotifications());
        nacosCapabilities.setStateTransitionHistory(capabilities.stateTransitionHistory());
        return nacosCapabilities;
    }

    private static AgentSkill convertToNacosAgentSkill(io.a2a.spec.AgentSkill agentSkill) {
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
            List<io.a2a.spec.AgentInterface> agentInterfaces) {
        if (agentInterfaces == null) {
            return List.of();
        }
        return agentInterfaces.stream()
                .map(AgentCardConverterUtil::convertToNacosAgentInterface)
                .collect(Collectors.toList());
    }

    private static AgentInterface convertToNacosAgentInterface(
            io.a2a.spec.AgentInterface agentInterface) {
        AgentInterface nacosAgentInterface = new AgentInterface();
        nacosAgentInterface.setUrl(agentInterface.url());
        nacosAgentInterface.setTransport(agentInterface.transport());
        return nacosAgentInterface;
    }

    private static AgentProvider convertToNacosAgentProvider(
            io.a2a.spec.AgentProvider agentProvider) {
        if (null == agentProvider) {
            return null;
        }
        AgentProvider nacosAgentProvider = new AgentProvider();
        nacosAgentProvider.setOrganization(agentProvider.organization());
        nacosAgentProvider.setUrl(agentProvider.url());
        return nacosAgentProvider;
    }

    private static Map<String, SecurityScheme> convertToNacosSecuritySchemes(
            Map<String, io.a2a.spec.SecurityScheme> securitySchemes) {
        if (securitySchemes == null) {
            return null;
        }
        String originalJson = JacksonUtils.toJson(securitySchemes);
        return JacksonUtils.toObj(originalJson, new TypeReference<>() {});
    }
}
