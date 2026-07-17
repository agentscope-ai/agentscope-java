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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.ai.model.a2a.SecurityScheme;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentCardConverterUtil}.
 */
class AgentCardConverterUtilTest {
    @Test
    @DisplayName("Should return null when converting null Nacos AgentCard to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullInput() {
        assertNull(AgentCardConverterUtil.convertToA2aAgentCard(null));
    }

    @Test
    @DisplayName("Should convert Nacos AgentCard to A2A AgentCard correctly")
    void testConvertToA2aAgentCard() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertNotNull(result);
        assertEquals(
                nacosAgentCard.getProtocolVersion(),
                result.supportedInterfaces().get(0).protocolVersion());
        assertEquals(nacosAgentCard.getName(), result.name());
        assertEquals(nacosAgentCard.getDescription(), result.description());
        assertEquals(nacosAgentCard.getVersion(), result.version());
        assertEquals(nacosAgentCard.getIconUrl(), result.iconUrl());
        assertEquals(nacosAgentCard.getUrl(), result.url());
        assertEquals(nacosAgentCard.getPreferredTransport(), result.preferredTransport());
        assertEquals(nacosAgentCard.getDocumentationUrl(), result.documentationUrl());
        assertEquals(
                nacosAgentCard.getSecurity(),
                result.securityRequirements().stream().map(SecurityRequirement::schemes).toList());
        assertEquals(nacosAgentCard.getDefaultInputModes(), result.defaultInputModes());
        assertEquals(nacosAgentCard.getDefaultOutputModes(), result.defaultOutputModes());
        assertEquals(
                nacosAgentCard.getSupportsAuthenticatedExtendedCard(),
                result.capabilities().extendedAgentCard());

        // Check capabilities
        assertNotNull(result.capabilities());
        assertEquals(
                nacosAgentCard.getCapabilities().getStreaming(), result.capabilities().streaming());
        assertEquals(
                nacosAgentCard.getCapabilities().getPushNotifications(),
                result.capabilities().pushNotifications());

        // Check provider
        assertNotNull(result.provider());
        assertEquals(
                nacosAgentCard.getProvider().getOrganization(), result.provider().organization());
        assertEquals(nacosAgentCard.getProvider().getUrl(), result.provider().url());

        // Check skills
        assertNotNull(result.skills());
        assertEquals(nacosAgentCard.getSkills().size(), result.skills().size());
        for (int i = 0; i < nacosAgentCard.getSkills().size(); i++) {
            AgentSkill nacosSkill = nacosAgentCard.getSkills().get(i);
            org.a2aproject.sdk.spec.AgentSkill a2aSkill = result.skills().get(i);
            assertEquals(nacosSkill.getId(), a2aSkill.id());
            assertEquals(nacosSkill.getName(), a2aSkill.name());
            assertEquals(nacosSkill.getDescription(), a2aSkill.description());
            assertEquals(nacosSkill.getTags(), a2aSkill.tags());
            assertEquals(nacosSkill.getExamples(), a2aSkill.examples());
            assertEquals(nacosSkill.getInputModes(), a2aSkill.inputModes());
            assertEquals(nacosSkill.getOutputModes(), a2aSkill.outputModes());
        }

        // Check interfaces
        assertNotNull(result.additionalInterfaces());
        assertEquals(
                nacosAgentCard.getAdditionalInterfaces().size(),
                result.additionalInterfaces().size());
        for (int i = 0; i < nacosAgentCard.getAdditionalInterfaces().size(); i++) {
            AgentInterface nacosInterface = nacosAgentCard.getAdditionalInterfaces().get(i);
            Legacy_0_3_AgentInterface a2aInterface = result.additionalInterfaces().get(i);
            assertEquals(nacosInterface.getTransport(), a2aInterface.transport());
            assertEquals(nacosInterface.getUrl(), a2aInterface.url());
        }

