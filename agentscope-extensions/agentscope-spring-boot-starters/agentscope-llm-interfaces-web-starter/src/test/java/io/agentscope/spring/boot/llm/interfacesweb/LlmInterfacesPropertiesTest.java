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
package io.agentscope.spring.boot.llm.interfacesweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LlmInterfacesProperties Tests")
class LlmInterfacesPropertiesTest {

    @Test
    @DisplayName("Should expose expected defaults")
    void shouldExposeDefaults() {
        LlmInterfacesProperties properties = new LlmInterfacesProperties();

        assertTrue(properties.isEnabled());
        assertEquals("/v1", properties.getBasePath());
        assertTrue(properties.isIgnoreUnknownFields());
        assertTrue(properties.isIgnoreInvalidThinking());
        assertTrue(properties.getChat().isEnabled());
        assertTrue(properties.getResponses().isEnabled());
        assertTrue(properties.getAnthropic().isEnabled());
    }

    @Test
    @DisplayName("Should expose mutable endpoint configuration")
    void shouldExposeMutableEndpointConfiguration() {
        LlmInterfacesProperties properties = new LlmInterfacesProperties();
        LlmInterfacesProperties.Endpoint chat = new LlmInterfacesProperties.Endpoint();
        LlmInterfacesProperties.Endpoint responses = new LlmInterfacesProperties.Endpoint(false);
        LlmInterfacesProperties.Endpoint anthropic = new LlmInterfacesProperties.Endpoint(true);

        properties.setEnabled(false);
        properties.setBasePath("/api");
        properties.setIgnoreUnknownFields(false);
        properties.setIgnoreInvalidThinking(false);
        chat.setEnabled(false);
        anthropic.setEnabled(false);
        properties.setChat(chat);
        properties.setResponses(responses);
        properties.setAnthropic(anthropic);

        assertFalse(properties.isEnabled());
        assertEquals("/api", properties.getBasePath());
        assertFalse(properties.isIgnoreUnknownFields());
        assertFalse(properties.isIgnoreInvalidThinking());
        assertFalse(properties.getChat().isEnabled());
        assertFalse(properties.getResponses().isEnabled());
        assertFalse(properties.getAnthropic().isEnabled());
    }
}
