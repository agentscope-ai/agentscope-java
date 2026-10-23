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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BailianAddResponse}. */
class BailianAddResponseTest {

    @Test
    void testSettersAndGetters() {
        BailianAddResponse response = new BailianAddResponse();

        response.setRequestId("req_123");
        assertEquals("req_123", response.getRequestId());

        List<BailianAddResponse.BailianMemoryNode> memoryNodes =
                List.of(new BailianAddResponse.BailianMemoryNode());
        response.setMemoryNodes(memoryNodes);
        assertEquals(memoryNodes, response.getMemoryNodes());
    }

    @Test
    void testDefaultConstructor() {
        BailianAddResponse response = new BailianAddResponse();

        assertNull(response.getRequestId());
        assertNull(response.getMemoryNodes());
    }

    @Test
    void testNestedBailianMemoryNodeSettersAndGetters() {
        BailianAddResponse.BailianMemoryNode node = new BailianAddResponse.BailianMemoryNode();

        node.setMemoryNodeId("mem_123");
        assertEquals("mem_123", node.getMemoryNodeId());

        node.setContent("Memory content");
        assertEquals("Memory content", node.getContent());

        node.setEvent("ADD");
        assertEquals("ADD", node.getEvent());

        node.setOldContent("Old content");
        assertEquals("Old content", node.getOldContent());
    }

    @Test
    void testNestedBailianMemoryNodeDefaultConstructor() {
        BailianAddResponse.BailianMemoryNode node = new BailianAddResponse.BailianMemoryNode();

        assertNull(node.getMemoryNodeId());
        assertNull(node.getContent());
        assertNull(node.getEvent());
        assertNull(node.getOldContent());
    }

    @Test
    void testBailianMemoryNodeWithAddEvent() {
        BailianAddResponse.BailianMemoryNode node = new BailianAddResponse.BailianMemoryNode();
        node.setMemoryNodeId("mem_1");
        node.setContent("New memory");
        node.setEvent("ADD");

        assertEquals("mem_1", node.getMemoryNodeId());
        assertEquals("New memory", node.getContent());
        assertEquals("ADD", node.getEvent());
        assertNull(node.getOldContent());
    }

    @Test
    void testBailianMemoryNodeWithUpdateEvent() {
        BailianAddResponse.BailianMemoryNode node = new BailianAddResponse.BailianMemoryNode();
        node.setMemoryNodeId("mem_2");
        node.setContent("Updated memory");
        node.setEvent("UPDATE");
        node.setOldContent("Old memory");

        assertEquals("mem_2", node.getMemoryNodeId());
        assertEquals("Updated memory", node.getContent());
        assertEquals("UPDATE", node.getEvent());
        assertEquals("Old memory", node.getOldContent());
    }

    @Test
    void testBailianMemoryNodeWithDeleteEvent() {
        BailianAddResponse.BailianMemoryNode node = new BailianAddResponse.BailianMemoryNode();
        node.setMemoryNodeId("mem_3");
        node.setContent("Deleted memory");
        node.setEvent("DELETE");

        assertEquals("mem_3", node.getMemoryNodeId());
        assertEquals("Deleted memory", node.getContent());
        assertEquals("DELETE", node.getEvent());
    }

    @Test
    void testResponseWithMultipleMemoryNodes() {
        BailianAddResponse response = new BailianAddResponse();
        response.setRequestId("req_456");

        BailianAddResponse.BailianMemoryNode node1 = new BailianAddResponse.BailianMemoryNode();
        node1.setMemoryNodeId("mem_1");
        node1.setContent("Memory 1");
        node1.setEvent("ADD");

        BailianAddResponse.BailianMemoryNode node2 = new BailianAddResponse.BailianMemoryNode();
        node2.setMemoryNodeId("mem_2");
        node2.setContent("Memory 2");
        node2.setEvent("ADD");

        List<BailianAddResponse.BailianMemoryNode> nodes = List.of(node1, node2);
        response.setMemoryNodes(nodes);

        assertEquals("req_456", response.getRequestId());
        assertEquals(2, response.getMemoryNodes().size());
        assertEquals("mem_1", response.getMemoryNodes().get(0).getMemoryNodeId());
        assertEquals("mem_2", response.getMemoryNodes().get(1).getMemoryNodeId());
    }

    @Test
    void testResponseWithEmptyMemoryNodes() {
        BailianAddResponse response = new BailianAddResponse();
        response.setRequestId("req_empty");
        response.setMemoryNodes(List.of());

        assertEquals("req_empty", response.getRequestId());
        assertNotNull(response.getMemoryNodes());
        assertEquals(0, response.getMemoryNodes().size());
    }

    @Test
    void testResponseWithNullMemoryNodes() {
        BailianAddResponse response = new BailianAddResponse();
        response.setRequestId("req_null");

        assertNull(response.getMemoryNodes());
    }
}
