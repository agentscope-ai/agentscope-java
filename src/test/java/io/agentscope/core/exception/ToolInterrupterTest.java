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
package io.agentscope.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolInterrupter.
 */
@DisplayName("ToolInterrupter Tests")
class ToolInterrupterTest {

    @AfterEach
    void cleanup() {
        // Reset interruption state after each test
        ToolInterrupter.reset();
    }

    @Test
    @DisplayName("Should not be interrupted initially")
    void testInitialState() {
        assertFalse(ToolInterrupter.isInterrupted());
        assertNull(ToolInterrupter.getState());
    }

    @Test
    @DisplayName("Should throw exception when interrupting with message")
    void testInterruptWithMessage() {
        String message = "Test interruption";

        ToolInterruptedException exception =
                assertThrows(
                        ToolInterruptedException.class, () -> ToolInterrupter.interrupt(message));

        assertEquals(message, exception.getMessage());
        assertEquals(InterruptSource.TOOL, exception.getSource());
        assertTrue(ToolInterrupter.isInterrupted());
    }

    @Test
    @DisplayName("Should throw exception when interrupting without message")
    void testInterruptWithoutMessage() {
        ToolInterruptedException exception =
                assertThrows(ToolInterruptedException.class, ToolInterrupter::interrupt);

        assertNotNull(exception.getMessage());
        assertEquals(InterruptSource.TOOL, exception.getSource());
        assertTrue(ToolInterrupter.isInterrupted());
    }

    @Test
    @DisplayName("Should track interruption state in ThreadLocal")
    void testThreadLocalState() {
        assertFalse(ToolInterrupter.isInterrupted());

        try {
            ToolInterrupter.interrupt("Test");
        } catch (ToolInterruptedException e) {
            // Expected
        }

        assertTrue(ToolInterrupter.isInterrupted());
        assertNotNull(ToolInterrupter.getState());
    }

    @Test
    @DisplayName("Should store interruption message and timestamp")
    void testInterruptionStateContent() {
        String message = "Custom interruption message";

        try {
            ToolInterrupter.interrupt(message);
        } catch (ToolInterruptedException e) {
            // Expected
        }

        ToolInterrupter.InterruptionState state = ToolInterrupter.getState();
        assertNotNull(state);
        assertEquals(message, state.message);
        assertNotNull(state.timestamp);
    }

    @Test
    @DisplayName("Should reset interruption state")
    void testReset() {
        try {
            ToolInterrupter.interrupt("Test");
        } catch (ToolInterruptedException e) {
            // Expected
        }

        assertTrue(ToolInterrupter.isInterrupted());

        ToolInterrupter.reset();

        assertFalse(ToolInterrupter.isInterrupted());
        assertNull(ToolInterrupter.getState());
    }

    @Test
    @DisplayName("Should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        Thread thread1 =
                new Thread(
                        () -> {
                            try {
                                ToolInterrupter.interrupt("Thread 1");
                            } catch (ToolInterruptedException e) {
                                // Expected
                            }
                            assertTrue(ToolInterrupter.isInterrupted());
                            ToolInterrupter.reset();
                        });

        Thread thread2 =
                new Thread(
                        () -> {
                            assertFalse(ToolInterrupter.isInterrupted());
                        });

        thread1.start();
        thread1.join();
        thread2.start();
        thread2.join();
    }

    @Test
    @DisplayName("Should handle multiple resets safely")
    void testMultipleResets() {
        ToolInterrupter.reset();
        ToolInterrupter.reset();
        ToolInterrupter.reset();

        assertFalse(ToolInterrupter.isInterrupted());
    }

    @Test
    @DisplayName("Should detect interruption even after exception is caught")
    void testDetectionAfterCatchingException() {
        try {
            ToolInterrupter.interrupt("Test");
        } catch (ToolInterruptedException e) {
            // Tool code catches the exception
        }

        // Framework should still detect the interruption
        assertTrue(ToolInterrupter.isInterrupted());
        assertNotNull(ToolInterrupter.getState());
    }
}
