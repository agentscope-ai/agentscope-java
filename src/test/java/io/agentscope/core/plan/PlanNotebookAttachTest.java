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
package io.agentscope.core.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanNotebookAttachTest {

    private PlanNotebook notebook;
    private ReActAgent mockAgent;
    private Toolkit mockToolkit;
    private Memory mockMemory;

    @BeforeEach
    void setUp() {
        notebook = PlanNotebook.builder().build();

        // Create mock agent with required dependencies
        mockToolkit = new Toolkit();
        mockMemory = mock(Memory.class);
        Model mockModel = mock(Model.class);

        mockAgent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(mockToolkit)
                        .memory(mockMemory)
                        .build();
    }

    @Test
    void testAttachTo() {
        // Before attach, not attached
        assertFalse(notebook.isAttachedTo(mockAgent));

        // Attach
        PlanNotebook result = notebook.attachTo(mockAgent);

        // Should return same notebook instance for chaining
        assertNotNull(result);

        // Should be attached now
        assertTrue(notebook.isAttachedTo(mockAgent));
    }

    @Test
    void testAttachToNullAgent() {
        try {
            notebook.attachTo(null);
            throw new AssertionError("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("cannot be null"));
        }
    }

    @Test
    void testAttachToTwice() {
        // First attach succeeds
        notebook.attachTo(mockAgent);

        // Second attach to same agent should fail
        try {
            notebook.attachTo(mockAgent);
            throw new AssertionError("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("already attached"));
        }
    }

    @Test
    void testDetachFrom() {
        // Attach first
        notebook.attachTo(mockAgent);
        assertTrue(notebook.isAttachedTo(mockAgent));

        // Detach
        notebook.detachFrom(mockAgent);

        // Should not be attached anymore
        assertFalse(notebook.isAttachedTo(mockAgent));
    }

    @Test
    void testDetachFromNotAttached() {
        // Detaching from non-attached agent should throw IllegalStateException
        try {
            notebook.detachFrom(mockAgent);
            throw new AssertionError("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not attached"));
        }
    }

    @Test
    void testDetachFromAll() {
        // Create multiple mock agents
        Model mockModel = mock(Model.class);
        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("Agent1")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(mock(Memory.class))
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("Agent2")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(mock(Memory.class))
                        .build();

        // Attach to both
        notebook.attachTo(agent1);
        notebook.attachTo(agent2);

        assertTrue(notebook.isAttachedTo(agent1));
        assertTrue(notebook.isAttachedTo(agent2));

        // Detach from all
        notebook.detachFromAll();

        // Should not be attached to any
        assertFalse(notebook.isAttachedTo(agent1));
        assertFalse(notebook.isAttachedTo(agent2));
    }

    @Test
    void testIsAttachedTo() {
        // Initially not attached
        assertFalse(notebook.isAttachedTo(mockAgent));

        // After attach, is attached
        notebook.attachTo(mockAgent);
        assertTrue(notebook.isAttachedTo(mockAgent));

        // After detach, not attached again
        notebook.detachFrom(mockAgent);
        assertFalse(notebook.isAttachedTo(mockAgent));
    }

    @Test
    void testAttachToMultipleAgents() {
        Model mockModel = mock(Model.class);

        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("Agent1")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(mock(Memory.class))
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("Agent2")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(mock(Memory.class))
                        .build();

        // Attach to first agent
        notebook.attachTo(agent1);
        assertTrue(notebook.isAttachedTo(agent1));
        assertFalse(notebook.isAttachedTo(agent2));

        // Attach to second agent
        notebook.attachTo(agent2);
        assertTrue(notebook.isAttachedTo(agent1));
        assertTrue(notebook.isAttachedTo(agent2));

        // Detach from first
        notebook.detachFrom(agent1);
        assertFalse(notebook.isAttachedTo(agent1));
        assertTrue(notebook.isAttachedTo(agent2));
    }

    @Test
    void testReattachAfterDetach() {
        // Attach
        notebook.attachTo(mockAgent);
        assertTrue(notebook.isAttachedTo(mockAgent));

        // Detach
        notebook.detachFrom(mockAgent);
        assertFalse(notebook.isAttachedTo(mockAgent));

        // Reattach should work
        notebook.attachTo(mockAgent);
        assertTrue(notebook.isAttachedTo(mockAgent));
    }
}