        // Check security schemes
        assertNotNull(result.securitySchemes());
        assertEquals(nacosAgentCard.getSecuritySchemes().size(), result.securitySchemes().size());
    }

    @Test
    @DisplayName("Should convert A2A AgentCard to Nacos AgentCard correctly")
    void testConvertToNacosAgentCard() {
        org.a2aproject.sdk.spec.AgentCard a2aAgentCard = createSampleA2aAgentCard();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(a2aAgentCard);

        assertNotNull(result);
        assertEquals(
                a2aAgentCard.supportedInterfaces().get(0).protocolVersion(),
                result.getProtocolVersion());
        assertEquals(a2aAgentCard.name(), result.getName());
        assertEquals(a2aAgentCard.description(), result.getDescription());
        assertEquals(a2aAgentCard.version(), result.getVersion());
        assertEquals(a2aAgentCard.iconUrl(), result.getIconUrl());
        assertEquals(a2aAgentCard.url(), result.getUrl());
        assertEquals(a2aAgentCard.preferredTransport(), result.getPreferredTransport());
        assertEquals(a2aAgentCard.documentationUrl(), result.getDocumentationUrl());
        assertEquals(
                a2aAgentCard.securityRequirements().stream()
                        .map(SecurityRequirement::schemes)
                        .toList(),
                result.getSecurity());
        assertEquals(a2aAgentCard.defaultInputModes(), result.getDefaultInputModes());
        assertEquals(a2aAgentCard.defaultOutputModes(), result.getDefaultOutputModes());
        assertEquals(
                a2aAgentCard.capabilities().extendedAgentCard(),
                result.getSupportsAuthenticatedExtendedCard());

        // Check capabilities
        assertNotNull(result.getCapabilities());
        assertEquals(
                a2aAgentCard.capabilities().streaming(), result.getCapabilities().getStreaming());
        assertEquals(
                a2aAgentCard.capabilities().pushNotifications(),
                result.getCapabilities().getPushNotifications());
        assertFalse(result.getCapabilities().getStateTransitionHistory());

        // Check provider
        assertNotNull(result.getProvider());
        assertEquals(
                a2aAgentCard.provider().organization(), result.getProvider().getOrganization());
        assertEquals(a2aAgentCard.provider().url(), result.getProvider().getUrl());

        // Check skills
        assertNotNull(result.getSkills());
        assertEquals(a2aAgentCard.skills().size(), result.getSkills().size());
        for (int i = 0; i < a2aAgentCard.skills().size(); i++) {
            org.a2aproject.sdk.spec.AgentSkill a2aSkill = a2aAgentCard.skills().get(i);
            AgentSkill nacosSkill = result.getSkills().get(i);
            assertEquals(a2aSkill.id(), nacosSkill.getId());
            assertEquals(a2aSkill.name(), nacosSkill.getName());
            assertEquals(a2aSkill.description(), nacosSkill.getDescription());
            assertEquals(a2aSkill.tags(), nacosSkill.getTags());
            assertEquals(a2aSkill.examples(), nacosSkill.getExamples());
            assertEquals(a2aSkill.inputModes(), nacosSkill.getInputModes());
            assertEquals(a2aSkill.outputModes(), nacosSkill.getOutputModes());
        }

        // Check interfaces
        assertNotNull(result.getAdditionalInterfaces());
        assertEquals(
                a2aAgentCard.supportedInterfaces().size(), result.getAdditionalInterfaces().size());
        for (int i = 0; i < a2aAgentCard.supportedInterfaces().size(); i++) {
            org.a2aproject.sdk.spec.AgentInterface a2aInterface =
                    a2aAgentCard.supportedInterfaces().get(i);
            AgentInterface nacosInterface = result.getAdditionalInterfaces().get(i);
            assertEquals(a2aInterface.url(), nacosInterface.getUrl());
            assertEquals(a2aInterface.protocolBinding(), nacosInterface.getTransport());
        }

        // Check security schemes
        assertNotNull(result.getSecuritySchemes());
        assertEquals(a2aAgentCard.securitySchemes().size(), result.getSecuritySchemes().size());
    }

    @Test
    @DisplayName("Should handle null capabilities when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullCapabilities() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setCapabilities(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);
        assertNotNull(result.capabilities());
        assertFalse(result.capabilities().streaming());
        assertFalse(result.capabilities().pushNotifications());
        assertTrue(result.capabilities().extendedAgentCard());
    }

    @Test
    @DisplayName("Should handle null provider when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullProvider() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setProvider(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertNotNull(result);
        assertNull(result.provider());
    }

    @Test
    @DisplayName("Should handle null skills when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullSkills() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setSkills(null);

        assertTrue(AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard).skills().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty interfaces when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithEmptyInterfaces() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setAdditionalInterfaces(Collections.emptyList());

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertNotNull(result);
        assertEquals(0, result.additionalInterfaces().size());
    }

    @Test
    @DisplayName("Should handle null interfaces when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullInterfaces() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setAdditionalInterfaces(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertNotNull(result);
        assertEquals(0, result.additionalInterfaces().size());
    }

    @Test
    @DisplayName("Should handle null security schemes when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullSecuritySchemes() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setSecuritySchemes(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertNotNull(result);
        assertNull(result.securitySchemes());
    }

    @Test
    @DisplayName("Should handle null provider when converting to Nacos AgentCard")
    void testConvertToNacosAgentCardWithNullProvider() {
        org.a2aproject.sdk.spec.AgentCard a2aAgentCard = createSampleA2aAgentCard();
        org.a2aproject.sdk.spec.AgentProvider nullProvider = null;

        // Create a new A2A agent card with null provider
        org.a2aproject.sdk.spec.AgentCard cardWithNullProvider =
                org.a2aproject.sdk.spec.AgentCard.builder(a2aAgentCard)
                        .provider(nullProvider)
                        .build();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(cardWithNullProvider);

        assertNotNull(result);
        assertNull(result.getProvider());
    }

    @Test
    @DisplayName("Should handle empty interfaces when converting to Nacos AgentCard")
    void testConvertToNacosAgentCardWithEmptyInterfaces() {
        org.a2aproject.sdk.spec.AgentCard a2aAgentCard = createSampleA2aAgentCard();

        // Create a new A2A agent card with empty interfaces
        org.a2aproject.sdk.spec.AgentCard cardWithEmptyInterfaces =
                org.a2aproject.sdk.spec.AgentCard.builder(a2aAgentCard)
                        .supportedInterfaces(List.of())
                        .additionalInterfaces(List.of())
                        .build();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(cardWithEmptyInterfaces);

        assertNotNull(result);
        assertEquals(0, result.getAdditionalInterfaces().size());
    }

    @Test
    @DisplayName("Should handle null security schemes when converting to Nacos AgentCard")
    void testConvertToNacosAgentCardWithNullSecuritySchemes() {
        org.a2aproject.sdk.spec.AgentCard a2aAgentCard = createSampleA2aAgentCard();
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> nullSecuritySchemes = null;

        // Create a new A2A agent card with null security schemes
        org.a2aproject.sdk.spec.AgentCard cardWithNullSecuritySchemes =
                org.a2aproject.sdk.spec.AgentCard.builder(a2aAgentCard)
                        .securitySchemes(nullSecuritySchemes)
                        .build();

        AgentCard result =
                AgentCardConverterUtil.convertToNacosAgentCard(cardWithNullSecuritySchemes);

        assertNotNull(result);
        assertNull(result.getSecuritySchemes());
    }

    /**
     * Helper method to create a sample Nacos AgentCard for testing.
     */
    private AgentCard createSampleNacosAgentCard() {
        AgentCard card = new AgentCard();
        card.setProtocolVersion("1.0");
        card.setName("test-agent");
        card.setDescription("A test agent");
        card.setVersion("1.0.0");
        card.setIconUrl("http://example.com/icon.png");
        card.setUrl("http://example.com");
        card.setPreferredTransport("JSONRPC");
        card.setDocumentationUrl("http://example.com/docs");
        card.setSecurity(List.of(Map.of("scheme1", List.of("test"))));
        card.setDefaultInputModes(List.of("text", "voice"));
        card.setDefaultOutputModes(List.of("text", "audio"));
        card.setSupportsAuthenticatedExtendedCard(true);

        // Set capabilities
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setStreaming(true);
        capabilities.setPushNotifications(false);
        capabilities.setStateTransitionHistory(true);
        card.setCapabilities(capabilities);

        // Set provider
        AgentProvider provider = new AgentProvider();
        provider.setUrl("http://test.org");
        provider.setOrganization("Test Org");
        card.setProvider(provider);

        // Set skills
        AgentSkill skill1 = new AgentSkill();
        skill1.setId("skill-1");
        skill1.setName("Test Skill");
        skill1.setDescription("A test skill");
        skill1.setTags(List.of("tag1", "tag2"));
        skill1.setExamples(List.of("example1", "example2"));
        skill1.setInputModes(List.of("text"));
        skill1.setOutputModes(List.of("text"));

        AgentSkill skill2 = new AgentSkill();
        skill2.setId("skill-2");
        skill2.setName("Another Skill");
        skill2.setDescription("Another test skill");
        skill2.setTags(List.of("tag3"));
        skill2.setExamples(List.of("example3"));
        skill2.setInputModes(List.of("voice"));
        skill2.setOutputModes(List.of("audio"));

        card.setSkills(List.of(skill1, skill2));

        // Set interfaces
        AgentInterface interface1 = new AgentInterface();
        interface1.setTransport("JSONRPC");
        interface1.setUrl("http://example.com");
        AgentInterface interface2 = new AgentInterface();
        interface2.setTransport("GRPC");
        interface2.setUrl("http://interface2.com");
        card.setAdditionalInterfaces(List.of(interface1, interface2));

        // Set security schemes
        SecurityScheme scheme1 = new SecurityScheme();
        scheme1.put("type", "mutualTLS");
        SecurityScheme scheme2 = new SecurityScheme();
        scheme2.put("type", "mutualTLS");

        card.setSecuritySchemes(Map.of("scheme1", scheme1, "scheme2", scheme2));

        return card;
    }

    /**
     * Helper method to create a sample A2A AgentCard for testing.
     */
    private org.a2aproject.sdk.spec.AgentCard createSampleA2aAgentCard() {
        org.a2aproject.sdk.spec.AgentProvider provider =
                new org.a2aproject.sdk.spec.AgentProvider("Test Org", "http://test.org");

        org.a2aproject.sdk.spec.AgentCapabilities capabilities =
                org.a2aproject.sdk.spec.AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .extendedAgentCard(true)
                        .build();

        org.a2aproject.sdk.spec.AgentSkill skill1 =
                org.a2aproject.sdk.spec.AgentSkill.builder()
                        .id("skill-1")
                        .name("Test Skill")
                        .description("A test skill")
                        .tags(List.of("tag1", "tag2"))
                        .examples(List.of("example1", "example2"))
                        .inputModes(List.of("text"))
                        .outputModes(List.of("text"))
                        .build();

        org.a2aproject.sdk.spec.AgentSkill skill2 =
                org.a2aproject.sdk.spec.AgentSkill.builder()
                        .id("skill-2")
                        .name("Another Skill")
                        .description("Another test skill")
                        .tags(List.of("tag3"))
                        .examples(List.of("example3"))
                        .inputModes(List.of("voice"))
                        .outputModes(List.of("audio"))
                        .build();

        org.a2aproject.sdk.spec.AgentInterface interface1 =
                new org.a2aproject.sdk.spec.AgentInterface("JSONRPC", "http://example.com");
        org.a2aproject.sdk.spec.AgentInterface interface2 =
                new org.a2aproject.sdk.spec.AgentInterface("GRPC", "http://interface2.com");

        Legacy_0_3_AgentInterface legacyInterface1 =
                new Legacy_0_3_AgentInterface("JSONRPC", "http://example.com");
        Legacy_0_3_AgentInterface legacyInterface2 =
                new Legacy_0_3_AgentInterface("GRPC", "http://interface2.com");

        org.a2aproject.sdk.spec.SecurityScheme scheme1 = new MutualTLSSecurityScheme("scheme1");
        org.a2aproject.sdk.spec.SecurityScheme scheme2 = new MutualTLSSecurityScheme("scheme2");

        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name("test-agent")
                .description("A test agent")
                .version("1.0.0")
                .iconUrl("http://example.com/icon.png")
                .capabilities(capabilities)
                .provider(provider)
                .skills(List.of(skill1, skill2))
                .url("http://example.com")
                .preferredTransport("JSONRPC")
                .supportedInterfaces(List.of(interface1, interface2))
                .additionalInterfaces(List.of(legacyInterface1, legacyInterface2))
                .documentationUrl("http://example.com/docs")
                .securitySchemes(Map.of("scheme1", scheme1, "scheme2", scheme2))
                .securityRequirements(
                        List.of(new SecurityRequirement(Map.of("scheme1", List.of("test")))))
                .defaultInputModes(List.of("text", "voice"))
                .defaultOutputModes(List.of("text", "audio"))
                .build();
    }
}
