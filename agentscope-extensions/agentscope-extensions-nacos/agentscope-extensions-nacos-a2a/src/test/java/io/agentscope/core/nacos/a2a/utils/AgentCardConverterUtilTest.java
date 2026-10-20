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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.ai.model.a2a.SecurityScheme;
import com.alibaba.nacos.common.utils.JacksonUtils;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentCardConverterUtil}.
 */
class AgentCardConverterUtilTest {

    private static final org.a2aproject.sdk.spec.AgentInterface JSONRPC_INTERFACE =
            new org.a2aproject.sdk.spec.AgentInterface("JSONRPC", "http://example.com");

    private static final org.a2aproject.sdk.spec.AgentInterface GRPC_INTERFACE =
            new org.a2aproject.sdk.spec.AgentInterface("GRPC", "http://interface2.com");

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
        assertEquals(nacosAgentCard.getName(), result.name());
        assertEquals(nacosAgentCard.getDescription(), result.description());
        assertEquals(nacosAgentCard.getVersion(), result.version());
        assertEquals(nacosAgentCard.getIconUrl(), result.iconUrl());
        assertEquals(nacosAgentCard.getDocumentationUrl(), result.documentationUrl());
        assertEquals(
                List.of(new SecurityRequirement(Map.of("scheme1", List.of("test")))),
                result.securityRequirements());
        assertEquals(nacosAgentCard.getDefaultInputModes(), result.defaultInputModes());
        assertEquals(nacosAgentCard.getDefaultOutputModes(), result.defaultOutputModes());

        // The url and preferred transport come first; the same additional interface once.
        assertEquals(List.of(JSONRPC_INTERFACE, GRPC_INTERFACE), result.supportedInterfaces());
        result.supportedInterfaces().forEach(each -> assertEquals("1.0", each.protocolVersion()));

        // Check capabilities
        assertNotNull(result.capabilities());
        assertEquals(
                nacosAgentCard.getCapabilities().getStreaming(), result.capabilities().streaming());
        assertEquals(
                nacosAgentCard.getCapabilities().getPushNotifications(),
                result.capabilities().pushNotifications());
        assertEquals(
                nacosAgentCard.getSupportsAuthenticatedExtendedCard(),
                result.capabilities().extendedAgentCard());

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

