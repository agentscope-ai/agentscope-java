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
package io.agentscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

public class SimpleIntegrationTest {

    @Test
    public void testMemoryOperations() {
        InMemoryMemory memory = new InMemoryMemory();

        Msg msg1 =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("First message").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Second message").build())
                        .build();

        // Test adding messages
        memory.addMessage(msg1);
        memory.addMessage(msg2);

        assertEquals(2, memory.getMessages().size());

        // Test clearing memory
        memory.clear();
        assertEquals(0, memory.getMessages().size());
    }
}
