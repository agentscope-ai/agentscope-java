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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigurableAgentCard Tests")
class ConfigurableAgentCardTest {

    @Test
    @DisplayName("Should create ConfigurableAgentCard with all fields")
    void testCreateConfigurableAgentCardWithAllFields() {
        // Given
        String name = "Test Agent";
        String description = "Test Description";
        AgentProvider provider = new AgentProvider("Test Provider", "https://provider.com");
        String version = "1.0.0";
        String documentationUrl = "https://docs.example.com";
        List<String> defaultInputModes = List.of("text", "image");
        List<String> defaultOutputModes = List.of("text");
        List<AgentSkill> skills =
                List.of(
                        AgentSkill.builder()
                                .id("skill1")
                                .name("Skill 1")
                                .description("Skill 1")
                                .tags(List.of())
                                .build());
        List<SecurityRequirement> securityRequirements =
                List.of(new SecurityRequirement(Map.of("basic", List.of("read"))));
        String iconUrl = "https://example.com/icon.png";
        List<AgentInterface> supportedInterfaces =
                List.of(new AgentInterface("jsonrpc", "https://example.com/rpc", "/public", null));

        // When
        ConfigurableAgentCard card =
                new ConfigurableAgentCard.Builder()
                        .name(name)
                        .description(description)
                        .provider(provider)
                        .version(version)
                        .documentationUrl(documentationUrl)
                        .defaultInputModes(defaultInputModes)
                        .defaultOutputModes(defaultOutputModes)
                        .skills(skills)
                        .securityRequirements(securityRequirements)
                        .iconUrl(iconUrl)
                        .supportedInterfaces(supportedInterfaces)
                        .build();

        // Then
        assertNotNull(card);
        assertEquals(name, card.getName());
        assertEquals(description, card.getDescription());
        assertEquals(provider, card.getProvider());
        assertEquals(version, card.getVersion());
        assertEquals(documentationUrl, card.getDocumentationUrl());
        assertEquals(defaultInputModes, card.getDefaultInputModes());
        assertEquals(defaultOutputModes, card.getDefaultOutputModes());
        assertEquals(skills, card.getSkills());
        assertEquals(securityRequirements, card.getSecurityRequirements());
        assertEquals(iconUrl, card.getIconUrl());
        assertEquals(supportedInterfaces, card.getSupportedInterfaces());
    }

    @Test
    @DisplayName("Should create ConfigurableAgentCard with default values when fields are not set")
    void testCreateConfigurableAgentCardWithDefaultValues() {
        // When
        ConfigurableAgentCard card = new ConfigurableAgentCard.Builder().build();

        // Then
        assertNotNull(card);
        assertNull(card.getName());
        assertNull(card.getDescription());
        assertNull(card.getProvider());
        assertNull(card.getVersion());
        assertNull(card.getDocumentationUrl());
        assertNull(card.getDefaultInputModes());
        assertNull(card.getDefaultOutputModes());
        assertNull(card.getSkills());
        assertNull(card.getSecurityRequirements());
        assertNull(card.getIconUrl());
        assertNull(card.getSupportedInterfaces());
    }

    @Test
    @DisplayName("Should create ConfigurableAgentCard with only some fields set")
    void testCreateConfigurableAgentCardWithPartialFields() {
        // Given
        String name = "Test Agent";
        String description = "Test Description";

        // When
        ConfigurableAgentCard card =
                new ConfigurableAgentCard.Builder().name(name).description(description).build();

        // Then
        assertNotNull(card);
        assertEquals(name, card.getName());
        assertEquals(description, card.getDescription());
        assertNull(card.getProvider());
        assertNull(card.getVersion());
        assertNull(card.getDocumentationUrl());
        assertNull(card.getDefaultInputModes());
        assertNull(card.getDefaultOutputModes());
        assertNull(card.getSkills());
        assertNull(card.getSecurityRequirements());
        assertNull(card.getIconUrl());
        assertNull(card.getSupportedInterfaces());
    }
}