        // Check security schemes
        assertEquals(2, result.securitySchemes().size());
        assertInstanceOf(MutualTLSSecurityScheme.class, result.securitySchemes().get("scheme1"));
    }

    @Test
    @DisplayName("Should keep the protocol version Nacos has for the interfaces")
    void testConvertToA2aAgentCardKeepsProtocolVersion() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setProtocolVersion("0.3.0");

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        result.supportedInterfaces().forEach(each -> assertEquals("0.3.0", each.protocolVersion()));
    }

    @Test
    @DisplayName("Should convert A2A AgentCard to Nacos AgentCard correctly")
    void testConvertToNacosAgentCard() {
        org.a2aproject.sdk.spec.AgentCard a2aAgentCard = createSampleA2aAgentCard();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(a2aAgentCard);

        assertNotNull(result);
        // The first interface is the preferred one.
        assertEquals("1.0", result.getProtocolVersion());
        assertEquals("http://example.com", result.getUrl());
        assertEquals("JSONRPC", result.getPreferredTransport());
        assertEquals(a2aAgentCard.name(), result.getName());
        assertEquals(a2aAgentCard.description(), result.getDescription());
        assertEquals(a2aAgentCard.version(), result.getVersion());
        assertEquals(a2aAgentCard.iconUrl(), result.getIconUrl());
        assertEquals(a2aAgentCard.documentationUrl(), result.getDocumentationUrl());
        assertEquals(List.of(Map.of("scheme1", List.of("test"))), result.getSecurity());
        assertEquals(a2aAgentCard.defaultInputModes(), result.getDefaultInputModes());
        assertEquals(a2aAgentCard.defaultOutputModes(), result.getDefaultOutputModes());
        assertTrue(result.getSupportsAuthenticatedExtendedCard());

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
        assertEquals(2, result.getAdditionalInterfaces().size());
        assertEquals("JSONRPC", result.getAdditionalInterfaces().get(0).getTransport());
        assertEquals("http://example.com", result.getAdditionalInterfaces().get(0).getUrl());
        assertEquals("GRPC", result.getAdditionalInterfaces().get(1).getTransport());
        assertEquals("http://interface2.com", result.getAdditionalInterfaces().get(1).getUrl());

        // Check security schemes
        assertEquals(Map.of("type", "mutualTLS"), result.getSecuritySchemes().get("scheme1"));
    }

    @Test
    @DisplayName("Should keep every kind of security scheme through Nacos")
    void testSecuritySchemesRoundTrip() {
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> schemes = new LinkedHashMap<>();
        schemes.put(
                "apiKey",
                new APIKeySecurityScheme(
                        APIKeySecurityScheme.Location.QUERY, "api_key", "Query key"));
        schemes.put("bearer", new HTTPAuthSecurityScheme("JWT", "bearer", null));
        schemes.put(
                "oauth",
                new OAuth2SecurityScheme(
                        new OAuthFlows(
                                new AuthorizationCodeOAuthFlow(
                                        "https://auth/authorize",
                                        "https://auth/refresh",
                                        Map.of("read", "Read"),
                                        "https://auth/token",
                                        true),
                                new ClientCredentialsOAuthFlow(
                                        null, Map.of(), "https://auth/token"),
                                new DeviceCodeOAuthFlow(
                                        "https://auth/device",
                                        "https://auth/token",
                                        null,
                                        Map.of("read", "Read"))),
                        "OAuth",
                        "https://auth/.well-known/oauth-authorization-server"));
        schemes.put(
                "oidc",
                new OpenIdConnectSecurityScheme(
                        "https://auth/.well-known/openid-configuration", null));
        schemes.put("mtls", new MutualTLSSecurityScheme("Client certificate"));
        org.a2aproject.sdk.spec.AgentCard card =
                org.a2aproject.sdk.spec.AgentCard.builder(createSampleA2aAgentCard())
                        .securitySchemes(schemes)
                        .build();

        // Nacos stores the card as JSON.
        String stored = JacksonUtils.toJson(AgentCardConverterUtil.convertToNacosAgentCard(card));
        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(
                        JacksonUtils.toObj(stored, AgentCard.class));

        assertEquals(schemes, result.securitySchemes());
    }

    @Test
    @DisplayName("Should read security schemes stored in the A2A 0.3 format")
    void testConvertToA2aAgentCardWithLegacySecuritySchemes() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        SecurityScheme apiKey = new SecurityScheme();
        apiKey.putAll(Map.of("type", "apiKey", "in", "header", "name", "X-API-Key"));
        // A2A 1.0 has no implicit or password flow.
        SecurityScheme oauth = new SecurityScheme();
        oauth.putAll(
                Map.of(
                        "type",
                        "oauth2",
                        "flows",
                        Map.of(
                                "clientCredentials",
                                Map.of("tokenUrl", "https://auth/token", "scopes", Map.of()),
                                "implicit",
                                Map.of("authorizationUrl", "https://auth/authorize"))));
        SecurityScheme unknown = new SecurityScheme();
        unknown.put("type", "unknown");
        nacosAgentCard.setSecuritySchemes(
                Map.of("apiKey", apiKey, "oauth", oauth, "unknown", unknown));

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertEquals(
                Map.of(
                        "apiKey",
                        new APIKeySecurityScheme(
                                APIKeySecurityScheme.Location.HEADER, "X-API-Key", null),
                        "oauth",
                        new OAuth2SecurityScheme(
                                new OAuthFlows(
                                        null,
                                        new ClientCredentialsOAuthFlow(
                                                null, Map.of(), "https://auth/token"),
                                        null),
                                null,
                                null)),
                result.securitySchemes());
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

        assertEquals(List.of(JSONRPC_INTERFACE), result.supportedInterfaces());
    }

    @Test
    @DisplayName("Should handle null interfaces when converting to A2A AgentCard")
    void testConvertToA2aAgentCardWithNullInterfaces() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setAdditionalInterfaces(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertEquals(List.of(JSONRPC_INTERFACE), result.supportedInterfaces());
    }

    @Test
    @DisplayName("Should use JSONRPC when Nacos has no preferred transport")
    void testConvertToA2aAgentCardWithoutPreferredTransport() {
        AgentCard nacosAgentCard = createSampleNacosAgentCard();
        nacosAgentCard.setPreferredTransport(null);
        nacosAgentCard.setAdditionalInterfaces(null);

        org.a2aproject.sdk.spec.AgentCard result =
                AgentCardConverterUtil.convertToA2aAgentCard(nacosAgentCard);

        assertEquals(List.of(JSONRPC_INTERFACE), result.supportedInterfaces());
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
        org.a2aproject.sdk.spec.AgentCard cardWithNullProvider =
                org.a2aproject.sdk.spec.AgentCard.builder(createSampleA2aAgentCard())
                        .provider(null)
                        .build();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(cardWithNullProvider);

        assertNotNull(result);
        assertNull(result.getProvider());
    }

    @Test
    @DisplayName("Should handle no interfaces when converting to Nacos AgentCard")
    void testConvertToNacosAgentCardWithNoInterfaces() {
        org.a2aproject.sdk.spec.AgentCard cardWithNoInterfaces =
                org.a2aproject.sdk.spec.AgentCard.builder(createSampleA2aAgentCard())
                        .supportedInterfaces(List.of())
                        .build();

        AgentCard result = AgentCardConverterUtil.convertToNacosAgentCard(cardWithNoInterfaces);

        assertNotNull(result);
        assertNull(result.getUrl());
        assertEquals(0, result.getAdditionalInterfaces().size());
    }

    @Test
    @DisplayName("Should handle null security schemes when converting to Nacos AgentCard")
    void testConvertToNacosAgentCardWithNullSecuritySchemes() {
        org.a2aproject.sdk.spec.AgentCard cardWithNullSecuritySchemes =
                org.a2aproject.sdk.spec.AgentCard.builder(createSampleA2aAgentCard())
                        .securitySchemes(null)
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

        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name("test-agent")
                .description("A test agent")
                .version("1.0.0")
                .iconUrl("http://example.com/icon.png")
                .capabilities(capabilities)
                .provider(provider)
                .skills(List.of(skill1, skill2))
                .supportedInterfaces(List.of(JSONRPC_INTERFACE, GRPC_INTERFACE))
                .documentationUrl("http://example.com/docs")
                .securitySchemes(
                        Map.of(
                                "scheme1",
                                new MutualTLSSecurityScheme(null),
                                "scheme2",
                                new MutualTLSSecurityScheme(null)))
                .securityRequirements(
                        List.of(new SecurityRequirement(Map.of("scheme1", List.of("test")))))
                .defaultInputModes(List.of("text", "voice"))
                .defaultOutputModes(List.of("text", "audio"))
                .build();
    }
}
