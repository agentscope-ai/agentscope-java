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
package io.agentscope.core.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OllamaChatModel;
import org.junit.jupiter.api.Test;

class CredentialsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dashScopeCredentialAppliesDefaultBaseUrlWhenNull() {
        DashScopeCredential c = DashScopeCredential.builder().apiKey("ds-key").build();
        assertEquals(DashScopeCredential.DEFAULT_BASE_URL, c.getBaseUrl());
        assertEquals(DashScopeChatModel.class, c.getChatModelClass());
    }

    @Test
    void dashScopeCredentialKeepsExplicitBaseUrl() {
        DashScopeCredential c =
                DashScopeCredential.builder()
                        .apiKey("ds-key")
                        .baseUrl("https://custom.dash/api")
                        .build();
        assertEquals("https://custom.dash/api", c.getBaseUrl());
    }

    @Test
    void ollamaCredentialHostMayBeNull() {
        OllamaCredential c = OllamaCredential.builder().build();
        assertNull(c.getHost());
        assertEquals(OllamaChatModel.class, c.getChatModelClass());
    }

    @Test
    void ollamaCredentialKeepsHost() {
        OllamaCredential c = OllamaCredential.builder().host("http://ollama.local:11434").build();
        assertEquals("http://ollama.local:11434", c.getHost());
    }

    @Test
    void deepSeekCredentialThrowsOnGetChatModelClass() {
        DeepSeekCredential c = DeepSeekCredential.builder().apiKey("ds-key").build();
        assertEquals(DeepSeekCredential.DEFAULT_BASE_URL, c.getBaseUrl());
        assertThrows(UnsupportedOperationException.class, c::getChatModelClass);
    }

    @Test
    void kimiCredentialThrowsOnGetChatModelClass() {
        KimiCredential c = KimiCredential.builder().apiKey("k-key").build();
        assertEquals(KimiCredential.DEFAULT_BASE_URL, c.getBaseUrl());
        assertThrows(UnsupportedOperationException.class, c::getChatModelClass);
    }

    @Test
    void xaiCredentialThrowsOnGetChatModelClass() {
        XAICredential c = XAICredential.builder().apiKey("x-key").build();
        assertEquals("x-key", c.getApiKey());
        assertThrows(UnsupportedOperationException.class, c::getChatModelClass);
    }

    @Test
    void allCredentialsRequireNonNullApiKey() {
        assertThrows(NullPointerException.class, () -> DashScopeCredential.builder().build());
        assertThrows(NullPointerException.class, () -> DeepSeekCredential.builder().build());
        assertThrows(NullPointerException.class, () -> KimiCredential.builder().build());
        assertThrows(NullPointerException.class, () -> XAICredential.builder().build());
    }

    @Test
    void toStringMasksApiKey() {
        DashScopeCredential c = DashScopeCredential.builder().apiKey("dash-key-secret").build();
        String s = c.toString();
        assertTrue(s.contains("apiKey=***"));
        assertFalse(s.contains("dash-key-secret"));
    }

    @Test
    void explicitIdIsPreservedThroughBuilderAndJson() throws Exception {
        DashScopeCredential c = DashScopeCredential.builder().id("custom-id-1").apiKey("k").build();
        assertEquals("custom-id-1", c.getId());
        String json = mapper.writeValueAsString(c);
        DashScopeCredential round = mapper.readValue(json, DashScopeCredential.class);
        assertEquals("custom-id-1", round.getId());
    }
}
