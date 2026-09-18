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
package io.agentscope.core.agui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import org.junit.jupiter.api.Test;

class AguiRequestBodyParserTest {

    private final AguiRequestBodyParser parser = new AguiRequestBodyParser();

    @Test
    void shouldParseTextContentWithAgentScopeCodec() {
        String body =
                """
                {
                  "threadId": "thread-1",
                  "runId": "run-1",
                  "messages": [
                    {"id": "message-1", "role": "user", "content": "Hello!"}
                  ]
                }
                """;

        RunAgentInput input = parser.parse(body);

        assertEquals("Hello!", input.getMessages().get(0).getTextContent());
    }

    @Test
    void shouldParseMultimodalContentWithAgentScopeCodec() {
        String body =
                """
                {
                  "threadId": "thread-1",
                  "runId": "run-1",
                  "messages": [
                    {
                      "id": "message-1",
                      "role": "user",
                      "content": [
                        {"type": "text", "text": "Describe this"},
                        {
                          "type": "image",
                          "source": {
                            "type": "url",
                            "value": "https://example.com/image.png"
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        RunAgentInput input = parser.parse(body);
        MessageContent.Blocks content =
                assertInstanceOf(
                        MessageContent.Blocks.class, input.getMessages().get(0).getContent());

        assertEquals(2, content.parts().size());
        assertTrue(input.getMessages().get(0).hasBlocks());
    }

    @Test
    void shouldRejectNullBody() {
        assertThrows(NullPointerException.class, () -> parser.parse(null));
    }
}
