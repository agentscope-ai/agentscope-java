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
package io.agentscope.core.memory.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BailianMemoryNode}. */
class BailianMemoryNodeTest {

    @Test
    void testDefaultConstructor() {
        BailianMemoryNode node = new BailianMemoryNode();

        assertNull(node.getMemoryNodeId());
        assertNull(node.getContent());
        assertNull(node.getEvent());
        assertNull(node.getOldContent());
        assertNull(node.getCreatedAt());
        assertNull(node.getUpdatedAt());
    }

    @Test
    void testSettersAndGetters() {
        BailianMemoryNode node = new BailianMemoryNode();

        node.setMemoryNodeId("mem_123");
        assertEquals("mem_123", node.getMemoryNodeId());

        node.setContent("Memory content");
        assertEquals("Memory content", node.getContent());

        node.setEvent("ADD");
        assertEquals("ADD", node.getEvent());

        node.setOldContent("Old content");
        assertEquals("Old content", node.getOldContent());

        node.setCreatedAt(1234567890L);
        assertEquals(1234567890L, node.getCreatedAt());

        node.setUpdatedAt(1234567900L);
        assertEquals(1234567900L, node.getUpdatedAt());
    }

    @Test
    void testWithAddEvent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_1");
        node.setContent("New memory");
        node.setEvent("ADD");
        node.setCreatedAt(1234567890L);
        node.setUpdatedAt(1234567890L);

        assertEquals("mem_1", node.getMemoryNodeId());
        assertEquals("New memory", node.getContent());
        assertEquals("ADD", node.getEvent());
        assertNull(node.getOldContent());
        assertEquals(1234567890L, node.getCreatedAt());
        assertEquals(1234567890L, node.getUpdatedAt());
    }

    @Test
    void testWithUpdateEvent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_2");
        node.setContent("Updated memory");
        node.setEvent("UPDATE");
        node.setOldContent("Old memory");
        node.setCreatedAt(1234567890L);
        node.setUpdatedAt(1234567900L);

        assertEquals("mem_2", node.getMemoryNodeId());
        assertEquals("Updated memory", node.getContent());
        assertEquals("UPDATE", node.getEvent());
        assertEquals("Old memory", node.getOldContent());
        assertEquals(1234567890L, node.getCreatedAt());
        assertEquals(1234567900L, node.getUpdatedAt());
    }

    @Test
    void testWithDeleteEvent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_3");
        node.setContent("Deleted memory");
        node.setEvent("DELETE");
        node.setCreatedAt(1234567890L);
        node.setUpdatedAt(1234567910L);

        assertEquals("mem_3", node.getMemoryNodeId());
        assertEquals("Deleted memory", node.getContent());
        assertEquals("DELETE", node.getEvent());
        assertNull(node.getOldContent());
        assertEquals(1234567890L, node.getCreatedAt());
        assertEquals(1234567910L, node.getUpdatedAt());
    }

    @Test
    void testWithAllFields() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_full");
        node.setContent("Full memory content");
        node.setEvent("UPDATE");
        node.setOldContent("Previous content");
        node.setCreatedAt(1234567880L);
        node.setUpdatedAt(1234567920L);

        assertEquals("mem_full", node.getMemoryNodeId());
        assertEquals("Full memory content", node.getContent());
        assertEquals("UPDATE", node.getEvent());
        assertEquals("Previous content", node.getOldContent());
        assertEquals(1234567880L, node.getCreatedAt());
        assertEquals(1234567920L, node.getUpdatedAt());
    }

    @Test
    void testWithNullValues() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId(null);
        node.setContent(null);
        node.setEvent(null);
        node.setOldContent(null);
        node.setCreatedAt(null);
        node.setUpdatedAt(null);

        assertNull(node.getMemoryNodeId());
        assertNull(node.getContent());
        assertNull(node.getEvent());
        assertNull(node.getOldContent());
        assertNull(node.getCreatedAt());
        assertNull(node.getUpdatedAt());
    }

    @Test
    void testWithEmptyContent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_empty");
        node.setContent("");
        node.setEvent("ADD");

        assertEquals("mem_empty", node.getMemoryNodeId());
        assertEquals("", node.getContent());
        assertEquals("ADD", node.getEvent());
    }

    @Test
    void testWithDifferentEventTypes() {
        BailianMemoryNode addNode = new BailianMemoryNode();
        addNode.setEvent("ADD");

        BailianMemoryNode updateNode = new BailianMemoryNode();
        updateNode.setEvent("UPDATE");

        BailianMemoryNode deleteNode = new BailianMemoryNode();
        deleteNode.setEvent("DELETE");

        assertEquals("ADD", addNode.getEvent());
        assertEquals("UPDATE", updateNode.getEvent());
        assertEquals("DELETE", deleteNode.getEvent());
    }

    @Test
    void testWithTimestamps() {
        BailianMemoryNode node = new BailianMemoryNode();
        long now = System.currentTimeMillis() / 1000 * 1000;

        node.setCreatedAt(now);
        node.setUpdatedAt(now + 1000);

        assertEquals(now, node.getCreatedAt());
        assertEquals(now + 1000, node.getUpdatedAt());
    }

    @Test
    void testWithNullOldContentForAddEvent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setEvent("ADD");
        node.setOldContent(null);

        assertEquals("ADD", node.getEvent());
        assertNull(node.getOldContent());
    }

    @Test
    void testWithOldContentOnlyForUpdateEvent() {
        BailianMemoryNode node = new BailianMemoryNode();
        node.setEvent("UPDATE");
        node.setOldContent("Previous version");

        assertEquals("UPDATE", node.getEvent());
        assertEquals("Previous version", node.getOldContent());
    }
}
