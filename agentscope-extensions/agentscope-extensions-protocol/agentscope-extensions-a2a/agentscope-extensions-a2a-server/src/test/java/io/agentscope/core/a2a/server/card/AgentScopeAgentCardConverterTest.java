/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentScopeAgentCardBuilder Tests")
class AgentScopeAgentCardConverterTest {

    private AgentScopeAgentCardConverter builder;
    private ConfigurableAgentCard configurableAgentCard;
    private AgentRunner agentRunner;
    private Set<TransportProperties> availableTransports;

    @BeforeEach
    void setUp() {
        builder = new AgentScopeAgentCardConverter();
        configurableAgentCard = mock(ConfigurableAgentCard.class);
        agentRunner = mock(AgentRunner.class);

        // Create transport properties
        TransportProperties jsonRpcTransport = mock(TransportProperties.class);
        when(jsonRpcTransport.transportType()).thenReturn(TransportProtocol.JSONRPC.asString());
        when(jsonRpcTransport.host()).thenReturn("localhost");
        when(jsonRpcTransport.port()).thenReturn(8080);
        when(jsonRpcTransport.path()).thenReturn("/jsonrpc");
        when(jsonRpcTransport.supportTls()).thenReturn(false);

        availableTransports = new HashSet<>();
        availableTransports.add(jsonRpcTransport);
    }

    @Test
    @DisplayName("Should create agent card with all configurable fields")
    void testCreateAgentCardWithAllConfigurableFields() {
        // Given
        String name = "Test Agent";
        String description = "Test Description";
        String url = "https://example.com/agent";
        AgentProvider provider = new AgentProvider("Test Provider", "https://provider.com");
        String version = "2.0.0";
        String documentationUrl = "https://docs.example.com";
        List<String> defaultInputModes = List.of("text", "image");
        List<String> defaultOutputModes = List.of("text", "audio");
        List<AgentSkill> skills =
                List.of(
                        AgentSkill.builder()
                                .id("skill1")
                                .name("Skill 1")
                                .description("Skill 1")
                                .tags(List.of())
                                .build());
        Map<String, SecurityScheme> securitySchemes =
                Map.of("basic", new MutualTLSSecurityScheme("basic"));
        List<Map<String, List<String>>> configSecurity = List.of(Map.of("basic", List.of("read")));
        List<SecurityRequirement> expectedSecurity =
                List.of(new SecurityRequirement(configSecurity.get(0)));
        String iconUrl = "https://example.com/icon.png";
        List<AgentInterface> additionalInterfaces =
                List.of(new AgentInterface("jsonrpc", "https://example.com/rpc"));
        String preferredTransport = "jsonrpc";

        when(configurableAgentCard.getName()).thenReturn(name);
        when(configurableAgentCard.getDescription()).thenReturn(description);
        when(configurableAgentCard.getUrl()).thenReturn(url);
        when(configurableAgentCard.getProvider()).thenReturn(provider);
        when(configurableAgentCard.getVersion()).thenReturn(version);
        when(configurableAgentCard.getDocumentationUrl()).thenReturn(documentationUrl);
        when(configurableAgentCard.getDefaultInputModes()).thenReturn(defaultInputModes);
        when(configurableAgentCard.getDefaultOutputModes()).thenReturn(defaultOutputModes);
        when(configurableAgentCard.getSkills()).thenReturn(skills);
        when(configurableAgentCard.getSecuritySchemes()).thenReturn(securitySchemes);
        when(configurableAgentCard.getSecurity()).thenReturn(configSecurity);
        when(configurableAgentCard.getIconUrl()).thenReturn(iconUrl);
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(additionalInterfaces);
        when(configurableAgentCard.getPreferredTransport()).thenReturn(preferredTransport);

        when(agentRunner.getAgentName()).thenReturn("Runner Agent Name");
        when(agentRunner.getAgentDescription()).thenReturn("Runner Agent Description");

        // When
        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, availableTransports);

