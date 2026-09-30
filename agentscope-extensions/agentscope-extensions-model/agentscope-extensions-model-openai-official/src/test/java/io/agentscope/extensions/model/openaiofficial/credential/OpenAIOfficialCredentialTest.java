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
package io.agentscope.extensions.model.openaiofficial.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import org.junit.jupiter.api.Test;

class OpenAIOfficialCredentialTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiOfficialCredentialJsonRoundTripWithOrganization() throws Exception {
        OpenAIOfficialCredential c =
                OpenAIOfficialCredential.builder()
                        .apiKey("sk-test")
                        .organization("org-abc")
                        .baseUrl("https://api.openai.com/v1")
                        .build();
        String json = mapper.writeValueAsString(c);
        assertTrue(json.contains("\"type\":\"openai_official_credential\""));
        assertTrue(json.contains("\"organization\":\"org-abc\""));

        OpenAIOfficialCredential round = mapper.readValue(json, OpenAIOfficialCredential.class);
        assertEquals("sk-test", round.getApiKey());
        assertEquals("org-abc", round.getOrganization());
        assertEquals("https://api.openai.com/v1", round.getBaseUrl());
        assertEquals(OpenAIResponsesChatModel.class, round.getChatModelClass());
    }

    @Test
    void openAiOfficialCredentialRequiresNonNullApiKey() {
        assertThrows(NullPointerException.class, () -> OpenAIOfficialCredential.builder().build());
    }

    @Test
    void autoIdIsRoundTrippedNotRegenerated() throws Exception {
        OpenAIOfficialCredential c = OpenAIOfficialCredential.builder().apiKey("k").build();
        String originalId = c.getId();
        assertNotNull(originalId);
        String json = mapper.writeValueAsString(c);
        OpenAIOfficialCredential round = mapper.readValue(json, OpenAIOfficialCredential.class);
        assertEquals(originalId, round.getId());
    }
}
