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
package io.agentscope.core.llm.interfacesweb.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResponsesRequest Tests")
class ResponsesRequestTest {

    @Test
    @DisplayName("Should expose all request fields")
    void shouldExposeAllRequestFields() {
        ResponsesRequest request = new ResponsesRequest();
        ResponsesTool tool = new ResponsesTool();
        Object input = Map.of("role", "user");
        Object metadata = Map.of("trace", "1");
        Object conversation = Map.of("id", "conv_1");
        Object toolChoice = Map.of("type", "auto");
        Map<String, Object> text = Map.of("format", Map.of("type", "text"));

        request.setModel("model");
        request.setInput(input);
        request.setInstructions("Be brief");
        request.setStream(true);
        request.setTools(List.of(tool));
        request.setMetadata(metadata);
        request.setPreviousResponseId("resp_1");
        request.setConversation(conversation);
        request.setBackground(false);
        request.setStore(true);
        request.setToolChoice(toolChoice);
        request.setText(text);

        assertEquals("model", request.getModel());
        assertSame(input, request.getInput());
        assertEquals("Be brief", request.getInstructions());
        assertTrue(request.getStream());
        assertEquals(List.of(tool), request.getTools());
        assertSame(metadata, request.getMetadata());
        assertEquals("resp_1", request.getPreviousResponseId());
        assertSame(conversation, request.getConversation());
        assertFalse(request.getBackground());
        assertTrue(request.getStore());
        assertSame(toolChoice, request.getToolChoice());
        assertEquals(text, request.getText());
    }
}
