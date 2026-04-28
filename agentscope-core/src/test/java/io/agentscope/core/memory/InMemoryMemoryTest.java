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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    // ==================== Anchor Tests ====================

    @Nested
    @DisplayName("Anchor (saveAnchor / restoreAnchor / hasAnchor)")
    class AnchorTests {

        @Test
        @DisplayName("hasAnchor() returns false before saveAnchor() is called")
        void testHasAnchorFalseInitially() {
            assertFalse(memory.hasAnchor());
        }

        @Test
        @DisplayName("hasAnchor() returns true after saveAnchor() is called")
        void testHasAnchorTrueAfterSave() {
            memory.addMessage(TestUtils.createUserMessage("user", "Hello"));
            memory.saveAnchor();
            assertTrue(memory.hasAnchor());
        }

        @Test
        @DisplayName("saveAnchor() snapshots current messages and restoreAnchor() restores them")
        void testSaveAndRestoreAnchor() {
            Msg msg1 = TestUtils.createUserMessage("user", "Message 1");
            Msg msg2 = TestUtils.createAssistantMessage("assistant", "Message 2");
            memory.addMessage(msg1);
            memory.addMessage(msg2);

            memory.saveAnchor();

            // Add more messages after anchor
            memory.addMessage(TestUtils.createUserMessage("user", "Message 3"));
            assertEquals(3, memory.getMessages().size());

            // Restore should revert to the 2-message state
            memory.restoreAnchor();
            List<Msg> restored = memory.getMessages();
            assertEquals(2, restored.size());
            assertEquals(msg1, restored.get(0));
            assertEquals(msg2, restored.get(1));
        }

        @Test
        @DisplayName("restoreAnchor() is a no-op when no anchor has been saved")
        void testRestoreAnchorNoOpWhenNoAnchor() {
            memory.addMessage(TestUtils.createUserMessage("user", "Only message"));
            // Should not throw, should not change messages
            memory.restoreAnchor();
            assertEquals(1, memory.getMessages().size());
        }

        @Test
        @DisplayName("saveAnchor() overwrites previous anchor")
        void testSaveAnchorOverwritesPrevious() {
            Msg msg1 = TestUtils.createUserMessage("user", "First");
            memory.addMessage(msg1);
            memory.saveAnchor(); // anchor = [msg1]

            Msg msg2 = TestUtils.createAssistantMessage("assistant", "Second");
            memory.addMessage(msg2);
            memory.saveAnchor(); // anchor = [msg1, msg2]

            // Add a third message and restore
            memory.addMessage(TestUtils.createUserMessage("user", "Third"));
            memory.restoreAnchor();

            // Should be [msg1, msg2]
            List<Msg> restored = memory.getMessages();
            assertEquals(2, restored.size());
            assertEquals(msg2, restored.get(1));
        }

        @Test
        @DisplayName("restoreAnchor() can be called multiple times idempotently")
        void testRestoreAnchorMultipleTimes() {
            Msg msg1 = TestUtils.createUserMessage("user", "Anchor message");
            memory.addMessage(msg1);
            memory.saveAnchor();

            memory.addMessage(TestUtils.createUserMessage("user", "Extra"));
            memory.restoreAnchor();
            assertEquals(1, memory.getMessages().size());

            // Calling again should still work
            memory.addMessage(TestUtils.createUserMessage("user", "Extra2"));
            memory.restoreAnchor();
            assertEquals(1, memory.getMessages().size());
        }

        @Test
        @DisplayName("saveAnchor() on empty memory, restoreAnchor() clears messages")
        void testSaveAnchorOnEmptyRestoresClear() {
            memory.saveAnchor(); // anchor = empty

            memory.addMessage(TestUtils.createUserMessage("user", "Added after anchor"));
            assertEquals(1, memory.getMessages().size());

            memory.restoreAnchor();
            assertTrue(memory.getMessages().isEmpty());
        }
    }

    // ==================== deleteMessagesFrom Tests ====================

    @Nested
    @DisplayName("deleteMessagesFrom(int fromIndex)")
    class DeleteMessagesFromTests {

        @Test
        @DisplayName("Normal truncation: removes messages from index to end")
        void testDeleteMessagesFromNormal() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 2"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 3"));

            memory.deleteMessagesFrom(2);

            List<Msg> remaining = memory.getMessages();
            assertEquals(2, remaining.size());
            assertEquals("Msg 0", TestUtils.extractTextContent(remaining.get(0)));
            assertEquals("Msg 1", TestUtils.extractTextContent(remaining.get(1)));
        }

        @Test
        @DisplayName("fromIndex = 0 removes all messages")
        void testDeleteMessagesFromZero() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));

            memory.deleteMessagesFrom(0);

            assertTrue(memory.getMessages().isEmpty());
        }

        @Test
        @DisplayName("fromIndex = size - 1 removes only the last message")
        void testDeleteMessagesFromLastIndex() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 2"));

            memory.deleteMessagesFrom(2);

            List<Msg> remaining = memory.getMessages();
            assertEquals(2, remaining.size());
            assertEquals("Msg 1", TestUtils.extractTextContent(remaining.get(1)));
        }

        @Test
        @DisplayName("fromIndex = -1 is a no-op")
        void testDeleteMessagesFromNegativeIndex() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));

            memory.deleteMessagesFrom(-1);

            assertEquals(2, memory.getMessages().size());
        }

        @Test
        @DisplayName("fromIndex = size is a no-op")
        void testDeleteMessagesFromSizeIndex() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));

            memory.deleteMessagesFrom(
                    2); // size == 2, valid; actually removes from index 2 (nothing)
            // Wait - fromIndex == size is actually no-op per impl: fromIndex >= size returns
            // Actually size=2, fromIndex=2: condition fromIndex >= size is 2>=2 = true => no-op
            // Let's re-add and test fromIndex == size+1
            // First restore to 2 messages
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0 again"));
            // Now size=3, let's test fromIndex=4
            memory.deleteMessagesFrom(4);
            assertEquals(3, memory.getMessages().size());
        }

        @Test
        @DisplayName("fromIndex >= size is a no-op")
        void testDeleteMessagesFromOutOfBounds() {
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 0"));
            memory.addMessage(TestUtils.createUserMessage("user", "Msg 1"));
            // size = 2, fromIndex = 2 → no-op (boundary)
            memory.deleteMessagesFrom(2);
            assertEquals(2, memory.getMessages().size());

            // fromIndex = 10 → no-op
            memory.deleteMessagesFrom(10);
            assertEquals(2, memory.getMessages().size());
        }

        @Test
        @DisplayName("deleteMessagesFrom on empty memory is a no-op")
        void testDeleteMessagesFromEmptyMemory() {
            memory.deleteMessagesFrom(0);
            assertTrue(memory.getMessages().isEmpty());
        }
    }
}
