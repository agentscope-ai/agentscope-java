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
package io.agentscope.core.a2a.agent.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.a2a.A2A;
import io.a2a.spec.AgentCard;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for WellKnownAgentCardResolver.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Builder pattern functionality</li>
 *   <li>Configuration settings</li>
 *   <li>Builder method chaining</li>
 *   <li>Default values</li>
 * </ul>
 */
@DisplayName("WellKnownAgentCardResolver Tests")
class WellKnownAgentCardResolverTest {

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        WellKnownAgentCardResolver.Builder builder = WellKnownAgentCardResolver.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set base URL")
    void testSetBaseUrl() {
        WellKnownAgentCardResolver.Builder builder = WellKnownAgentCardResolver.builder();
        String baseUrl = "http://example.com";

        WellKnownAgentCardResolver.Builder result = builder.baseUrl(baseUrl);

        assertSame(builder, result); // Check method chaining
    }

    @Test
    @DisplayName("Should set relative card path")
    void testSetRelativeCardPath() {
        WellKnownAgentCardResolver.Builder builder = WellKnownAgentCardResolver.builder();
        String relativeCardPath = "/custom/path/agent-card.json";

        WellKnownAgentCardResolver.Builder result = builder.relativeCardPath(relativeCardPath);

        assertSame(builder, result); // Check method chaining
    }

    @Test
    @DisplayName("Should set authentication headers")
    void testSetAuthHeaders() {
        WellKnownAgentCardResolver.Builder builder = WellKnownAgentCardResolver.builder();
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("Authorization", "Bearer token");
        authHeaders.put("Custom-Header", "custom-value");

        WellKnownAgentCardResolver.Builder result = builder.authHeaders(authHeaders);

        assertSame(builder, result); // Check method chaining
    }

    @Test
    @DisplayName("Should build resolver with default values")
    void testBuildResolverWithDefaults() {
        WellKnownAgentCardResolver resolver =
                WellKnownAgentCardResolver.builder().baseUrl("http://example.com").build();

        assertNotNull(resolver);
    }

    @Test
    @DisplayName("Should have correct default values")
    void testDefaultValues() throws Exception {
        WellKnownAgentCardResolver resolver =
                WellKnownAgentCardResolver.builder().baseUrl("http://example.com").build();

        // Using reflection to access private fields
        String relativeCardPath = getFieldValue(resolver, "relativeCardPath", String.class);
        Map<String, String> authHeaders = getFieldValue(resolver, "authHeaders", Map.class);

        assertEquals("/.well-known/agent-card.json", relativeCardPath);
        assertNotNull(authHeaders);
        assertEquals(0, authHeaders.size());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() {
        WellKnownAgentCardResolver.Builder builder = WellKnownAgentCardResolver.builder();
        String baseUrl = "http://example.com";
        String relativeCardPath = "/custom/path/agent-card.json";
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("Authorization", "Bearer token");

        WellKnownAgentCardResolver.Builder result =
                builder.baseUrl(baseUrl)
                        .relativeCardPath(relativeCardPath)
                        .authHeaders(authHeaders);

        assertNotNull(result);
        assertSame(builder, result);

        WellKnownAgentCardResolver resolver = builder.build();
        assertNotNull(resolver);
    }

    @Test
    @DisplayName("Should build multiple independent resolver instances")
    void testMultipleResolverInstances() {
        String baseUrl1 = "http://example1.com";
        String baseUrl2 = "http://example2.com";

        WellKnownAgentCardResolver resolver1 =
                WellKnownAgentCardResolver.builder().baseUrl(baseUrl1).build();

        WellKnownAgentCardResolver resolver2 =
                WellKnownAgentCardResolver.builder().baseUrl(baseUrl2).build();

        assertNotNull(resolver1);
        assertNotNull(resolver2);
    }

    @Test
    @DisplayName("Should store all configuration values correctly")
    void testConfigurationValuesStoredCorrectly() throws Exception {
        String baseUrl = "http://example.com";
        String relativeCardPath = "/custom/path/agent-card.json";
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("Authorization", "Bearer token");
        authHeaders.put("Custom-Header", "custom-value");

        WellKnownAgentCardResolver resolver =
                WellKnownAgentCardResolver.builder()
                        .baseUrl(baseUrl)
                        .relativeCardPath(relativeCardPath)
                        .authHeaders(authHeaders)
                        .build();

        // Using reflection to access private fields
        String actualBaseUrl = getFieldValue(resolver, "baseUrl", String.class);
        String actualRelativeCardPath = getFieldValue(resolver, "relativeCardPath", String.class);
        Map<String, String> actualAuthHeaders = getFieldValue(resolver, "authHeaders", Map.class);

        assertEquals(baseUrl, actualBaseUrl);
        assertEquals(relativeCardPath, actualRelativeCardPath);
        assertEquals(authHeaders, actualAuthHeaders);
    }

    @Test
    @DisplayName("Should get AgentCard from Remote server")
    void testGetAgentCardFromRemoteServer() throws Exception {
        try (MockedStatic<A2A> mockedA2A = Mockito.mockStatic(A2A.class)) {
            AgentCard mockAgentCard = mock(AgentCard.class);
            mockedA2A
                    .when(
                            () ->
                                    A2A.getAgentCard(
                                            "http://example.com",
                                            "/.well-known/agent-card.json",
                                            Map.of()))
                    .thenReturn(mockAgentCard);
            WellKnownAgentCardResolver resolver =
                    WellKnownAgentCardResolver.builder().baseUrl("http://example.com").build();
            AgentCard actual = resolver.getAgentCard("agentName");
            assertSame(mockAgentCard, actual);
        }
    }

    private <T> T getFieldValue(Object obj, String fieldName, Class<T> fieldType) throws Exception {
        Class<?> clazz = obj.getClass();
        java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(obj));
    }
}
