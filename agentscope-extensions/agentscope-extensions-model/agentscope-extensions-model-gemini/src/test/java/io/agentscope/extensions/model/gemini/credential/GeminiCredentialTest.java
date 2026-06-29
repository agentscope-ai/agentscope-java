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
package io.agentscope.extensions.model.gemini.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.extensions.model.gemini.GeminiChatModel;
import org.junit.jupiter.api.Test;

class GeminiCredentialTest {

    @Test
    void geminiCredentialOnlyApiKey() {
        GeminiCredential c = GeminiCredential.builder().apiKey("g-key").build();
        assertEquals("g-key", c.getApiKey());
        assertEquals(GeminiChatModel.class, c.getChatModelClass());
        assertEquals("gemini_credential", c.getType());
    }

    @Test
    void geminiCredentialRequiresApiKey() {
        assertThrows(NullPointerException.class, () -> GeminiCredential.builder().build());
    }
}
