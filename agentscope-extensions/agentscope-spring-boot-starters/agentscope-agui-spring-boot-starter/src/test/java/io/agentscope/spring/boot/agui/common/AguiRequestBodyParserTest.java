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
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.util.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AguiRequestBodyParser}.
 *
 * <p>These cases cover the Spring Boot 4 / Jackson 3 compatibility path: raw JSON is
 * decoded with AgentScope Jackson 2 so {@code MessageContent} custom deserializers work.
 */
class AguiRequestBodyParserTest {

    @Test
    @DisplayName("Should parse RunAgentInput with string message content")
    void testParseStringMessageContent() {
        String body =
                """
                {
                  "threadId": "thread-1",
                  "runId": "run-1",
                  "messages": [
                    {
                      "id": "msg-1",
                      "role": "user",
                      "content": "hello from jackson2 parser"
                    }
                  ],
                  "tools": [],
                  "context": [],
                  "state": {},
                  "forwardedProps": {}
                }
                """;

        RunAgentInput input = AguiRequestBodyParser.parseRunAgentInput(body);

        assertEquals("thread-1", input.getThreadId());
        assertEquals("run-1", input.getRunId());
        assertEquals(1, input.getMessages().size());

        AguiMessage message = input.getMessages().get(0);
        assertEquals("msg-1", message.getId());
        assertEquals("user", message.getRole());
        assertEquals("hello from jackson2 parser", message.getTextContent());
        assertFalse(message.hasBlocks());
        assertInstanceOf(MessageContent.Text.class, message.getContent());
    }

    @Test
    @DisplayName("Should parse RunAgentInput with multimodal MessageContent blocks")
    void testParseBlocksMessageContent() {
        String body =
                """
                {
                  "threadId": "thread-2",
                  "runId": "run-2",
                  "messages": [
                    {
                      "id": "msg-2",
                      "role": "user",
                      "content": [
                        {
                          "type": "text",
                          "text": "describe this"
                        }
                      ]
                    }
                  ],
                  "tools": [],
                  "context": [],
                  "state": {},
                  "forwardedProps": {}
                }
                """;

        RunAgentInput input = AguiRequestBodyParser.parseRunAgentInput(body);

        assertEquals("thread-2", input.getThreadId());
        AguiMessage message = input.getMessages().get(0);
        assertTrue(message.hasBlocks());
        assertNull(message.getTextContent());
        assertInstanceOf(MessageContent.Blocks.class, message.getContent());

        MessageContent.Blocks blocks = (MessageContent.Blocks) message.getContent();
        assertEquals(1, blocks.parts().size());
        assertInstanceOf(TextInputContent.class, blocks.parts().get(0));
        assertEquals("describe this", ((TextInputContent) blocks.parts().get(0)).text());
    }

    @Test
    @DisplayName("Should reject invalid JSON bodies")
    void testParseInvalidJson() {
        assertThrows(
                JsonException.class,
                () -> AguiRequestBodyParser.parseRunAgentInput("{not-valid-json"));
    }
}