        // Then
        assertNotNull(agentCard);
        assertEquals(name, agentCard.name());
        assertEquals(description, agentCard.description());
        assertEquals(url, agentCard.url());
        assertEquals(provider, agentCard.provider());
        assertEquals(version, agentCard.version());
        assertEquals(documentationUrl, agentCard.documentationUrl());
        assertEquals(defaultInputModes, agentCard.defaultInputModes());
        assertEquals(defaultOutputModes, agentCard.defaultOutputModes());
        assertEquals(skills, agentCard.skills());
        assertEquals(securitySchemes, agentCard.securitySchemes());
        assertEquals(expectedSecurity, agentCard.securityRequirements());
        assertEquals(iconUrl, agentCard.iconUrl());
        assertEquals(additionalInterfaces, agentCard.supportedInterfaces());
        assertEquals(preferredTransport, agentCard.preferredTransport());
        assertFalse(agentCard.capabilities().extendedAgentCard());
    }

    @Test
    @DisplayName("Should create agent card with fallback values from agent runner")
    void testCreateAgentCardWithFallbackValues() {
        // Given
        String runnerName = "Runner Agent Name";
        String runnerDescription = "Runner Agent Description";

        when(configurableAgentCard.getName()).thenReturn(null);
        when(configurableAgentCard.getDescription()).thenReturn(null);
        when(configurableAgentCard.getUrl()).thenReturn(null);
        when(configurableAgentCard.getVersion()).thenReturn(null);
        when(configurableAgentCard.getDefaultInputModes()).thenReturn(null);
        when(configurableAgentCard.getDefaultOutputModes()).thenReturn(null);
        when(configurableAgentCard.getSkills()).thenReturn(null);
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(null);
        when(configurableAgentCard.getPreferredTransport()).thenReturn(null);

        when(agentRunner.getAgentName()).thenReturn(runnerName);
        when(agentRunner.getAgentDescription()).thenReturn(runnerDescription);

        // When
        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, availableTransports);

        // Then
        assertNotNull(agentCard);
        assertEquals(runnerName, agentCard.name());
        assertEquals(runnerDescription, agentCard.description());
        assertNotNull(agentCard.url()); // Should be derived from transport properties
        assertEquals("1.0.0", agentCard.version());
        assertEquals(List.of("text"), agentCard.defaultInputModes());
        assertEquals(List.of("text"), agentCard.defaultOutputModes());
        assertEquals(List.of(), agentCard.skills()); // Empty list as default
        assertNotNull(agentCard.supportedInterfaces());
        assertFalse(agentCard.supportedInterfaces().isEmpty());
        assertEquals(TransportProtocol.JSONRPC.asString(), agentCard.preferredTransport());
    }

    @Test
    @DisplayName("Should create agent card with TLS enabled transport")
    void testCreateAgentCardWithTLSEnabledTransport() {
        // Given
        TransportProperties httpsTransport = mock(TransportProperties.class);
        when(httpsTransport.transportType()).thenReturn(TransportProtocol.JSONRPC.asString());
        when(httpsTransport.host()).thenReturn("example.com");
        when(httpsTransport.port()).thenReturn(443);
        when(httpsTransport.path()).thenReturn("/api");
        when(httpsTransport.supportTls()).thenReturn(true);

        Set<TransportProperties> tlsTransports = new HashSet<>();
        tlsTransports.add(httpsTransport);

        when(configurableAgentCard.getName()).thenReturn("Test Agent");
        when(configurableAgentCard.getDescription()).thenReturn("Test Description");
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(null);
        when(configurableAgentCard.getPreferredTransport()).thenReturn(null);
        when(configurableAgentCard.getUrl()).thenReturn(null);

        when(agentRunner.getAgentName()).thenReturn("Runner Agent Name");
        when(agentRunner.getAgentDescription()).thenReturn("Runner Agent Description");

        // When
        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, tlsTransports);

        // Then
        assertNotNull(agentCard);
        assertTrue(agentCard.url().startsWith("https://"));
        assertNotNull(agentCard.supportedInterfaces());
        assertFalse(agentCard.supportedInterfaces().isEmpty());
        AgentInterface interfaceFound = agentCard.supportedInterfaces().get(0);
        assertTrue(interfaceFound.url().startsWith("https://"));
    }

    @Test
    @DisplayName("Should create agent card with custom path")
    void testCreateAgentCardWithCustomPath() {
        // Given
        TransportProperties customPathTransport = mock(TransportProperties.class);
        when(customPathTransport.transportType()).thenReturn(TransportProtocol.JSONRPC.asString());
        when(customPathTransport.host()).thenReturn("localhost");
        when(customPathTransport.port()).thenReturn(8080);
        when(customPathTransport.path()).thenReturn("custom/path");
        when(customPathTransport.supportTls()).thenReturn(false);

        Set<TransportProperties> customPathTransports = new HashSet<>();
        customPathTransports.add(customPathTransport);

        when(configurableAgentCard.getName()).thenReturn("Test Agent");
        when(configurableAgentCard.getDescription()).thenReturn("Test Description");
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(null);
        when(configurableAgentCard.getPreferredTransport()).thenReturn(null);
        when(configurableAgentCard.getUrl()).thenReturn(null);

        when(agentRunner.getAgentName()).thenReturn("Runner Agent Name");
        when(agentRunner.getAgentDescription()).thenReturn("Runner Agent Description");

        // When
        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, customPathTransports);

        // Then
        assertNotNull(agentCard);
        assertTrue(agentCard.url().endsWith("/custom/path"));
        assertNotNull(agentCard.supportedInterfaces());
        assertFalse(agentCard.supportedInterfaces().isEmpty());
        AgentInterface interfaceFound = agentCard.supportedInterfaces().get(0);
        assertTrue(interfaceFound.url().endsWith("/custom/path"));
    }

    @Test
    @DisplayName("Should create agent card with explicit URL and preferred transport")
    void testCreateAgentCardWithExplicitUrlAndPreferredTransport() {
        // Given
        String explicitUrl = "https://explicit.example.com/api";
        String preferredTransport = "jsonrpc";

        when(configurableAgentCard.getName()).thenReturn("Test Agent");
        when(configurableAgentCard.getDescription()).thenReturn("Test Description");
        when(configurableAgentCard.getUrl()).thenReturn(explicitUrl);
        when(configurableAgentCard.getPreferredTransport()).thenReturn(preferredTransport);
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(null);

        when(agentRunner.getAgentName()).thenReturn("Runner Agent Name");
        when(agentRunner.getAgentDescription()).thenReturn("Runner Agent Description");

        // When
        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, availableTransports);

        // Then
        assertNotNull(agentCard);
        assertEquals(explicitUrl, agentCard.url());
        assertEquals(preferredTransport, agentCard.preferredTransport());
    }

    @Test
    @DisplayName("Should handle null available transports")
    void testCreateAgentCardWithNullAvailableTransports() {
        // Given
        when(configurableAgentCard.getName()).thenReturn("Test Agent");
        when(configurableAgentCard.getDescription()).thenReturn("Test Description");
        when(configurableAgentCard.getAdditionalInterfaces()).thenReturn(null);

        when(agentRunner.getAgentName()).thenReturn("Runner Agent Name");
        when(agentRunner.getAgentDescription()).thenReturn("Runner Agent Description");

        AgentCard agentCard =
                builder.createAgentCard(configurableAgentCard, agentRunner, new HashSet<>());

        assertNotNull(agentCard);
        assertNull(agentCard.url());
        assertEquals(TransportProtocol.JSONRPC.asString(), agentCard.preferredTransport());
        assertEquals(List.of(), agentCard.supportedInterfaces());
    }
}
