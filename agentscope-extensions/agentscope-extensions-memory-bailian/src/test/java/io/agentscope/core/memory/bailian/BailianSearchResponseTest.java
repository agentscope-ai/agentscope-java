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

/** Unit tests for {@link BailianSearchResponse}. */
class BailianSearchResponseTest {

    @Test
    void testSettersAndGetters() {
        BailianSearchResponse response = new BailianSearchResponse();

        response.setRequestId("req_123");
        assertEquals("req_123", response.getRequestId());

        List<BailianMemoryNode> memoryNodes = List.of(new BailianMemoryNode());
        response.setMemoryNodes(memoryNodes);
        assertEquals(memoryNodes, response.getMemoryNodes());
    }

    @Test
    void testDefaultConstructor() {
        BailianSearchResponse response = new BailianSearchResponse();

        assertNull(response.getRequestId());
        assertNull(response.getMemoryNodes());
    }

    @Test
    void testResponseWithSingleMemoryNode() {
        BailianSearchResponse response = new BailianSearchResponse();
        response.setRequestId("req_456");

        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_1");
        node.setContent("Memory content");
        node.setEvent("ADD");

        response.setMemoryNodes(List.of(node));

        assertEquals("req_456", response.getRequestId());
        assertNotNull(response.getMemoryNodes());
        assertEquals(1, response.getMemoryNodes().size());
        assertEquals("mem_1", response.getMemoryNodes().get(0).getMemoryNodeId());
        assertEquals("Memory content", response.getMemoryNodes().get(0).getContent());
    }

    @Test
    void testResponseWithMultipleMemoryNodes() {
        BailianSearchResponse response = new BailianSearchResponse();
        response.setRequestId("req_789");

        BailianMemoryNode node1 = new BailianMemoryNode();
        node1.setMemoryNodeId("mem_1");
        node1.setContent("First memory");
        node1.setEvent("ADD");

        BailianMemoryNode node2 = new BailianMemoryNode();
        node2.setMemoryNodeId("mem_2");
        node2.setContent("Second memory");
        node2.setEvent("ADD");

        BailianMemoryNode node3 = new BailianMemoryNode();
        node3.setMemoryNodeId("mem_3");
        node3.setContent("Third memory");
        node3.setEvent("ADD");

        response.setMemoryNodes(List.of(node1, node2, node3));

        assertEquals("req_789", response.getRequestId());
        assertEquals(3, response.getMemoryNodes().size());
        assertEquals("First memory", response.getMemoryNodes().get(0).getContent());
        assertEquals("Second memory", response.getMemoryNodes().get(1).getContent());
        assertEquals("Third memory", response.getMemoryNodes().get(2).getContent());
    }

    @Test
    void testResponseWithEmptyMemoryNodes() {
        BailianSearchResponse response = new BailianSearchResponse();
        response.setRequestId("req_empty");
        response.setMemoryNodes(List.of());

        assertEquals("req_empty", response.getRequestId());
        assertNotNull(response.getMemoryNodes());
        assertEquals(0, response.getMemoryNodes().size());
    }

    @Test
    void testResponseWithNullMemoryNodes() {
        BailianSearchResponse response = new BailianSearchResponse();
        response.setRequestId("req_null");

        assertNull(response.getMemoryNodes());
    }

    @Test
    void testResponseWithMemoryNodeHavingAllFields() {
        BailianSearchResponse response = new BailianSearchResponse();
        response.setRequestId("req_full");

        BailianMemoryNode node = new BailianMemoryNode();
        node.setMemoryNodeId("mem_123");
        node.setContent("Full memory content");
        node.setEvent("UPDATE");
        node.setOldContent("Old memory content");
        node.setCreatedAt(1234567890L);
        node.setUpdatedAt(1234567900L);

        response.setMemoryNodes(List.of(node));

        assertEquals("req_full", response.getRequestId());
        assertNotNull(response.getMemoryNodes());
        assertEquals(1, response.getMemoryNodes().size());
        assertEquals("mem_123", response.getMemoryNodes().get(0).getMemoryNodeId());
        assertEquals("Full memory content", response.getMemoryNodes().get(0).getContent());
        assertEquals("UPDATE", response.getMemoryNodes().get(0).getEvent());
        assertEquals("Old memory content", response.getMemoryNodes().get(0).getOldContent());
        assertEquals(1234567890L, response.getMemoryNodes().get(0).getCreatedAt());
        assertEquals(1234567900L, response.getMemoryNodes().get(0).getUpdatedAt());
    }
}
