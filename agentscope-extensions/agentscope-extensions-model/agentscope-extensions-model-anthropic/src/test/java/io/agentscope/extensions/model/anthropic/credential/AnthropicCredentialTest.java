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
package io.agentscope.extensions.model.anthropic.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import org.junit.jupiter.api.Test;

class AnthropicCredentialTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anthropicCredentialMayUseNullBaseUrl() {
        AnthropicCredential c = AnthropicCredential.builder().apiKey("anthropic-key").build();
        assertEquals("anthropic-key", c.getApiKey());
        assertNull(c.getBaseUrl());
        assertEquals(AnthropicChatModel.class, c.getChatModelClass());
        assertEquals("anthropic_credential", c.getType());
    }

    @Test
    void anthropicCredentialKeepsExplicitBaseUrl() {
        AnthropicCredential c =
                AnthropicCredential.builder()
                        .apiKey("anthropic-key")
                        .baseUrl("https://custom.anthropic/api")
                        .build();
        assertEquals("https://custom.anthropic/api", c.getBaseUrl());
    }

    @Test
    void anthropicCredentialRequiresApiKey() {
        assertThrows(NullPointerException.class, () -> AnthropicCredential.builder().build());
    }

    @Test
    void toStringMasksApiKey() {
        AnthropicCredential c = AnthropicCredential.builder().apiKey("anthropic-secret").build();
        String s = c.toString();
        assertTrue(s.contains("apiKey=***"));
        assertFalse(s.contains("anthropic-secret"));
    }

    @Test
    void explicitIdIsPreservedThroughBuilderAndJson() throws Exception {
        AnthropicCredential c = AnthropicCredential.builder().id("custom-id-1").apiKey("k").build();
        assertEquals("custom-id-1", c.getId());
        String json = mapper.writeValueAsString(c);
        AnthropicCredential round = mapper.readValue(json, AnthropicCredential.class);
        assertEquals("custom-id-1", round.getId());
    }
}
