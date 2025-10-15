/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ReplyListArgs.
 */
@DisplayName("ReplyListArgs Tests")
class ReplyListArgsTest {

    @Test
    @DisplayName("Should create ReplyListArgs with messages")
    void testConstructorWithMessages() {
        Msg msg1 =
                Msg.builder()
                        .name("User")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("Assistant")
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi").build())
                        .build();

        List<Msg> inputs = List.of(msg1, msg2);
        ReplyListArgs args = new ReplyListArgs(inputs);

        assertNotNull(args);
        assertNotNull(args.getInputs());
        assertEquals(2, args.getInputs().size());
        assertEquals(inputs, args.getInputs());
    }

    @Test
    @DisplayName("Should create ReplyListArgs with empty list")
    void testConstructorWithEmptyList() {
        List<Msg> inputs = new ArrayList<>();
        ReplyListArgs args = new ReplyListArgs(inputs);

        assertNotNull(args);
        assertNotNull(args.getInputs());
        assertTrue(args.getInputs().isEmpty());
    }

    @Test
    @DisplayName("Should get inputs correctly")
    void testGetInputs() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(TextBlock.builder().text("Test").build())
                        .build();

        List<Msg> inputs = List.of(msg);
        ReplyListArgs args = new ReplyListArgs(inputs);

        List<Msg> retrieved = args.getInputs();
        assertEquals(1, retrieved.size());
        assertEquals(msg, retrieved.get(0));
    }
}
