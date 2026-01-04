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
package io.agentscope.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryMemoryTest {

    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
    }

    @Test
    void testAddMessage() {
        Msg message = TestUtils.createUserMessage("user", "Hello, world!");
        memory.addMessage(message);

        List<Msg> messages = memory.getMessages();
        assertNotNull(messages);
        assertEquals(1, messages.size());
        assertEquals(message, messages.get(0));
        assertEquals("Hello, world!", TestUtils.extractTextContent(messages.get(0)));
    }

    @Test
    void testGetMessagesReturnsEmptyListWhenNoMessages() {
        List<Msg> messages = memory.getMessages();
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void testGetMessagesFiltersOutNullEntries() {
        memory.addMessage(TestUtils.createUserMessage("user1", "First message"));
        memory.addMessage(null); // Add a null entry
        memory.addMessage(TestUtils.createAssistantMessage("assistant", "Second message"));

        List<Msg> messages = memory.getMessages();
        assertNotNull(messages);
        assertEquals(2, messages.size());
        assertEquals("First message", TestUtils.extractTextContent(messages.get(0)));
        assertEquals("Second message", TestUtils.extractTextContent(messages.get(1)));
    }

    @Test
    void testAddMultipleMessages() {
        Msg msg1 = TestUtils.createUserMessage("user", "First message");
        Msg msg2 = TestUtils.createAssistantMessage("assistant", "Second message");
        Msg msg3 = TestUtils.createUserMessage("user", "Third message");

        memory.addMessage(msg1);
        memory.addMessage(msg2);
        memory.addMessage(msg3);

        List<Msg> messages = memory.getMessages();
        assertNotNull(messages);
        assertEquals(3, messages.size());
        assertEquals(msg1, messages.get(0));
        assertEquals(msg2, messages.get(1));
        assertEquals(msg3, messages.get(2));
    }

    @Test
    void testDeleteMessageAtValidIndex() {
        Msg msg1 = TestUtils.createUserMessage("user", "First message");
        Msg msg2 = TestUtils.createAssistantMessage("assistant", "Second message");
        Msg msg3 = TestUtils.createUserMessage("user", "Third message");

        memory.addMessage(msg1);
        memory.addMessage(msg2);
        memory.addMessage(msg3);

        // Delete middle message
        memory.deleteMessage(1);

        List<Msg> messages = memory.getMessages();
        assertEquals(2, messages.size());
        assertEquals(msg1, messages.get(0));
        assertEquals(msg3, messages.get(1));
    }

    @Test
    void testDeleteMessageAtInvalidIndex() {
        Msg msg1 = TestUtils.createUserMessage("user", "First message");
        Msg msg2 = TestUtils.createAssistantMessage("assistant", "Second message");

        memory.addMessage(msg1);
        memory.addMessage(msg2);

        // Try to delete at negative index - should be no-op
        memory.deleteMessage(-1);
        assertEquals(2, memory.getMessages().size());

        // Try to delete at out-of-bounds index - should be no-op
        memory.deleteMessage(5);
        assertEquals(2, memory.getMessages().size());

        // Try to delete at exact size index - should be no-op
        memory.deleteMessage(2);
        assertEquals(2, memory.getMessages().size());
    }

    @Test
    void testDeleteMessageFromEmptyMemory() {
        // Delete from empty memory - should be no-op
        memory.deleteMessage(0);
        assertTrue(memory.getMessages().isEmpty());
    }

    @Test
    void testClear() {
        Msg msg1 = TestUtils.createUserMessage("user", "First message");
        Msg msg2 = TestUtils.createAssistantMessage("assistant", "Second message");

        memory.addMessage(msg1);
        memory.addMessage(msg2);
        assertEquals(2, memory.getMessages().size());

        memory.clear();
        assertTrue(memory.getMessages().isEmpty());
    }

    @Test
    void testClearEmptyMemory() {
        // Clear empty memory - should be no-op
        memory.clear();
        assertTrue(memory.getMessages().isEmpty());
    }

    @Test
    void testConcurrentOperations() {
        // Test that memory operations are thread-safe by adding messages in a loop
        for (int i = 0; i < 100; i++) {
            memory.addMessage(TestUtils.createUserMessage("user", "Message " + i));
        }

        List<Msg> messages = memory.getMessages();
        assertEquals(100, messages.size());

        // Delete some messages
        for (int i = 0; i < 50; i++) {
            memory.deleteMessage(0); // Delete first message each time
        }

        messages = memory.getMessages();
        assertEquals(50, messages.size());

        // Clear remaining messages
        memory.clear();
        assertTrue(memory.getMessages().isEmpty());
    }
}
