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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageUtilsServerToolTest {

    @Test
    void shouldReturnOnlyInlineServerToolResultIds() {
        ToolResultBlock serverResult =
                ToolResultBlock.builder()
                        .id("srvtoolu_01")
                        .name("web_search")
                        .output(TextBlock.builder().text("Result").build())
                        .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                        .build();
        ToolResultBlock localResult =
                ToolResultBlock.builder()
                        .id("call_local")
                        .name("weather")
                        .output(TextBlock.builder().text("Sunny").build())
                        .build();
        Msg message =
                AssistantMessage.builder()
                        .name("assistant")
                        .content(List.of(serverResult, localResult))
                        .build();

        Set<String> resultIds = MessageUtils.inlineServerToolResultIds(message);

        assertEquals(Set.of("srvtoolu_01"), resultIds);
    }

    @Test
    void shouldReturnEmptySetForNullMessage() {
        assertEquals(Set.of(), MessageUtils.inlineServerToolResultIds(null));
    }
}
